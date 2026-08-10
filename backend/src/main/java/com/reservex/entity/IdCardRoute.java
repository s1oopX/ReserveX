package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一人一证一天一约的**第二道防线**(单库表)。第一道是抢号 Lua 里的 {@code SET NX dup}。
 *
 * <p><b>两道防线的分工</b>:Lua 那道快(不碰 DB)但活在 Redis 里 —— Redis 丢数据、
 * key 过期、或 dup 被 10.2a 回滚删掉,配额就穿了。本表的主键是**持久的结构性保证**:
 * 即使前一道全线失效,第二次落库也会撞主键。
 *
 * <p>⚠️ <b>主键 {@code (id_card_hash, slot_date)} 本身就是"一天一次"的语义。</b>
 * 这不是一个可调的业务参数 —— 把 {@code reservex.quota.daily-per-idcard} 配成 2,
 * 两道防线会**同时失效**(Lua 直接拒第二次;强行放开 dup 则必然撞本表主键 → 走 10.2a 回滚
 * → 用户看到"抢号成功后又失败")。故 {@code ConfigGuard} 断言它必须为 1(03 §3.1)。
 * v2 真要支持 N 次/天,主键要改成 {@code (hash, date, reservation_no)} 且 Lua 从
 * {@code SET NX} 改成 {@code INCR} 比对上限 —— 是改结构,不是改配置值。
 *
 * <p>⚠️ <b>复合主键,MyBatis-Plus 不支持复合 {@code @TableId}</b> → 本类**不标注**
 * {@code @TableId},靠 XML 的 resultMap 映射。后果:**不能用 {@code selectById} /
 * {@code updateById} / {@code deleteById}**,按主键的操作一律走自定义 XML 方法。
 *
 * <p>⚠️ <b>{@code id_card_hash} 用的是全局固定 pepper,不是 per-row salt</b> ——
 * 正因为要在这张表里跨用户比对,加随机盐会让同一证件的两个用户得到不同 hash,
 * 主键根本拦不住(03 §2.1)。这是本项目唯一"照密码学教科书做反而错"的地方。
 *
 * <p>⚠️ <b>超管不进本表</b>:seed 里超管的 hash 是保留占位值,他不预约,
 * 写进来反而会占掉一个真实证件的配额位(08 §4.1 坑 4)。
 */
@Data
@TableName("id_card_route")
public class IdCardRoute {

    /** {@code SHA-256(pepper || 明文)},64 位十六进制。明文永不入库。 */
    private String idCardHash;

    /** 本地日历日(北京时间)。用 UTC 存会让跨零点的同一本地日裂成两天,配额键直接错。 */
    private LocalDate slotDate;

    /** 占用该配额位的预约。冲突时靠它定位"是被哪一笔占了"。 */
    private Long reservationNo;

    private LocalDateTime createAt;
}
