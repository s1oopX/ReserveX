package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.entity.ReconcileLog;
import com.reservex.entity.Slot;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.SlotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 批量过期扫描(04 §五 / 06 §四)。
 *
 * <p>{@code ReservationMapper.expireBySlot} 的唯一调用点 —— SQL 早已存在但此前无人调,
 * 过期状态只靠抢号时 occupy 标记 + 落库消费者判定,没有定时批量置 DB 侧 EXPIRED。
 * 本任务遍历今日已过 {@code valid_until} 的 slot,广播两库批量 {@code RESERVED→EXPIRED}。
 *
 * <p>⚠️ {@code now} 由应用层按 {@code reservex.zone} 传入,**不用 SQL NOW()}(见
 * ReservationMapper.expireBySlot 注释:容器时区漏配成 UTC 时 NOW() 早 8h,会把当天
 * 还有效的预约全刷成 EXPIRED)。
 *
 * <p>不碰 occupy:occupy TTL 30min 自动过期,过期状态的主要消费者是 DB 侧列表与对账。
 */
@Slf4j
@Component
public class ExpiryScanner {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final SlotMapper slotMapper;
    private final ReservationMapper reservationMapper;
    private final ReconcileLogMapper reconcileMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final TransactionTemplate singleTx;

    public ExpiryScanner(SlotMapper slotMapper,
                          ReservationMapper reservationMapper,
                          ReconcileLogMapper reconcileMapper,
                          IdGenerator idGenerator,
                          TimeSupport time,
                          @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager) {
        this.slotMapper = slotMapper;
        this.reservationMapper = reservationMapper;
        this.reconcileMapper = reconcileMapper;
        this.idGenerator = idGenerator;
        this.time = time;
        this.singleTx = new TransactionTemplate(singleTxManager);
    }

    @Scheduled(cron = "${reservex.reconcile.crons.expire:0 * * * * ?}")
    @Async("reconcileExecutor")
    public void scan() {
        LocalDateTime now = time.now();
        int total = 0;
        for (Slot slot : slotMapper.selectByDate(time.today())) {
            if (slot.getValidUntil() == null || !slot.getValidUntil().isBefore(now)) {
                continue;
            }
            int affected = reservationMapper.expireBySlot(slot.getSlotId(), now);
            if (affected > 0) {
                log.info("场次过期 slot={} 过期预约数={}", slot.getSlotId(), affected);
            }
            total += affected;
        }
        if (total > 0) {
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
        log.setFixAction("expired");
        log.setCreateAt(now);
        singleTx.executeWithoutResult(status -> reconcileMapper.insertIgnore(log));
    }
}
