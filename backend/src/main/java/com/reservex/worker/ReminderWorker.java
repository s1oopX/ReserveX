package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.entity.Slot;
import com.reservex.entity.User;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.SlotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 邮件提醒(04 §四 / 06 §四)。
 *
 * <p>在预约场次生效前 {@code ahead-min}(默认 30min)窗口内,向 RESERVED 用户发提醒邮件。
 * Redis 短租约挡并发发送,SMTP 成功后再把同一 key 提升为当天有效的 sent 标记。
 *
 * <p>⚠️ <b>现在由应用层算窗口,不用 SQL 的 NOW()}</b>(见 ReservationMapper 注释:
 * 容器时区漏配成 UTC 时 NOW() 早 8h,会把还没到提醒时间的预约全发提醒)。now 取 {@link TimeSupport}。
 *
 * <p>⚠️ <b>SMTP 密码是 placeholder,演示环境发不出邮件。</b>{@code JavaMailSender} Bean
 * 在空密码下正常创建(发邮件时才连邮箱),send 抛 {@link MailAuthenticationException}
 * 时降级 {@code log.warn}("演示环境邮件不可用"),不拖垮定时任务。
 * {@code management.health.mail.enabled=false} 已把 health 排除,避免 535 拽垮健康总览。
 *
 * <p>⚠️ <b>取邮箱走分片键 user_id 两跳</b>:reservation 表广播查到 user_id 集合,
 * 再 {@code selectBatchIds(userIds)} 按分片键路由取 user 行(**不广播**)。
 * 绝不能按 email 查分库 user(见 UserMapper 注释)。
 */
@Slf4j
@Component
public class ReminderWorker {

    private static final String REMINDER_SENT_KEY_PREFIX = "reminder:sent:";
    private static final String REMINDER_SENDING_VALUE = "sending";
    private static final String REMINDER_SENT_VALUE = "sent";
    private static final Duration REMINDER_LEASE = Duration.ofMinutes(1);

    private final ReservationMapper reservationMapper;
    private final UserMapper userMapper;
    private final SlotMapper slotMapper;
    private final TimeSupport time;
    private final ReserveXProperties props;
    private final StringRedisTemplate redis;
    private final JavaMailSender mailSender;
    private final String mailFrom;

    public ReminderWorker(ReservationMapper reservationMapper,
                           UserMapper userMapper,
                           SlotMapper slotMapper,
                           TimeSupport time,
                           ReserveXProperties props,
                           StringRedisTemplate redis,
                           @Autowired(required = false) JavaMailSender mailSender) {
        this.reservationMapper = reservationMapper;
        this.userMapper = userMapper;
        this.slotMapper = slotMapper;
        this.time = time;
        this.props = props;
        this.redis = redis;
        this.mailSender = mailSender;
        this.mailFrom = "reservex@qq.com";
    }

    @Scheduled(cron = "${reservex.reconcile.crons.reminder:0 */5 * * * ?}")
    @Async("mailExecutor")
    public void scan() {
        if (mailSender == null) {
            log.warn("JavaMailSender 未注入,跳过提醒邮件发送(演示环境正常现象)");
            return;
        }
        LocalDateTime now = time.now();
        int aheadMin = Math.min(props.getReminder().getAheadMin(), 30);
        LocalDateTime from = now;
        LocalDateTime to = now.plusMinutes(aheadMin);
        // valid_until 是结束时间，不是开场时间；放宽一日后再按 slot 开始时间精筛，
        // 避免 09:00 场次在 10:30 才被提醒。场次按日生成，24h 是本任务的边界。
        List<Reservation> candidates = reservationMapper.selectReminderCandidates(from, to.plusDays(1));
        if (candidates.isEmpty()) {
            return;
        }

        // 取邮箱:按分片键 user_id 批量查,不广播。
        Set<Long> userIds = candidates.stream()
                .map(Reservation::getUserId)
                .collect(Collectors.toSet());
        List<User> users = userMapper.selectBatchIds(userIds);
        java.util.Map<Long, String> emailByUserId = new java.util.HashMap<>();
        for (User u : users) {
            emailByUserId.put(u.getUserId(), u.getEmail());
        }

        int sent = 0;
        int skipped = 0;
        for (Reservation r : candidates) {
            Slot slot = slotMapper.selectById(r.getSlotId());
            if (slot == null) {
                continue;
            }
            LocalDateTime startsAt = slot.getSlotDate().atTime(slot.getSlotHour(), 0);
            if (startsAt.isBefore(from) || startsAt.isAfter(to)) {
                continue;
            }
            String sentKey = REMINDER_SENT_KEY_PREFIX + r.getSlotDate() + ":" + r.getReservationNo();
            // 短租约挡多实例并发；进程在 SMTP 前崩溃时，租约过期后仍能重试。
            Duration ttl = Duration.between(now, time.endOfDay(r.getSlotDate()));
            long ttlSec = Math.max(1L, ttl.getSeconds());
            Boolean ok = redis.opsForValue().setIfAbsent(
                    sentKey, REMINDER_SENDING_VALUE, REMINDER_LEASE);
            if (!Boolean.TRUE.equals(ok)) {
                skipped++;
                continue;
            }
            String email = emailByUserId.get(r.getUserId());
            if (email == null) {
                log.warn("提醒候选 user 不存在 rno={} userId={} (孤儿 user,route 未清理?)",
                        r.getReservationNo(), r.getUserId());
                redis.delete(sentKey);
                continue;
            }
            if (sendReminder(r, email)) {
                redis.opsForValue().set(
                        sentKey, REMINDER_SENT_VALUE, Duration.ofSeconds(ttlSec));
                sent++;
            } else {
                // 发送失败立即释放租约，不等下一次过期。
                redis.delete(sentKey);
            }
        }
        if (sent > 0 || skipped > 0) {
            log.info("提醒邮件扫描完成 sent={} skipped(已发/发送中)={} window=[{},{}] candidates={}",
                    sent, skipped, from, to, candidates.size());
        }
    }

    private boolean sendReminder(Reservation r, String email) {
        Slot slot = slotMapper.selectById(r.getSlotId());
        int slotHour = slot != null ? slot.getSlotHour() : 0;
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(mailFrom);
        msg.setTo(email);
        msg.setSubject("您的湿地预约提醒:" + r.getSlotDate() + " " + slotHour + ":00");
        msg.setText(buildBody(r, slotHour));
        try {
            mailSender.send(msg);
            return true;
        } catch (MailAuthenticationException e) {
            // 演示环境 SMTP 密码是 placeholder,发不出邮件属预期。
            log.warn("演示环境邮件不可用(SMTP 认证失败),已跳过发送 rno={}",
                    r.getReservationNo());
            return false;
        } catch (RuntimeException e) {
            log.error("提醒邮件发送失败 rno={} errorType={}", r.getReservationNo(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private String buildBody(Reservation r, int slotHour) {
        return new StringBuilder()
                .append("尊敬的访客您好:\n\n")
                .append("您预约的湿地场次即将开始,请准时入园。\n\n")
                .append("预约编号:").append(r.getReservationNo()).append("\n")
                .append("场次日期:").append(r.getSlotDate()).append("\n")
                .append("场次时段:").append(slotHour).append(":00\n")
                .append("有效期至:").append(r.getValidUntil()).append("\n\n")
                .append("本邮件由系统自动发送,请勿回复。")
                .toString();
    }
}
