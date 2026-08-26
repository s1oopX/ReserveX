package com.reservex.lua;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压测脚本 {@code stress-test/scripts/grab.lua} 的形态契约。
 *
 * <p>为什么只能断言形态:wrk 不在 CI 里(也不该在 —— 它要打真实服务),这个脚本
 * 在合并前唯一的检查机会就是读它。而它错了不会报错,只会**静默给出一份看起来
 * 合理的假报告**,这比崩掉危险得多。
 *
 * <p>三条断言各对应一个真实修过的 bug,共同的根因是 wrk 的环境模型
 * (wrk/SCRIPTING 的 Overview):setup 与 done 共用一个环境,每个线程各有独立
 * 环境,两者不连通。
 */
class StressScriptContractTest {

    /**
     * bug 1:早先 {@code setup()} 里按 {@code token_count} 取随机偏移。但
     * {@code setup} 所在环境从没跑过 {@code init()},那里 {@code token_count}
     * 恒为 0,于是偏移恒为 0 —— 所有线程齐步走同一批 token,QUOTA_USED 虚高,
     * 而脚本自己的文件头正警告过这种自骗。
     */
    @Test
    void setupDoesNotReadStateThatOnlyInitProduces() throws IOException {
        String setup = functionBody("setup");
        assertThat(setup)
                .as("setup 所在环境没跑过 init(),token_count 在那里恒为 0")
                .doesNotContain("token_count");
        assertThat(setup)
                .as("线程分区靠 thread_id + nthreads,两者都得在 setup 里下发")
                .contains("thread:set(\"thread_id\"")
                .contains("t:set(\"nthreads\"");
    }

    /**
     * bug 2:{@code done()} 要读的线程变量必须是全局。加了 {@code local} 就落在
     * 线程环境的词法作用域里,{@code thread:get()} 取不到 —— wrk 自己的
     * {@code scripts/setup.lua} 示例正是靠"不写 local"来传值的。
     */
    @Test
    void threadStateReadByDoneIsGlobalNotLocal() throws IOException {
        String init = functionBody("init");
        for (String global : new String[] {"codes", "sent", "reused"}) {
            assertThat(init)
                    .as("%s 要被 done() 跨环境 thread:get(),不能声明成 local", global)
                    .contains(global + " = ")
                    .doesNotContain("local " + global);
        }
    }

    /**
     * bug 3:早先 {@code done()} 直接读本环境的 {@code codes}。那张表从头到尾是
     * 空的(累加发生在各线程环境里),业务码分布永远印不出来 —— 而 09 §三 的底线
     * 两条「成功预约数 = capacity」「超卖数 0」正是靠它断言的。
     */
    @Test
    void doneAggregatesPerThreadInsteadOfReadingItsOwnEmptyTable() throws IOException {
        String done = functionBody("done");
        assertThat(done)
                .as("必须逐线程取回再合并,否则分布恒为空")
                .contains("thread:get(\"codes\")")
                .contains("for _, thread in ipairs(threads) do");
        assertThat(done)
                .as("池子被回绕时结果不可用,必须显式声明而不是静默出数")
                .contains("total_reused > 0");
    }

    /** 取出某个顶层 {@code function name(...)} 到其配平 {@code end} 之间的正文。 */
    private String functionBody(String name) throws IOException {
        String script = Files.readString(script(), StandardCharsets.UTF_8);
        Matcher start = Pattern.compile("^function\\s+" + name + "\\s*\\(", Pattern.MULTILINE)
                .matcher(script);
        assertThat(start.find()).as("脚本里找不到顶层函数 %s", name).isTrue();
        // 顶层函数以行首 end 收尾(缩进的 end 属于内层 if/for)
        Matcher end = Pattern.compile("^end$", Pattern.MULTILINE).matcher(script);
        assertThat(end.find(start.end())).as("函数 %s 没有行首 end 收尾", name).isTrue();
        return script.substring(start.end(), end.start());
    }

    private static Path script() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("stress-test").resolve("scripts").resolve("grab.lua");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository file not found: stress-test/scripts/grab.lua");
    }
}
