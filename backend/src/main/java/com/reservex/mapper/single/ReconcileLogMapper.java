package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.ReconcileLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对账流水 Mapper(**单库**)。
 */
public interface ReconcileLogMapper extends BaseMapper<ReconcileLog> {

    /**
     * 记一次对账结果。**必须 INSERT IGNORE**,靠 {@code uk_task_period_slot} 挡重跑。
     *
     * <p>⚠️ 同一任务同周期跑两次(选主失效/人工补跑/调度抖动)时,
     * 没有这层保护会把同一份差异记两条 → 看板上"今日差异数"直接翻倍,
     * 运维会以为故障在扩大。
     *
     * @return 0 表示本周期已记录过
     */
    int insertIgnore(ReconcileLog log);

    /** 对账中心看板:只查每个任务/场次最新一轮仍未收敛的差异。 */
    List<ReconcileLog> selectWithDiff(@Param("taskType") String taskType,
                                      @Param("limit") Integer limit);

    /** 当前仍未收敛的任务/场次数；不把历史差异和已完成动作累计进去。 */
    long countCurrentWithDiff();

    /** 对账中心健康视图：即使 diff=0 也展示最近运行证据。 */
    List<ReconcileLog> selectLatest(@Param("limit") Integer limit);
}
