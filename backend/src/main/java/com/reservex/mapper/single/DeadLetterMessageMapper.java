package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.DeadLetterMessage;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DeadLetterMessageMapper extends BaseMapper<DeadLetterMessage> {

    int insertIgnore(DeadLetterMessage message);

    List<DeadLetterMessage> selectRecent(@Param("limit") int limit);

    int claimReplay(@Param("messageId") String messageId,
                    @Param("staleBefore") LocalDateTime staleBefore,
                    @Param("now") LocalDateTime now,
                    @Param("resolverId") long resolverId);

    int completeReplay(@Param("messageId") String messageId,
                       @Param("now") LocalDateTime now,
                       @Param("resolverId") long resolverId);

    int releaseReplay(@Param("messageId") String messageId);
}
