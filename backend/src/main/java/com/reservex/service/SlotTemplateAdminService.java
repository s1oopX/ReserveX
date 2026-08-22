package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.TimeSupport;
import com.reservex.entity.SlotTemplate;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.single.SlotTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** CRUD and validation for reusable slot templates. */
@Service
@RequiredArgsConstructor
public class SlotTemplateAdminService {

    private static final int MAX_CAPACITY = 100_000;
    private static final int MAX_BUCKETS = 1_000;

    private final SlotTemplateMapper templateMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;

    public List<TemplateView> list() {
        return templateMapper.selectList(new LambdaQueryWrapper<SlotTemplate>()
                        .orderByAsc(SlotTemplate::getSlotHour))
                .stream().map(TemplateView::from).toList();
    }

    public TemplateView get(long templateId) {
        SlotTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return TemplateView.from(template);
    }

    public TemplateView create(TemplateInput input) {
        validate(input.slotHour(), input.durationMin(), input.capacity(),
                input.bucketCount(), input.releaseOffsetMin());
        LocalDateTime now = time.now();
        SlotTemplate template = new SlotTemplate();
        template.setTemplateId(idGenerator.nextId());
        apply(template, input);
        template.setEnabled(input.enabled() ? 1 : 0);
        template.setCreateAt(now);
        template.setUpdateAt(now);
        template.setVersion(0);
        try {
            templateMapper.insert(template);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.TEMPLATE_INVALID, "该时段已有模板");
        }
        return TemplateView.from(template);
    }

    public TemplateView update(long templateId, TemplatePatch patch,
                               HttpPreconditions.VersionCondition condition) {
        SlotTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!condition.matches(template.getVersion())) {
            throw BizException.of(ErrorCode.PRECONDITION_FAILED);
        }
        TemplateInput merged = new TemplateInput(
                patch.slotHour() == null ? template.getSlotHour() : patch.slotHour(),
                patch.durationMin() == null ? template.getDurationMin() : patch.durationMin(),
                patch.capacity() == null ? template.getCapacity() : patch.capacity(),
                patch.bucketCount() == null ? template.getBucketCount() : patch.bucketCount(),
                patch.releaseOffsetMin() == null ? template.getReleaseOffsetMin() : patch.releaseOffsetMin(),
                patch.enabled() == null ? template.getEnabled() == 1 : patch.enabled());
        validate(merged.slotHour(), merged.durationMin(), merged.capacity(),
                merged.bucketCount(), merged.releaseOffsetMin());
        if (!Objects.equals(merged.slotHour(), template.getSlotHour())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板时段不可修改，请停用后新建");
        }
        apply(template, merged);
        template.setEnabled(merged.enabled() ? 1 : 0);
        template.setUpdateAt(time.now());
        if (templateMapper.casUpdate(template) != 1) {
            throw BizException.of(ErrorCode.PRECONDITION_FAILED);
        }
        template.setVersion(template.getVersion() + 1);
        return TemplateView.from(template);
    }

    static void validate(Integer hour, Integer duration, Integer capacity,
                         Integer buckets, Integer releaseOffset) {
        if (hour == null || hour < 0 || hour > 23 || duration == null || duration <= 0
                || hour * 60 + duration > 24 * 60
                || capacity == null || capacity <= 0 || buckets == null || buckets <= 0
                || capacity < buckets || capacity > MAX_CAPACITY
                || buckets > MAX_BUCKETS
                || releaseOffset == null || releaseOffset < -24 * 60
                || releaseOffset >= hour * 60) {
            throw BizException.of(ErrorCode.TEMPLATE_INVALID);
        }
    }

    private static void apply(SlotTemplate target, TemplateInput input) {
        target.setSlotHour(input.slotHour());
        target.setDurationMin(input.durationMin());
        target.setCapacity(input.capacity());
        target.setBucketCount(input.bucketCount());
        target.setReleaseOffsetMin(input.releaseOffsetMin());
    }

    public record TemplateInput(Integer slotHour, Integer durationMin, Integer capacity,
                                Integer bucketCount, Integer releaseOffsetMin, boolean enabled) {
    }

    public record TemplatePatch(Integer slotHour, Integer durationMin, Integer capacity,
                                Integer bucketCount, Integer releaseOffsetMin, Boolean enabled) {
    }

    public record TemplateView(Long templateId, Integer slotHour, Integer durationMin,
                               Integer capacity, Integer bucketCount, Integer releaseOffsetMin,
                               boolean enabled, Integer version) {
        static TemplateView from(SlotTemplate template) {
            return new TemplateView(template.getTemplateId(), template.getSlotHour(),
                    template.getDurationMin(), template.getCapacity(), template.getBucketCount(),
                    template.getReleaseOffsetMin(), template.getEnabled() == 1,
                    template.getVersion());
        }
    }
}
