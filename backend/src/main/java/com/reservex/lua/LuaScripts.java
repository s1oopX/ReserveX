package com.reservex.lua;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 四个 Lua 脚本的加载与执行(04 §七)。
 *
 * <p><b>用 {@code scriptLoad} 预加载 + {@code evalSha} 调用</b>,不每次传全文:
 * 抢号脚本约 2KB,峰值 2000 QPS 下每次传全文就是 4MB/s 的无谓网络开销,
 * 而它恰好压在抢号这条最不能慢的路径上。
 *
 * <p>⚠️ <b>编码红线:改了 lua 文件必须重启 backend。</b>SHA 在启动时算一次并缓存,
 * 改了文件而不重启,跑的还是 Redis 里那份旧脚本 —— 现象是"我明明改了 Lua 但行为没变",
 * 联调期最容易踩。(要在不重启下生效需 {@code SCRIPT FLUSH},但那会波及所有脚本。)
 *
 * <p>⚠️ <b>启动时就加载,不惰性加载。</b>惰性加载会把"脚本语法错误"推迟到第一次抢号时
 * 才暴露 —— 那是放号瞬间,最不该出错的时刻。启动即加载 = 语法错误在启动时就拒绝服务。
 */
@Slf4j
@Component
public class LuaScripts {

    /** 脚本清单。文件名与 04 §七 的对照表一一对应,改名等于改契约。 */
    public enum Script {
        /** 10.1 抢号 + 判重 + 借桶 + 限流。返回 1 成功 / 0 售罄 / -1 配额已用 / -2 限流。 */
        GRAB("lua/grab.lua"),
        /** 10.2a 创建失败补偿回滚。返回 1 已回滚 / 0 已回滚过。 */
        COMPENSATE("lua/compensate.lua"),
        /** 10.3 放号初始化桶。必须在 released 0→1 CAS 成功后才调。 */
        RELEASE("lua/release.lua"),
        /** 增容。逐桶增量,只增不减。 */
        INCR("lua/incr.lua");

        private final String path;

        Script(String path) {
            this.path = path;
        }
    }

    private final RedissonClient redisson;
    private final Map<Script, String> shaCache = new EnumMap<>(Script.class);

    public LuaScripts(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @PostConstruct
    public void loadAll() {
        RScript script = redisson.getScript(StringCodec.INSTANCE);
        for (Script s : Script.values()) {
            String body = read(s.path);
            String sha = script.scriptLoad(body);
            shaCache.put(s, sha);
            log.info("Lua 已加载 {} sha={}", s.path, sha);
        }
    }

    /**
     * 执行脚本。
     *
     * <p>⚠️ <b>KEYS 与 ARGV 的顺序是脚本契约的一部分</b>,不是实现细节:
     * {@code grab.lua} 的 {@code KEYS[1]} 是命中桶、{@code KEYS[2..n]} 必须是
     * <b>环形</b>借桶顺序({@code (bucket_no + i) % bucket_count}),末尾两位
     * {@code KEYS[n+1]=ratelimit:user:{userId}}、{@code KEYS[n+2]=ratelimit:slot:{slotId}}
     * 是 D5 限流折叠进来的(保持 2 round-trip)。ARGV[15]/ARGV[16] 是 user/slot rps。
     * 顺序传错不会报错,只会让借桶偏向固定几个桶 → 倾斜,而压测埋点
     * {@code stats:borrow:*} 是唯一能看出来的地方。
     *
     * @return 脚本返回的整数(各脚本的返回值语义见 {@link Script} 注释)
     */
    public Long evalLong(Script script, List<Object> keys, Object... argv) {
        String sha = shaCache.get(script);
        if (sha == null) {
            throw new IllegalStateException("Lua 脚本未加载:" + script + ",检查启动日志");
        }
        return redisson.getScript(StringCodec.INSTANCE).evalSha(
                RScript.Mode.READ_WRITE, sha, RScript.ReturnType.INTEGER, keys, argv);
    }

    private String read(String classpath) {
        try (var in = new ClassPathResource(classpath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "读不到 Lua 脚本 " + classpath + " —— 它必须在 backend/src/main/resources/lua/ 下"
                            + "且被打进 jar(04 §七)", e);
        }
    }
}
