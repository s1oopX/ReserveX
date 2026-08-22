package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.RegistrationOutbox;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Claim/lease operations for registration recovery. */
public interface RegistrationOutboxMapper extends BaseMapper<RegistrationOutbox> {

    int claim(@Param("userId") long userId,
              @Param("staleBefore") LocalDateTime staleBefore,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("leaseOwner") String leaseOwner);

    List<Long> selectDueUserIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int complete(@Param("userId") long userId, @Param("leaseOwner") String leaseOwner,
                 @Param("now") LocalDateTime now);

    int deleteCompletedWithoutKey(@Param("userId") long userId,
                                  @Param("leaseOwner") String leaseOwner);

    int retry(@Param("userId") long userId, @Param("leaseOwner") String leaseOwner,
              @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
              @Param("lastError") String lastError, @Param("now") LocalDateTime now);

    int markStuck(@Param("userId") long userId, @Param("leaseOwner") String leaseOwner,
                  @Param("lastError") String lastError, @Param("now") LocalDateTime now);

    int retryStuck(@Param("userId") long userId, @Param("now") LocalDateTime now);

    int existsUnfinished(@Param("userId") long userId);

    RegistrationOutbox selectByRegistrationKey(@Param("registrationKey") String registrationKey);
}
