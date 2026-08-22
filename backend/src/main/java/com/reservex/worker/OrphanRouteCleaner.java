package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.EmailRoute;
import com.reservex.entity.IdCardIdentity;
import com.reservex.entity.PhoneRoute;
import com.reservex.entity.ReconcileLog;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.single.EmailRouteMapper;
import com.reservex.mapper.single.IdCardIdentityMapper;
import com.reservex.mapper.single.PhoneRouteMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.RegistrationOutboxMapper;
import com.reservex.mapper.sharding.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 孤儿 route 检测(03 §八·补 / 06 §四)。
 *
 * <p>注册是跨库两写:单库 route 与 registration_outbox 先原子提交,再写分库 user。
 * outbox 在进程崩溃后负责重放；本任务只报告没有未完成 outbox 的疑似孤儿 route。
 *
 * <p>本任务扫超龄 route,回查对应 user 是否存在,只记录人工复核项。单凭年龄和一次查无
 * user 无法证明注册已经失败:分片写入可能阻塞后最终成功,自动删除会留下无法登录的 user。
 *
 * <p>⚠️ user 查询走分片键 {@code user_id}({@code selectBatchIds}),
 * ShardingSphere 按分片键路由不广播;**绝不能按 email 查分库 user**(见 UserMapper 注释)。
 */
@Slf4j
@Component
public class OrphanRouteCleaner {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int PAGE = 500;

    private final EmailRouteMapper emailRouteMapper;
    private final PhoneRouteMapper phoneRouteMapper;
    private final IdCardIdentityMapper idCardIdentityMapper;
    private final UserMapper userMapper;
    private final ReconcileLogMapper reconcileMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final ReserveXProperties props;
    private final RegistrationOutboxMapper registrationOutboxes;

    public OrphanRouteCleaner(EmailRouteMapper emailRouteMapper,
                              PhoneRouteMapper phoneRouteMapper,
                              IdCardIdentityMapper idCardIdentityMapper,
                              UserMapper userMapper,
                              ReconcileLogMapper reconcileMapper,
                              IdGenerator idGenerator,
                              TimeSupport time,
                              ReserveXProperties props) {
        this(emailRouteMapper, phoneRouteMapper, idCardIdentityMapper, userMapper,
                reconcileMapper, idGenerator, time, props, null);
    }

    @Autowired
    public OrphanRouteCleaner(EmailRouteMapper emailRouteMapper,
                              PhoneRouteMapper phoneRouteMapper,
                              IdCardIdentityMapper idCardIdentityMapper,
                              UserMapper userMapper,
                              ReconcileLogMapper reconcileMapper,
                              IdGenerator idGenerator,
                              TimeSupport time,
                              ReserveXProperties props,
                              RegistrationOutboxMapper registrationOutboxes) {
        this.emailRouteMapper = emailRouteMapper;
        this.phoneRouteMapper = phoneRouteMapper;
        this.idCardIdentityMapper = idCardIdentityMapper;
        this.userMapper = userMapper;
        this.reconcileMapper = reconcileMapper;
        this.idGenerator = idGenerator;
        this.time = time;
        this.props = props;
        this.registrationOutboxes = registrationOutboxes;
    }

    @Scheduled(cron = "${reservex.reconcile.crons.orphan-route:0 0 7 * * ?}")
    @Async("reconcileExecutor")
    public void clean() {
        LocalDateTime cutoff = time.now().minusMinutes(props.getReconcile().getOrphanRouteMinAgeMin());
        int suspected = scanEmail(cutoff) + scanPhone(cutoff) + scanIdentity(cutoff);
        if (suspected > 0) {
            recordLog(suspected);
            log.warn("检测到疑似孤儿 route,等待人工复核 count={} cutoff={}", suspected, cutoff);
        }
    }

    private int scanEmail(LocalDateTime cutoff) {
        int suspected = 0;
        LocalDateTime afterCreateAt = null;
        String afterEmail = null;
        while (true) {
            List<EmailRoute> candidates = emailRouteMapper.selectOrphansOlderThan(
                    cutoff, afterCreateAt, afterEmail, PAGE);
            if (candidates.isEmpty()) {
                break;
            }
            suspected += countMissingEmails(candidates);
            EmailRoute last = candidates.get(candidates.size() - 1);
            afterCreateAt = last.getCreateAt();
            afterEmail = last.getEmail();
            if (candidates.size() < PAGE) {
                break;
            }
        }
        return suspected;
    }

    private int scanPhone(LocalDateTime cutoff) {
        int suspected = 0;
        LocalDateTime afterCreateAt = null;
        String afterPhone = null;
        while (true) {
            List<PhoneRoute> candidates = phoneRouteMapper.selectOrphansOlderThan(
                    cutoff, afterCreateAt, afterPhone, PAGE);
            if (candidates.isEmpty()) {
                break;
            }
            suspected += countMissingPhones(candidates);
            PhoneRoute last = candidates.get(candidates.size() - 1);
            afterCreateAt = last.getCreateAt();
            afterPhone = last.getPhone();
            if (candidates.size() < PAGE) {
                break;
            }
        }
        return suspected;
    }

    private int scanIdentity(LocalDateTime cutoff) {
        int suspected = 0;
        LocalDateTime afterCreateAt = null;
        String afterHash = null;
        while (true) {
            List<IdCardIdentity> candidates = idCardIdentityMapper.selectOrphansOlderThan(
                    cutoff, afterCreateAt, afterHash, PAGE);
            if (candidates.isEmpty()) {
                break;
            }
            Set<Long> userIds = new HashSet<>();
            candidates.forEach(identity -> userIds.add(identity.getUserId()));
            Set<Long> missing = findMissingUsers(userIds);
            suspected += Math.toIntExact(candidates.stream()
                    .filter(identity -> missing.contains(identity.getUserId())).count());
            IdCardIdentity last = candidates.get(candidates.size() - 1);
            afterCreateAt = last.getCreateAt();
            afterHash = last.getIdCardHash();
            if (candidates.size() < PAGE) {
                break;
            }
        }
        return suspected;
    }

    private int countMissingEmails(List<EmailRoute> candidates) {
        Set<Long> userIds = new HashSet<>();
        for (EmailRoute r : candidates) {
            userIds.add(r.getUserId());
        }
        Set<Long> missing = findMissingUsers(userIds);
        int missingCount = 0;
        for (EmailRoute route : candidates) {
            if (missing.contains(route.getUserId())) {
                missingCount++;
            }
        }
        return missingCount;
    }

    private int countMissingPhones(List<PhoneRoute> candidates) {
        Set<Long> userIds = new HashSet<>();
        for (PhoneRoute r : candidates) {
            userIds.add(r.getUserId());
        }
        Set<Long> missing = findMissingUsers(userIds);
        int missingCount = 0;
        for (PhoneRoute route : candidates) {
            if (missing.contains(route.getUserId())) {
                missingCount++;
            }
        }
        return missingCount;
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
        if (registrationOutboxes != null) {
            missing.removeIf(id -> registrationOutboxes.existsUnfinished(id) > 0);
        }
        return missing;
    }

    private void recordLog(int suspected) {
        ReconcileLog log = new ReconcileLog();
        log.setId(idGenerator.nextId());
        log.setTaskType("orphan-route");
        log.setPeriod(time.now().format(PERIOD));
        // 全局任务无 slot 归属:slot_id=0 让 uk_task_period_slot 去重生效。
        log.setSlotId(0L);
        log.setRedisOccupied(null);
        log.setDbOccupied(null);
        log.setReservationCnt(null);
        log.setDiff(suspected);
        log.setFixAction("manual-review");
        log.setCreateAt(time.now());
        reconcileMapper.insertIgnore(log);
    }
}
