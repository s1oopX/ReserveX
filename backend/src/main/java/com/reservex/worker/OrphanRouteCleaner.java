package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.EmailRoute;
import com.reservex.entity.PhoneRoute;
import com.reservex.entity.ReconcileLog;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.single.EmailRouteMapper;
import com.reservex.mapper.single.PhoneRouteMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.sharding.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 孤儿 route 清理(03 §八·补 / 06 §四)。
 *
 * <p>注册是跨库两写:先写单库 route(抢 email/phone 唯一性),再写分库 user。
 * 第二步失败时 {@code AuthService} 会补偿删 route;但**补偿删也失败**时,留下永久孤儿 route ——
 * 占着 email/phone 不让任何人注册,且没有任何自愈路径。
 *
 * <p>本任务扫超龄 route,回查对应 user 是否存在,不存在则删。{@code min-age} 守卫
 * (默认 10min)挡住仍在途的注册:注册是秒级完成,10min 远超正常窗口。
 *
 * <p>⚠️ user 查询走分片键 {@code user_id}({@code selectBatchIds} / {@code selectById}),
 * ShardingSphere 按分片键路由不广播;**绝不能按 email 查分库 user**(见 UserMapper 注释)。
 */
@Slf4j
@Component
public class OrphanRouteCleaner {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int PAGE = 500;

    private final EmailRouteMapper emailRouteMapper;
    private final PhoneRouteMapper phoneRouteMapper;
    private final UserMapper userMapper;
    private final ReconcileLogMapper reconcileMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final ReserveXProperties props;
    private final TransactionTemplate singleTx;

    public OrphanRouteCleaner(EmailRouteMapper emailRouteMapper,
                              PhoneRouteMapper phoneRouteMapper,
                              UserMapper userMapper,
                              ReconcileLogMapper reconcileMapper,
                              IdGenerator idGenerator,
                              TimeSupport time,
                              ReserveXProperties props,
                              @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager) {
        this.emailRouteMapper = emailRouteMapper;
        this.phoneRouteMapper = phoneRouteMapper;
        this.userMapper = userMapper;
        this.reconcileMapper = reconcileMapper;
        this.idGenerator = idGenerator;
        this.time = time;
        this.props = props;
        this.singleTx = new TransactionTemplate(singleTxManager);
    }

    @Scheduled(cron = "${reservex.reconcile.crons.orphan-route:0 0 7 * * ?}")
    @Async("reconcileExecutor")
    public void clean() {
        LocalDateTime cutoff = time.now().minusMinutes(props.getReconcile().getOrphanRouteMinAgeMin());
        int deleted = scanEmail(cutoff) + scanPhone(cutoff);
        if (deleted > 0) {
            recordLog(cutoff, deleted);
            log.warn("孤儿 route 清理完成 deleted={} cutoff={}", deleted, cutoff);
        }
    }

    private int scanEmail(LocalDateTime cutoff) {
        int deleted = 0;
        while (true) {
            List<EmailRoute> candidates = emailRouteMapper.selectOrphansOlderThan(cutoff, PAGE);
            if (candidates.isEmpty()) {
                break;
            }
            deleted += purgeEmails(candidates);
            if (candidates.size() < PAGE) {
                break;
            }
        }
        return deleted;
    }

    private int scanPhone(LocalDateTime cutoff) {
        int deleted = 0;
        while (true) {
            List<PhoneRoute> candidates = phoneRouteMapper.selectOrphansOlderThan(cutoff, PAGE);
            if (candidates.isEmpty()) {
                break;
            }
            deleted += purgePhones(candidates);
            if (candidates.size() < PAGE) {
                break;
            }
        }
        return deleted;
    }

    private int purgeEmails(List<EmailRoute> candidates) {
        Set<Long> userIds = new HashSet<>();
        for (EmailRoute r : candidates) {
            userIds.add(r.getUserId());
        }
        Set<Long> missing = findMissingUsers(userIds);
        int deleted = 0;
        for (EmailRoute route : candidates) {
            if (!missing.contains(route.getUserId())) {
                continue;
            }
            // 二次确认:min-age 边界上恰好注册完成时,首次批量查可能尚未落库,
            // 单查一次挡住这条窄窗口。仍不存在才删。
            if (userMapper.selectById(route.getUserId()) != null) {
                continue;
            }
            singleTx.executeWithoutResult(status ->
                    emailRouteMapper.deleteByEmailAndUser(route.getEmail(), route.getUserId()));
            deleted++;
            log.info("清理孤儿 email_route email={} userId={}", route.getEmail(), route.getUserId());
        }
        return deleted;
    }

    private int purgePhones(List<PhoneRoute> candidates) {
        Set<Long> userIds = new HashSet<>();
        for (PhoneRoute r : candidates) {
            userIds.add(r.getUserId());
        }
        Set<Long> missing = findMissingUsers(userIds);
        int deleted = 0;
        for (PhoneRoute route : candidates) {
            if (!missing.contains(route.getUserId())) {
                continue;
            }
            if (userMapper.selectById(route.getUserId()) != null) {
                continue;
            }
            singleTx.executeWithoutResult(status ->
                    phoneRouteMapper.deleteByPhoneAndUser(route.getPhone(), route.getUserId()));
            deleted++;
            log.info("清理孤儿 phone_route phone={} userId={}", route.getPhone(), route.getUserId());
        }
        return deleted;
    }

    /** 按分片键 user_id 查存在性 —— ShardingSphere 路由到对应分片,不广播。 */
    private Set<Long> findMissingUsers(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> existing = new HashSet<>();
        userMapper.selectBatchIds(userIds).forEach(u -> existing.add(u.getUserId()));
        Set<Long> missing = new HashSet<>(userIds);
        missing.removeAll(existing);
        return missing;
    }

    private void recordLog(LocalDateTime cutoff, int deleted) {
        ReconcileLog log = new ReconcileLog();
        log.setId(idGenerator.nextId());
        log.setTaskType("orphan-route");
        log.setPeriod(time.now().format(PERIOD));
        // 全局任务无 slot 归属:slot_id=0 让 uk_task_period_slot 去重生效。
        log.setSlotId(0L);
        log.setRedisOccupied(null);
        log.setDbOccupied(null);
        log.setReservationCnt(null);
        log.setDiff(deleted);
        log.setFixAction("cleaned");
        log.setCreateAt(time.now());
        reconcileMapper.insertIgnore(log);
    }
}
