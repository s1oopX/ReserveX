package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.StuckReservation;
import com.reservex.entity.User;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.message.ReservationCreatedMessage;
import com.reservex.lua.LuaScripts;
import com.reservex.service.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** MQ 发送/消费窗口的第二通道：只补投，不因超时自主回滚。 */
@Slf4j
@Component
public class PendingScanner {

    private final StringRedisTemplate redis;
    private final RocketMQTemplate rocketMQ;
    private final UserMapper userMapper;
    private final StuckReservationMapper stuckMapper;
    private final ReserveXProperties props;
    private final TimeSupport time;
    private final LuaScripts lua;

    public PendingScanner(StringRedisTemplate redis,
                          RocketMQTemplate rocketMQ,
                          UserMapper userMapper,
                          StuckReservationMapper stuckMapper,
                          ReserveXProperties props,
                          TimeSupport time,
                          LuaScripts lua) {
        this.redis = redis;
        this.rocketMQ = rocketMQ;
        this.userMapper = userMapper;
        this.stuckMapper = stuckMapper;
        this.props = props;
        this.time = time;
        this.lua = lua;
    }

    @Scheduled(cron = "${reservex.pending.scan-cron}")
    public void scan() {
        long cutoff = time.now().atZone(time.zone()).toInstant().toEpochMilli()
                - props.getPending().getPersistTimeoutSec() * 1000L;
        Set<String> pending = redis.opsForZSet().rangeByScore(ReservationService.PENDING_KEY,
                0, cutoff, 0, props.getPending().getScanPageSize());
        if (pending == null) {
            return;
        }
        for (String raw : pending) {
            try {
                scanOne(raw);
            } catch (RuntimeException e) {
                log.error("扫描单条待落库预约失败 rno={}", raw, e);
            }
        }
    }

    private void scanOne(String raw) {
        long rno;
        try {
            rno = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            redis.opsForZSet().remove(ReservationService.PENDING_KEY, raw);
            return;
        }
        String occupyKey = ReservationService.occupyKey(rno);
        Map<Object, Object> occupy = redis.opsForHash().entries(occupyKey);
        if (occupy.isEmpty()) {
            redis.opsForZSet().remove(ReservationService.PENDING_KEY, raw);
            return;
        }
        // 兼容升级前带 TTL 的 occupy；恢复证据必须保留到成功消费或明确补偿。
        redis.persist(occupyKey);
        int count = intValue(occupy, "reinject_count", 0);
        long userId = longValue(occupy, "user_id");
        User user = userMapper.selectById(userId);
        if (user == null) {
            toStuck(rno, occupy, null, count, "用户不存在");
            return;
        }
        if (count >= props.getPending().getReinjectMax()) {
            toStuck(rno, occupy, user, count, "补投达到上限");
            return;
        }

        long slotId = longValue(occupy, "slot_id");
        int bucketNo = intValue(occupy, "bucket_no", -1);
        LocalDate slotDate = LocalDate.parse(value(occupy, "slot_date"));
        ReservationCreatedMessage message = new ReservationCreatedMessage(
                "rc-" + rno, rno, userId, slotId, slotDate.toString(),
                intValue(occupy, "slot_hour", 0), bucketNo, user.getIdCardHash(),
                value(occupy, "id_card_masked"), longValue(occupy, "valid_until"),
                longValue(occupy, "create_ts"), "scanner-" + rno,
                "dup:" + slotDate + ":" + user.getIdCardHash(),
                value(occupy, "bucket"), "slot:full:" + slotId);
        Long attempt = lua.evalLong(LuaScripts.Script.REINJECT, java.util.List.of(occupyKey));
        if (attempt == null || attempt == 0) {
            redis.opsForZSet().remove(ReservationService.PENDING_KEY, raw);
            return;
        }
        try {
            rocketMQ.syncSend("reservation-created", message);
            log.warn("补投预约消息 rno={} count={}", rno, attempt);
        } catch (RuntimeException e) {
            log.error("补投预约消息失败 rno={}", rno, e);
        }
    }

    private void toStuck(long rno, Map<Object, Object> occupy, User user,
                         int count, String error) {
        String occupyKey = ReservationService.occupyKey(rno);
        Boolean persisted = redis.persist(occupyKey);
        if (!Boolean.TRUE.equals(persisted) && !Boolean.TRUE.equals(redis.hasKey(occupyKey))) {
            throw new IllegalStateException("卡单 occupy 已丢失 rno=" + rno);
        }
        StuckReservation stuck = new StuckReservation();
        stuck.setReservationNo(rno);
        stuck.setSlotId(longValue(occupy, "slot_id"));
        stuck.setBucketKey(value(occupy, "bucket"));
        LocalDate slotDate = LocalDate.parse(value(occupy, "slot_date"));
        stuck.setDupKey("dup:" + slotDate + ":" + (user == null ? "unknown" : user.getIdCardHash()));
        stuck.setUserId(longValue(occupy, "user_id"));
        stuck.setIdCardHash(user == null ? "unknown" : user.getIdCardHash());
        stuck.setSlotDate(slotDate);
        stuck.setReinjectCount(count);
        stuck.setLastError(error);
        stuck.setStatus(0);
        stuck.setCreateAt(time.now());
        stuckMapper.insertIgnore(stuck);
        redis.opsForHash().put(occupyKey, "stuck", "1");
        redis.opsForZSet().remove(ReservationService.PENDING_KEY, Long.toString(rno));
        log.error("预约转卡单 rno={} reason={}", rno, error);
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("occupy 缺字段 " + key);
        }
        return value.toString();
    }

    private static long longValue(Map<Object, Object> values, String key) {
        return Long.parseLong(value(values, key));
    }

    private static int intValue(Map<Object, Object> values, String key, int fallback) {
        Object value = values.get(key);
        return value == null ? fallback : Integer.parseInt(value.toString());
    }
}
