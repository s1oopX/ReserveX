package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.entity.Slot;
import com.reservex.entity.User;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.SlotMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

class ReminderWorkerTest {

    @Test
    void successfulSendPromotesShortLeaseToSentMarker() {
        LocalDate date = LocalDate.of(2026, 8, 16);
        LocalDateTime now = date.atTime(8, 45);
        LocalDateTime endOfDay = date.atTime(23, 59, 59);

        Reservation reservation = new Reservation();
        reservation.setReservationNo(10L);
        reservation.setUserId(20L);
        reservation.setSlotId(30L);
        reservation.setSlotDate(date);

        Slot slot = new Slot();
        slot.setSlotId(30L);
        slot.setSlotDate(date);
        slot.setSlotHour(9);

        User user = new User();
        user.setUserId(20L);
        user.setEmail("visitor@example.com");

        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectReminderCandidates(now, now.plusMinutes(30).plusDays(1)))
                .thenReturn(List.of(reservation));
        UserMapper users = mock(UserMapper.class);
        when(users.selectBatchIds(any())).thenReturn(List.of(user));
        SlotMapper slots = mock(SlotMapper.class);
        when(slots.selectById(30L)).thenReturn(slot);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(now);
        when(time.endOfDay(date)).thenReturn(endOfDay);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String key = "reminder:sent:2026-08-16:10";
        when(values.setIfAbsent(key, "sending", Duration.ofMinutes(1))).thenReturn(true);
        JavaMailSender mail = mock(JavaMailSender.class);

        new ReminderWorker(reservations, users, slots, time,
                new ReserveXProperties(), redis, mail, CircuitBreakerRegistry.ofDefaults()).scan();

        var order = inOrder(values, mail);
        order.verify(values).setIfAbsent(key, "sending", Duration.ofMinutes(1));
        order.verify(mail).send(any(SimpleMailMessage.class));
        order.verify(values).set(key, "sent", Duration.between(now, endOfDay));
    }
}
