package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.StateLog;
import org.apache.ibatis.annotations.Param;

/**
 * 事务日志 Mapper(**单库**)。状态推进与人工回滚仲裁全部在这里。
 *
 * <p>⚠️ <b>写入时机的约定不能随意改</b>:Try 在消费者**落库前**写(而非抢号热路径),
 * 正因如此窗口期取消先到时 DB 里没有这行 → 必须用下面的 {@link #insertOrCancel}
 * 写空回滚占位,不能简单 INSERT。
 */
public interface StateLogMapper extends BaseMapper<StateLog> {

    /**
     * Try(消费者落库前)。PK 冲突说明已 Try 过(消息重投),幂等跳过。
     *
     * @return 0 表示已存在,调用方应继续往下走(检查 status)
     */
    int insertTry(@Param("xid") String xid, @Param("branchId") String branchId);

    /**
     * Confirm(核销 CAS 成功后)。
     *
     * @return 0 表示已 Confirm 或已 Cancel(幂等)
     */
    int confirm(@Param("xid") String xid);

    /**
     * Cancel(取消/超时/10.2a 回滚)。
     *
     * @return 0 幂等
     */
    int cancel(@Param("xid") String xid);

    /**
     * 空回滚占位:回滚先到、Try 还没写(消息乱序)时用。
     *
     * <p>实现:{@code INSERT INTO state_log … ON DUPLICATE KEY UPDATE status=3}。
     * 随后姗姗来迟的 Try 看到 status=3,必须**把本次落库转为 CANCELLED** ——
     * 否则会造出一笔"已被回滚却仍存在"的预约而 Redis 余量已回补 → 超卖。
     */
    int insertOrCancel(@Param("xid") String xid, @Param("branchId") String branchId);

    /** 回滚 Lua 已完成后的专用收口,只允许 status=4→3。 */
    int completeRollback(@Param("xid") String xid);

    /** 人工已 claim stuck 后接管既有 Cancel/Expire 占位。 */
    int promoteRollbackClaim(@Param("xid") String xid);

    /** 人工回滚抢占。status=4 只表示补偿处理中，已有 Try/Confirm/Cancel 时不得覆盖。 */
    int insertRollbackClaim(@Param("xid") String xid, @Param("branchId") String branchId);
}
