package com.reservex.bootstrap;

import cn.hutool.crypto.digest.BCrypt;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.AuditLog;
import com.reservex.entity.User;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 超管引导(08 §4.1)—— 把 seed 里的哨兵密码换成真正的 BCrypt。
 *
 * <p><b>为什么密码不写在 SQL 里。</b>{@code 02-seed.sql} 只插一行 {@code password='!'}
 * 的占位({@code '!'} 不可能是 BCrypt 串,BCrypt 恒以 {@code $2} 开头,故它匹配不了任何输入)。
 * 若在 SQL 里写死一个 BCrypt hash,等于把一个固定密码**永久钉进 git** —— 所有人克隆仓库
 * 就拿到了生产超管口令,而且改不掉(改了 SQL 也不会重跑,见下)。
 *
 * <p><b>为什么必须有这一步(鸡生蛋)。</b>没有超管 → 没人能建场次模板 → 生成任务无模板可用
 * → 整条发布链路启动不了。09 阶段 0 的验收项「超管可登录」就卡在这里。
 *
 * <p><b>为什么是 {@link ApplicationRunner} 而不是 {@code @PostConstruct}。</b>
 * 它要读写数据库,必须等数据源与 Mapper 都装配完。而 {@link SecretGuard} /
 * {@link ConfigGuard} 只读配置,所以用 {@code @PostConstruct} 抢在最前面 ——
 * **密钥不合规就该在连库之前拒绝启动**。
 *
 * <p>⚠️ <b>超管行必须在 {@code reservex_ds1}</b>:{@code user_id=1},而
 * {@code 1 mod 2 = 1}。插错成 ds0 的现象极具误导性 —— {@code email_route} 里明明有
 * {@code admin@reservex.local → 1} 的映射,但登录第 2 跳按 {@code user_id=1} 路由到 ds1
 * 查不到 → 报「超管不存在」,看起来像**孤儿 route**,实际是 seed 插错了库。
 * 这是阶段 0 最容易卡住的一条,故本类查不到时给出明确指向。
 *
 * <p>⚠️ <b>{@code role='ADMIN'} 只能由 seed/本类产生。</b>注册接口的 {@code role} 写死
 * {@code 'USER'} —— 系统里第一个 ADMIN 必须由部署产生,不能由任何 HTTP 端点产生,
 * 否则那个端点本身就是提权漏洞(07 §3·补·3 红线)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    /**
     * {@code 02-seed.sql} 写入的哨兵值。约定在两处(SQL 与此处),改一处必须改另一处 ——
     * 故这里用常量而不是字面量散落,并在 SQL 注释里指回本类。
     */
    public static final String PASSWORD_SENTINEL = "!";

    private final ReserveXProperties props;
    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;

    @Override
    public void run(ApplicationArguments args) {
        ReserveXProperties.AdminBootstrap cfg = props.getAdminBootstrap();
        if (!cfg.isEnabled()) {
            log.info("超管引导已关闭(reservex.admin-bootstrap.enabled=false)");
            return;
        }

        // ① 按分片键查。查不到就 fail-fast,**不自建** ——
        //    自建会掩盖真正的问题:initdb 只在数据目录为空时执行一次,
        //    查不到几乎总是意味着开发者的机器不干净,需要 `docker compose down -v`。
        User admin = userMapper.selectById(cfg.getUserId());
        if (admin == null) {
            throw new IllegalStateException(
                    "超管行不存在(user_id=" + cfg.getUserId() + ")。两种可能:"
                            + "① 02-seed.sql 没执行 —— initdb 只在数据目录为空时跑一次,"
                            + "改了 SQL 后必须 `docker compose down -v` 重来;"
                            + "② seed 把行插进了错的库 —— user_id=" + cfg.getUserId()
                            + " 时 " + cfg.getUserId() + " mod 2 = " + (cfg.getUserId() % 2)
                            + ",行必须在 reservex_ds" + (cfg.getUserId() % 2) + "(08 §4.1 坑 1)");
        }

        // ② 已引导过或超管已自行改密 → 直接返回,**绝不覆盖**。
        //    每次启动都重置密码,会让超管改的密码在下次重启后静默失效。
        if (!PASSWORD_SENTINEL.equals(admin.getPassword())) {
            log.info("超管已引导过,跳过(user_id={})", cfg.getUserId());
            return;
        }

        bootstrap(cfg);
    }

    /**
     * <b>这里故意不加事务。</b>两个写落在**不同数据源**(user 在分库、audit_log 在单库),
     * 单个 {@code @Transactional} 无论挂哪个事务管理器都只覆盖一半 ——
     * 写了反而制造"看起来原子"的错觉。而且本类是自己调自己,
     * Spring 事务靠 AOP 代理实现,**同类内部调用注解静默不生效**,连那一半都覆盖不到。
     *
     * <p>不需要事务的理由:哨兵条件让 UPDATE 天然幂等,失败重启即重来。
     *
     * <p>⚠️ 认下的缺口:若 UPDATE 成功而 audit 插入失败,重启后 UPDATE 返 0 → 跳过,
     * **那条审计永久缺失**。不调换顺序是因为倒过来更坏:审计先写而 UPDATE 失败,
     * 会留下一条"引导过了"的假记录。按 00 §6.3 纪律②,动作有 CAS 时审计成功后写。
     */
    private void bootstrap(ReserveXProperties.AdminBootstrap cfg) {
        String bcrypt = BCrypt.hashpw(cfg.getInitPassword());

        // 条件里带哨兵值 → 并发/重复启动下只有一个能成功,天然幂等
        int updated = userMapper.bootstrapAdminPassword(
                cfg.getUserId(), PASSWORD_SENTINEL, bcrypt, time.now());
        if (updated == 0) {
            log.info("超管密码已被并发引导,跳过");
            return;
        }

        AuditLog audit = new AuditLog();
        audit.setId(idGenerator.nextId());
        audit.setOperatorType("SYSTEM");
        audit.setOperatorId(null);
        audit.setAction("ADMIN_BOOTSTRAP");
        audit.setTargetType("USER");
        audit.setTargetId(cfg.getUserId());
        // ⚠️ 不记密码本身,连长度都不记 —— 审计表是给人看的
        audit.setBefore("{\"password\":\"<sentinel>\"}");
        audit.setAfter("{\"password\":\"<bcrypt>\"}");
        audit.setRequestId("bootstrap");
        audit.setCreateAt(time.now());
        auditLogMapper.insert(audit);

        log.warn("超管初始密码已写入(user_id={}, email={})。"
                        + "该密码来自 .env,团队可见 —— 首次登录后请立即修改",
                cfg.getUserId(), cfg.getEmail());
    }
}
