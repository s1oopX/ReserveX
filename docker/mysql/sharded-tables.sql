-- ============================================================================
-- ReserveX 分库表 DDL(03 §二 / §五)—— **只此一份**
--
-- ⚠️ 本文件不放进 /docker-entrypoint-initdb.d/,而是被 01-schema.sql 用 SOURCE 引两次:
--      USE reservex_ds0; SOURCE ...;
--      USE reservex_ds1; SOURCE ...;
--    理由:ds0/ds1 必须**逐字一致**。字段/索引/默认值有任何差异,按 user_id mod 2
--    落到不同库的同一业务在两库表现不同,而分片是随机的 → 现象是"部分用户功能异常",
--    这是最难查的一类缺陷。手抄两份必然在某次改字段时漏改一边。
--
-- ⚠️ 本文件内**不得出现 USE 语句**:库由调用方(01-schema.sql)选定。
-- ============================================================================

-- ---- user(分库,分片键 user_id)-------------------------------------------
-- 唯一约束不在此表(分库本地唯一索引失效),由单库 email_route / phone_route 承担
CREATE TABLE IF NOT EXISTS `user` (
  `user_id`            BIGINT         NOT NULL,                  -- Snowflake
  `email`              VARCHAR(128)   NOT NULL,
  `phone`              VARCHAR(32)    NOT NULL,
  `password`           VARCHAR(128)   NOT NULL,                  -- BCrypt(盐在 hash 串内,无需列)
  `id_card_ciphertext` VARBINARY(256) NOT NULL,                  -- AES-256-GCM,存 iv(12B)||ct||tag(16B),03 §2.1
  `id_card_key_id`     VARCHAR(16)    NOT NULL,                  -- 加密时的密钥版本;无此列则"支持轮换"是空承诺
  `id_card_hash`       CHAR(64)       NOT NULL,                  -- SHA-256(全局固定 pepper || 明文),非 per-row salt
  `id_card_masked`     VARCHAR(32)    NOT NULL,                  -- 脱敏展示;注册时从明文算好,不由密文派生
  `role`               VARCHAR(16)    NOT NULL DEFAULT 'USER',   -- 注册接口写死 USER;ADMIN 只能由 seed/引导产生
  `status`             TINYINT        NOT NULL DEFAULT 0,        -- 0 正常 1 封禁
  `create_at`          DATETIME       NOT NULL,
  `update_at`          DATETIME       NOT NULL,
  PRIMARY KEY (`user_id`),
  KEY `idx_hash` (`id_card_hash`),
  KEY `idx_email` (`email`)                                      -- 不给登录用(登录走 email_route 两跳),给运维排查用
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户(分库,user_id mod 2)';

-- ---- reservation(分库,与 user 绑定表,同 user_id 同库)---------------------
-- 不建 uk(id_card_hash, slot_date):分库本地唯一索引失效,由单库 id_card_route 兜底
CREATE TABLE IF NOT EXISTS `reservation` (
  `reservation_no` BIGINT      NOT NULL,                         -- Snowflake,亦作 QR 内 reservationNo
  `user_id`        BIGINT      NOT NULL,                         -- 分片键
  `slot_id`        BIGINT      NOT NULL,
  `slot_date`      DATE        NOT NULL,                         -- 冗余,对账/查询
  `bucket_no`      INT         NOT NULL,                         -- 命中桶号(取消/对账用)
  `id_card_hash`   CHAR(64)    NOT NULL,                         -- 冗余,关联 route
  `id_card_masked` VARCHAR(32) NOT NULL,                         -- 冗余,列表脱敏展示
  `status`         TINYINT     NOT NULL,                         -- 0 RESERVED 1 VERIFIED 2 CANCELLED 3 EXPIRED
  `valid_until`    DATETIME    NOT NULL,                         -- 超时扫 EXPIRED 用
  `verified_at`    DATETIME    NULL,
  `create_at`      DATETIME    NOT NULL,
  `update_at`      DATETIME    NOT NULL,
  `version`        INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`reservation_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_slot` (`slot_id`, `status`),
  KEY `idx_date_status` (`slot_date`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约(分库,与 user 绑定)';
