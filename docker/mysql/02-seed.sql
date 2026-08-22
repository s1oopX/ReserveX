-- ============================================================================
-- ReserveX 种子数据(08 §4.1)—— 只有两类,每类都可重复执行不出错
--
--   ① 超管账号:user 一行(落 ds1) + email_route/phone_route 各一行(落 single)
--   ② 场次模板:slot_template 四行(落 single),来自 yml 的 slot.seed.*
--   ③ 没有别的:不预置 slot(由生成任务按模板产出,预置会撞 uk_date_hour)、
--      不预置测试用户(由 09 §一 脚本走真实注册链路,才能验证跨库两写)
--
-- 幂等:全部 INSERT IGNORE,靠 PK / uk 挡重复。
-- ============================================================================

-- ============================================================================
-- 一、超管账号 —— 四个坑逐条钉死
-- ============================================================================
-- 坑 1:user_id 不能随机(seed 是 SQL,拿不到 Snowflake)。固定写 1,
--        而 1 mod 2 = 1 → **必须插进 reservex_ds1**。
--        ⚠️ 插错成 ds0 的后果:route 表里有 email→1 的映射,登录第 2 跳按 user_id=1
--           路由到 ds1 却查不到 → 报"超管不存在",现象像"孤儿 route",实际是 seed 插错库。
--           这是阶段 0 最容易卡住的一条。
-- 坑 2:password 不写 BCrypt 常量 —— 那等于把一个固定密码永久钉进 git。
--        这里只写哨兵值 '!'(BCrypt 串必以 $2 开头,故 '!' 不可能匹配任何输入),
--        应用启动时(bootstrap/AdminBootstrapRunner)检测到哨兵 → 用 ADMIN_INIT_PASSWORD
--        算 BCrypt 写入 + 记 audit_log(action='ADMIN_BOOTSTRAP')。
-- 坑 3:role='ADMIN' 只能由 seed/引导写。注册接口的 role 写死 'USER' ——
--        系统里第一个 ADMIN 必须由部署产生,不能由任何 HTTP 接口产生,否则接口即提权漏洞。
-- 坑 4:id_card_ciphertext / id_card_hash 是 NOT NULL,但超管没有真身份证。
--        填保留占位值,且该 hash **不进 id_card_route**(超管不预约)。
--        - hash 占位含非 hex 字符 → 与任何真实 SHA-256 十六进制串在结构上不可能相等,
--          不需要知道 pepper 也能保证不撞。
--        - key_id 填 'reserved',它**不在 reservex.aes.keys 里** → 万一有代码去解密超管的
--          身份证,会立刻抛"未知 key-id"而不是静默返回垃圾。这是刻意选的失败模式。
USE `reservex_ds1`;

INSERT IGNORE INTO `user`
  (`user_id`, `email`, `phone`, `password`,
   `id_card_ciphertext`, `id_card_key_id`, `id_card_hash`, `id_card_masked`,
   `role`, `status`, `create_at`, `update_at`)
VALUES
  (1,
   'admin@reservex.local',
   '00000000000',
   '!',                                                              -- 哨兵,见坑 2
   _binary 'ADMIN-RESERVED-NO-IDCARD',
   'reserved',
   RPAD('ADMIN-RESERVED-NO-IDCARD', 64, '0'),                        -- 由 RPAD 保证恰 64 字符
                                                                     --   (CHAR(64) 只补空格且取回时被裁掉,
                                                                     --    写短串会得到 24 字符,不是 64)
   'ADMIN-NO-IDCARD',
   'ADMIN',
   0,
   NOW(), NOW());

-- ---- 路由表(单库)。email_route 同时是登录第 1 跳的路由器,03 §2.2 --------
USE `reservex_single`;

INSERT IGNORE INTO `email_route` (`email`, `user_id`, `create_at`)
VALUES ('admin@reservex.local', 1, NOW());

INSERT IGNORE INTO `phone_route` (`phone`, `user_id`, `create_at`)
VALUES ('00000000000', 1, NOW());

INSERT IGNORE INTO `id_card_identity` (`id_card_hash`, `user_id`, `create_at`)
VALUES (RPAD('ADMIN-RESERVED-NO-IDCARD', 64, '0'), 1, NOW());

-- ⚠️ 刻意不写 id_card_route:超管不预约。写了反而会占掉一个真实身份证的配额位。

-- ============================================================================
-- 二、场次模板四行 —— yml 里 reservex.slot.seed.* 的**唯一使用点**
--     运行期一律读本表;代码里凡是在业务路径读 seed.* 的都是错的(03 §4.0)
--       template-hours: [9, 11, 14, 16]
--       default-duration-min: 120        → valid_until = slot_date + hour:00 + 120min
--       default-capacity: 50
--       default-bucket-count: 10         → 已放号禁改(改则 hash 路由错位)
--       default-release-offset-min: -840 → 前一日 10:00 放号(-1440 + 600)
--     template_id 固定 1~4:同 user_id,Snowflake 起始远大于此,永不冲突。
--     幂等靠 uk_hour:重跑时冲突即跳过该模板(不会造出第二套 9 点场)。
-- ============================================================================
INSERT IGNORE INTO `slot_template`
  (`template_id`, `slot_hour`, `duration_min`, `capacity`, `bucket_count`,
   `release_offset_min`, `enabled`, `create_at`, `update_at`, `version`)
VALUES
  (1,  9, 120, 50, 10, -840, 1, NOW(), NOW(), 0),
  (2, 11, 120, 50, 10, -840, 1, NOW(), NOW(), 0),
  (3, 14, 120, 50, 10, -840, 1, NOW(), NOW(), 0),
  (4, 16, 120, 50, 10, -840, 1, NOW(), NOW(), 0);
