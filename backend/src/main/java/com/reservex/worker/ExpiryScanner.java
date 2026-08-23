package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.entity.ReconcileLog;
import com.reservex.entity.Reservation;
import com.reservex.id.IdGenerator;
import com.reservex.message.TimeoutExpireMessage;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 过期消息扫描(04 §五 / 06 §四)。
 *
 * <p>只查询已到期的 RESERVED 并投 timeout；消费者逐条 CAS 并补齐 state/event。
 * 查询不限定 slot_date,因此任务跨日停机后仍能追赶。
 *
 * <p>⚠️ {@code now} 由应用层按 {@code reservex.zone} 传入,**不用 SQL NOW()}(见
 * 容器时区漏配成 UTC 时 SQL NOW() 早 8h,会把当天还有效的预约全刷成 EXPIRED)。
 *
 * <p>不碰 occupy:occupy TTL 30min 自动过期,过期状态的主要消费者是 DB 侧列表与对账。
 */
@Slf4j
@Component
public class ExpiryScanner {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Duration PUBLISH_GUARD_TTL = Duration.ofMinutes(5);

    private final ReservationMapper reservationMapper;
    private final ReconcileLogMapper reconcileMapper;
    private final RocketMQTemplate rocketMQ;
    private final StringRedisTemplate redis;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final TransactionTemplate singleTx;

    public ExpiryScanner(ReservationMapper reservationMapper,
                          ReconcileLogMapper reconcileMapper,
                          RocketMQTemplate rocketMQ,
                          StringRedisTemplate redis,
                          IdGenerator idGenerator,
                          TimeSupport time,
                          @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager) {
        this.reservationMapper = reservationMapper;
        this.reconcileMapper = reconcileMapper;
        this.rocketMQ = rocketMQ;
        this.redis = redis;
        this.idGenerator = idGenerator;
        this.time = time;
        this.singleTx = new TransactionTemplate(singleTxManager);
    }

    @Scheduled(cron = "${reservex.reconcile.crons.expire:0 * * * * ?}")
    @Async("reconcileExecutor")
    public void scan() {
        LocalDateTime now = time.now();
        int total = 0;
        for (Reservation candidate : reservationMapper.selectExpiryCandidates(now, 500)) {
            long rno = candidate.getReservationNo();
            String guardKey = "timeout:publishing:" + rno;
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    guardKey, "1", PUBLISH_GUARD_TTL))) {
                continue;
            }
            try {
                rocketMQ.syncSend("timeout", new TimeoutExpireMessage(
                        "te-" + rno, rno, candidate.getUserId(), candidate.getValidUntil(),
                        "timeout-" + rno));
            } catch (RuntimeException e) {
                redis.delete(guardKey);
                log.error("过期消息投递失败 rno={}", rno, e);
                continue;
            }
            total++;
        }
        if (total > 0) {
            log.info("过期预约已投递 count={}", total);
            recordLog(now, total);
        }
    }

    private void recordLog(LocalDateTime now, int total) {
        ReconcileLog log = new ReconcileLog();
        log.setId(idGenerator.nextId());
        log.setTaskType("expire");
        log.setPeriod(now.format(PERIOD));
        // 全局过期任务跨多 slot 归并,无单一 slot 归属:slot_id=0 让 uk 去重。
        log.setSlotId(0L);
        log.setRedisOccupied(null);
        log.setDbOccupied(null);
        log.setReservationCnt(total);
        log.setDiff(total);
        log.setFixAction("timeout-published");
        log.setCreateAt(now);
        singleTx.executeWithoutResult(status -> reconcileMapper.insertIgnore(log));
    }
}
