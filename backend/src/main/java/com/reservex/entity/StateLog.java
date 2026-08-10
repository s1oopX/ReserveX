package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事务日志(单库表)。TCC 三防:**空回滚 / 悬挂 / 幂等**。
 *
 * <p>{@code xid = "rx-" + reservation_no}(03 §6.1)。派生而非随机:补偿路径拿到 rno
 * 就能算出 xid,不必先查表 —— 随机 xid 会让"回滚时找不到自己的日志行"成为可能。
 *
 * <p>⚠️ <b>本表曾经"有表无写入点"</b> —— 这是本项目反复出现的同一种病:
 * 决策存在,承载它的定义不存在。四个写入点现已定死(03 §6.1),缺任一个,
 * 对应的那类故障就没有凭据可查:
 * <ol>
 *   <li><b>消费者落库前</b>写 {@code status=1}(Try)。这行是"我准备落库"的痕迹;</li>
 *   <li><b>落库成功后</b>更新 {@code status=2}(Confirm);</li>
 *   <li><b>10.2a 回滚成功后</b>更新 {@code status=3}(Cancel);</li>
 *   <li><b>空回滚占位</b>:回滚先到、Try 还没写(消息乱序或消费者慢),
 *       此时直接插一行 {@code status=3}。<b>不插会导致悬挂</b> ——
 *       随后姗姗来迟的 Try 看不到任何回滚痕迹,会照常落库,
 *       造出一笔"已被回滚过却仍存在"的预约,而 Redis 余量已回补 → 超卖。</li>
 * </ol>
 *
 * <p>⚠️ {@code branch_id} 单分支设计({@code = reservation_no}),故 {@code uk_xid_branch}
 * 与主键 {@code xid} 目前等价。留 branch 列是为 v2 多分支预留,不是冗余。
 */
@Data
@TableName("state_log")
public class StateLog {

    /** {@code "rx-" + reservation_no}。派生规则见类注释。 */
    @TableId(type = IdType.INPUT)
    private String xid;

    /** {@code = reservation_no}(单分支)。 */
    private String branchId;

    /** 0 初始,1 Try,2 Confirm,3 Cancel。 */
    private Integer status;

    private LocalDateTime createAt;

    private LocalDateTime updateAt;
}
