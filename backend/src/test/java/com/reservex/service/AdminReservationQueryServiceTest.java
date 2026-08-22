package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.entity.Reservation;
import com.reservex.mapper.sharding.ReservationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminReservationQueryServiceTest {

    @Test
    void invalidStatusFailsBeforeQuery() {
        ReservationMapper reservations = mock(ReservationMapper.class);
        var service = new AdminReservationQueryService(reservations);

        BizException error = assertThrows(BizException.class,
                () -> service.list(null, null, "CONFIRMEDX", null, 100));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        verify(reservations, never()).selectList(any());
    }

    @Test
    void invalidCursorFailsBeforeQuery() {
        ReservationMapper reservations = mock(ReservationMapper.class);
        var service = new AdminReservationQueryService(reservations);

        BizException error = assertThrows(BizException.class,
                () -> service.list(null, null, null, "not-base64", 100));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        verify(reservations, never()).selectList(any());
    }

    @Test
    void nextPageUsesCreateTimeAndReservationNumberBoundary() {
        ReservationMapper reservations = mock(ReservationMapper.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 18, 12, 0);
        when(reservations.selectList(any())).thenReturn(List.of(
                reservation(103, createdAt), reservation(102, createdAt),
                reservation(101, createdAt.minusSeconds(1))), List.of(
                reservation(101, createdAt.minusSeconds(1)),
                reservation(100, createdAt.minusSeconds(2))));
        var service = new AdminReservationQueryService(reservations);

        var first = service.list(null, null, null, null, 2);
        var second = service.list(null, null, null, first.nextCursor(), 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Reservation>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reservations, org.mockito.Mockito.times(2)).selectList(captor.capture());
        var boundary = captor.getAllValues().get(1);
        assertEquals(List.of(103L, 102L), first.items().stream()
                .map(AdminReservationQueryService.ReservationView::reservationNo).toList());
        assertEquals(List.of(101L, 100L), second.items().stream()
                .map(AdminReservationQueryService.ReservationView::reservationNo).toList());
        org.assertj.core.api.Assertions.assertThat(boundary.getExpression().getNormal()).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(boundary.getExpression().getOrderBy()).hasSize(2);
    }

    @Test
    void resultReportsTruncation() {
        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectList(any())).thenReturn(IntStream.range(0, 501)
                .mapToObj(i -> reservation((long) i + 1,
                        LocalDateTime.of(2026, 8, 18, 12, 0).minusSeconds(i)))
                .toList());
        var page = new AdminReservationQueryService(reservations)
                .list(null, null, null, null, 100);

        assertEquals(100, page.items().size());
        assertEquals(true, page.hasMore());
        assertEquals(true, page.nextCursor() != null);
    }

    private static Reservation reservation(long rno, LocalDateTime createdAt) {
        Reservation row = new Reservation();
        row.setReservationNo(rno);
        row.setStatus(0);
        row.setCreateAt(createdAt);
        return row;
    }
}
