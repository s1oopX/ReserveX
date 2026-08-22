package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.EmailRoute;
import com.reservex.entity.IdCardIdentity;
import com.reservex.entity.PhoneRoute;
import com.reservex.entity.ReconcileLog;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.EmailRouteMapper;
import com.reservex.mapper.single.IdCardIdentityMapper;
import com.reservex.mapper.single.PhoneRouteMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanRouteCleanerTest {

    @Test
    void missingUsersAreReportedButRoutesAreNotDeleted() {
        EmailRouteMapper emails = mock(EmailRouteMapper.class);
        PhoneRouteMapper phones = mock(PhoneRouteMapper.class);
        IdCardIdentityMapper identities = mock(IdCardIdentityMapper.class);
        UserMapper users = mock(UserMapper.class);
        ReconcileLogMapper logs = mock(ReconcileLogMapper.class);
        IdGenerator ids = mock(IdGenerator.class);
        TimeSupport time = mock(TimeSupport.class);
        ReserveXProperties props = new ReserveXProperties();
        LocalDateTime now = LocalDateTime.of(2026, 8, 17, 7, 0);

        EmailRoute email = new EmailRoute();
        email.setEmail("person@example.com");
        email.setUserId(11L);
        email.setCreateAt(now.minusHours(1));
        PhoneRoute phone = new PhoneRoute();
        phone.setPhone("13800138000");
        phone.setUserId(12L);
        phone.setCreateAt(now.minusHours(1));
        IdCardIdentity identity = new IdCardIdentity();
        identity.setIdCardHash("a".repeat(64));
        identity.setUserId(13L);
        identity.setCreateAt(now.minusHours(1));

        when(time.now()).thenReturn(now);
        when(ids.nextId()).thenReturn(1L);
        when(emails.selectOrphansOlderThan(any(), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(email));
        when(phones.selectOrphansOlderThan(any(), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(phone));
        when(identities.selectOrphansOlderThan(any(), isNull(), isNull(), eq(500)))
                .thenReturn(List.of(identity));
        when(users.selectBatchIds(any())).thenReturn(List.of());

        new OrphanRouteCleaner(emails, phones, identities, users, logs, ids, time, props).clean();

        verify(emails, never()).deleteByEmailAndUser(any(), any());
        verify(phones, never()).deleteByPhoneAndUser(any(), any());
        verify(identities, never()).deleteByHashAndUser(any(), any());
        ArgumentCaptor<ReconcileLog> captured = ArgumentCaptor.forClass(ReconcileLog.class);
        verify(logs).insertIgnore(captured.capture());
        assertEquals(3, captured.getValue().getDiff());
        assertEquals("manual-review", captured.getValue().getFixAction());
    }
}
