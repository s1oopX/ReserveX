package com.reservex.service;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.TimeSupport;
import com.reservex.entity.SlotTemplate;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.single.SlotTemplateMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotTemplateAdminServiceTest {

    @Test
    void staleVersionIsRejectedBeforeUpdate() {
        SlotTemplateMapper templates = mock(SlotTemplateMapper.class);
        SlotTemplate template = template(2);
        when(templates.selectById(9L)).thenReturn(template);
        SlotTemplateAdminService service = new SlotTemplateAdminService(
                templates, mock(IdGenerator.class), mock(TimeSupport.class));

        BizException error = assertThrows(BizException.class, () -> service.update(9L,
                new SlotTemplateAdminService.TemplatePatch(null, null, 200, null, null, null),
                HttpPreconditions.requireVersion("\"1\"")));

        assertEquals(ErrorCode.PRECONDITION_FAILED, error.getErrorCode());
        verify(templates, never()).casUpdate(any());
    }

    @Test
    void successfulUpdateReturnsTheNewVersion() {
        SlotTemplateMapper templates = mock(SlotTemplateMapper.class);
        SlotTemplate template = template(2);
        when(templates.selectById(9L)).thenReturn(template);
        when(templates.casUpdate(template)).thenReturn(1);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 18, 12, 0));
        SlotTemplateAdminService service = new SlotTemplateAdminService(
                templates, mock(IdGenerator.class), time);

        var updated = service.update(9L,
                new SlotTemplateAdminService.TemplatePatch(null, null, 200, null, null, null),
                HttpPreconditions.requireVersion("\"2\""));

        assertEquals(200, updated.capacity());
        assertEquals(3, updated.version());
    }

    private static SlotTemplate template(int version) {
        SlotTemplate template = new SlotTemplate();
        template.setTemplateId(9L);
        template.setSlotHour(9);
        template.setDurationMin(60);
        template.setCapacity(100);
        template.setBucketCount(10);
        template.setReleaseOffsetMin(-60);
        template.setEnabled(1);
        template.setVersion(version);
        return template;
    }
}
