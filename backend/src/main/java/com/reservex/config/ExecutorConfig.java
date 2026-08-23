package com.reservex.config;

import com.reservex.config.ReserveXProperties.Executor.PoolSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 舱壁线程池(08 §7.4)—— 把"抢号/核销/查询独立线程池"这句承诺落成具体配置项。
 *
 * <p>原文档三处(§6.2 舱壁隔离、05 §五、§6.4 容量看板)都把落点指向 08,而 08 从未定义,
 * 那条亮点就是空承诺 —— 与 {@code state_log} 曾经"有表无写入点"同构。
 *
 * <p>⚠️ <b>抢号与核销刻意不放异步池。</b>它们是同步返回的请求线程(Tomcat),
 * 舱壁体现在:Tomcat 线程池 + 各自的限流维度 + 各自的 Redis key 命名空间。
 * 本类定义的是**会抢线程的旁路任务**,防它们把 Tomcat 或公共池吃干。
 */
@Configuration
@RequiredArgsConstructor
public class ExecutorConfig implements SchedulingConfigurer {

    private final ReserveXProperties props;

    /**
     * 邮件池:界外调用(SMTP),超时高、不可控,必须独立且**有界**。
     *
     * <p>拒绝策略 {@code abort}:队列满时本轮提醒快速失败,下个扫描周期会再次尝试。
     * 不能用 caller-runs —— 调用线程是调度线程,SMTP 卡住会拖停其他定时任务。
     */
    @Bean("mailExecutor")
    public ThreadPoolTaskExecutor mailExecutor() {
        return build(props.getExecutor().getMail(), "mail-");
    }

    /**
     * 对账池:11 类对账,分页 + 限速,慢但不紧急。
     *
     * <p>拒绝策略 {@code abort}:拒了下个周期还会跑(对账任务本身幂等)。
     * **不能用 caller-runs** —— 调用线程是 {@code @Scheduled} 的调度线程,
     * 让它去跑一轮分页对账会把整个定时任务体系卡住。
     */
    @Bean("reconcileExecutor")
    public ThreadPoolTaskExecutor reconcileExecutor() {
        return build(props.getExecutor().getReconcile(), "reconcile-");
    }

    /**
     * {@code @Scheduled} 调度池。
     *
     * <p>⚠️ Spring 默认 {@code pool-size=1} —— 对本项目是错的:11 类对账 + 卡单巡检 +
     * pending scanner + slot 生成共十几个定时任务共用一根线程,**任何一个卡住,
     * 其余全部停摆**,而现象只是"对账好久没出数据",不会有任何报错。
     * 这是 README 纪律 #6"每个中间件的默认值都要问对本项目是对的吗"的第三处。
     */
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.getExecutor().getScheduler().getPoolSize());
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        // 优雅停机:必须 < compose 的 stop_grace_period(45s),否则被 SIGKILL 打断
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }

    private ThreadPoolTaskExecutor build(PoolSpec spec, String namePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(spec.getCore());
        executor.setMaxPoolSize(spec.getMax());
        executor.setQueueCapacity(spec.getQueue());
        executor.setKeepAliveSeconds(spec.getKeepAliveSec());
        executor.setThreadNamePrefix(namePrefix);
        executor.setRejectedExecutionHandler(rejectedHandler(spec.getRejected()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    private RejectedExecutionHandler rejectedHandler(String name) {
        return switch (name) {
            case "caller-runs" -> new ThreadPoolExecutor.CallerRunsPolicy();
            case "abort" -> new ThreadPoolExecutor.AbortPolicy();
            case "discard" -> new ThreadPoolExecutor.DiscardPolicy();
            default -> throw new IllegalStateException(
                    "未知的拒绝策略 " + name + ",只允许 caller-runs / abort / discard");
        };
    }
}
