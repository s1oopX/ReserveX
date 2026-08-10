-- ============================================================================
-- ReserveX 建库 + 建表(08 §4.1)—— 纯 DDL,可重复执行
--
-- 执行者:MySQL 容器的 docker-entrypoint-initdb.d(按文件名字典序:01 → 02)
-- ⚠️ initdb.d 只在数据目录为空时执行一次。改了 DDL 后重启 compose **不会重跑**,
--    现象是"我改了 DDL 但表还是老的"。开发期重置:docker compose down -v 再起。
-- ⚠️ 生产不该用 initdb 做 schema 管理(这条已在 08 §4.1 认下)。演进方向是 Flyway,
--    届时本文件变成 V1__init.sql。别把 initdb 说成"数据库版本管理方案"。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `reservex_ds0`    DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `reservex_ds1`    DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `reservex_single` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ============================================================================
-- 一、分库:ds0 / ds1 —— 结构完全相同,同一份 DDL 引两次
-- ============================================================================
USE `reservex_ds0`;
SOURCE /opt/reservex/sharded-tables.sql;

USE `reservex_ds1`;
SOURCE /opt/reservex/sharded-tables.sql;

-- ============================================================================
-- 二、单库:reservex_single —— 13 张表(03 §三/§四/§六/§七)
--     不用广播表:单库表走独立 DataSource + singleTxManager(03 §1.1)
-- ============================================================================
USE `reservex_single`;

-- ---- 2.1 全局路由表(分库下唯一约束失效,由这里承担)------------------------
-- 第二重价值:同时充当登录的**分片路由器**(email → user_id → 分片键),03 §2.2
CREATE TABLE IF NOT EXISTS `email_route` (
  `email`     VARCHAR(128) NOT NULL,
  `user_id`   BIGINT       NOT NULL,
  `create_at` DATETIME     NOT NULL,
  PRIMARY KEY (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='邮箱唯一 + 登录路由';

CREATE TABLE IF NOT EXISTS `phone_route` (
  `phone`     VARCHAR(32) NOT NULL,
  `user_id`   BIGINT      NOT NULL,
  `create_at` DATETIME    NOT NULL,
  PRIMARY KEY (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手机号唯一 + 路由';

-- 一人一天配额去重(第二道防线;第一道是 Lua 的 SET NX dup)
-- PK 结构上即"一天一次" → daily-per-idcard 只能是 1,启动断言,03 §3.1
CREATE TABLE IF NOT EXISTS `id_card_route` (
  `id_card_hash`   CHAR(64) NOT NULL,                            -- 明文不入库
  `slot_date`      DATE     NOT NULL,
  `reservation_no` BIGINT   NOT NULL,
  `create_at`      DATETIME NOT NULL,
  PRIMARY KEY (`id_card_hash`, `slot_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一人一证一天一约(第二道防线)';

-- ---- 2.2 号源(03 §四)-----------------------------------------------------
-- 场次模板:次日 slot 生成任务的唯一数据来源。yml 的 slot.seed.* 只用于灌本表种子,
-- 运行期一律读本表,不读 yml(否则改容量要重启、且无法按时段差异化),03 §4.0
CREATE TABLE IF NOT EXISTS `slot_template` (
  `template_id`        BIGINT   NOT NULL,                        -- Snowflake
  `slot_hour`          INT      NOT NULL,                        -- 时段起始小时(9/11/14/16)
  `duration_min`       INT      NOT NULL DEFAULT 120,
  `capacity`           INT      NOT NULL DEFAULT 50,
  `bucket_count`       INT      NOT NULL DEFAULT 10,
  `release_offset_min` INT      NOT NULL DEFAULT -1440,          -- 相对 slot_date 00:00 的分钟偏移
                                                                 --   存偏移不存时刻:模板不绑定具体日期
  `enabled`            TINYINT  NOT NULL DEFAULT 1,              -- 0 停用(生成任务跳过,不删行:历史 slot 仍引用)
  `create_at`          DATETIME NOT NULL,
  `update_at`          DATETIME NOT NULL,
  `version`            INT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`template_id`),
  UNIQUE KEY `uk_hour` (`slot_hour`)                             -- v1 一个 hour 一个模板
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场次模板';

CREATE TABLE IF NOT EXISTS `slot` (
  `slot_id`      BIGINT   NOT NULL,
  `template_id`  BIGINT   NULL,                                  -- 来源模板(手工建的为 NULL);只做溯源不做外键
  `slot_date`    DATE     NOT NULL,
  `slot_hour`    INT      NOT NULL,
  `valid_until`  DATETIME NOT NULL,                              -- = slot_date + slot_hour:00 + duration_min,落库固化
  `duration_min` INT      NOT NULL DEFAULT 120,
  `capacity`     INT      NOT NULL DEFAULT 50,
  `bucket_count` INT      NOT NULL DEFAULT 10,                   -- 已放号禁改(改则 hash 路由错位)
  `released`     TINYINT  NOT NULL DEFAULT 0,                    -- 0 未放 1 已放
  `release_at`   DATETIME NOT NULL,
  `version`      INT      NOT NULL DEFAULT 0,                    -- 乐观锁
  PRIMARY KEY (`slot_id`),
  UNIQUE KEY `uk_date_hour` (`slot_date`, `slot_hour`)           -- 挡"次日 slot 生成"任务重跑造重复场次
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场次';

CREATE TABLE IF NOT EXISTS `slot_bucket` (
  `slot_id`   BIGINT NOT NULL,
  `bucket_no` INT    NOT NULL,                                   -- 0 ~ bucket_count-1
  `total`     INT    NOT NULL,                                   -- 余数分摊:前 rem 个桶各多 1(03 §4.2)
  `occupied`  INT    NOT NULL DEFAULT 0,                         -- 累计成功预约数(取消/过期不返还,故不减回)
  PRIMARY KEY (`slot_id`, `bucket_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场次分桶库存(DB 侧账)';

-- ---- 2.3 状态机事务日志 + 事件流水(03 §六)--------------------------------
-- TCC 三防:空回滚/悬挂/幂等。xid = 'rx-' + reservation_no
CREATE TABLE IF NOT EXISTS `state_log` (
  `xid`       VARCHAR(64) NOT NULL,
  `branch_id` VARCHAR(64) NOT NULL,                              -- = reservation_no(单分支)
  `status`    TINYINT     NOT NULL,                              -- 0 初始 1 Try 2 Confirm 3 Cancel
  `create_at` DATETIME    NOT NULL,
  `update_at` DATETIME    NOT NULL,
  PRIMARY KEY (`xid`),
  UNIQUE KEY `uk_xid_branch` (`xid`, `branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事务日志(四个写入点见 03 §6.1)';

-- 不可变事件流水(业务审计)
CREATE TABLE IF NOT EXISTS `reservation_event` (
  `event_id`       BIGINT      NOT NULL,
  `reservation_no` BIGINT      NOT NULL,
  `event_type`     VARCHAR(32) NOT NULL,                         -- CREATED/VERIFIED/CANCELLED/EXPIRED
  `from_status`    TINYINT     NULL,
  `to_status`      TINYINT     NOT NULL,
  `operator_type`  VARCHAR(16) NOT NULL,                         -- USER/STAFF/ADMIN/SYSTEM
  `operator_id`    BIGINT      NULL,
  `request_id`     VARCHAR(64) NOT NULL,
  `event_time`     DATETIME    NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_res` (`reservation_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约事件流水(不可变)';

-- ---- 2.4 消费幂等 + 对账 + 核销流水(03 §七)-------------------------------
-- ⚠️ consumer_group 是主键前缀:改组名 = 幂等历史全失效,存量消息会被重复消费。视为不可变常量
CREATE TABLE IF NOT EXISTS `consumed_event` (
  `consumer_group` VARCHAR(64) NOT NULL,
  `event_id`       VARCHAR(64) NOT NULL,
  `consumed_at`    DATETIME    NOT NULL,
  PRIMARY KEY (`consumer_group`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消费幂等';

CREATE TABLE IF NOT EXISTS `reconcile_log` (
  `id`              BIGINT      NOT NULL,
  `task_type`       VARCHAR(32) NOT NULL,                        -- stock/routeA/routeB/...
  `period`          VARCHAR(16) NOT NULL,                        -- 对账周期键(slot_date 或 slot_id+hour)
  `slot_id`         BIGINT      NOT NULL,
  `redis_occupied`  INT         NOT NULL,                        -- capacity - Σ Redis 桶余量
  `db_occupied`     INT         NOT NULL,                        -- Σ slot_bucket.occupied
  `reservation_cnt` INT         NOT NULL,                        -- 有效预约(RESERVED+VERIFIED)
  `diff`            INT         NOT NULL,
  `fix_action`      VARCHAR(64) NULL,
  `create_at`       DATETIME    NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_period_slot` (`task_type`, `period`, `slot_id`)  -- 对账任务自身幂等,挡重跑翻倍
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账流水';

-- qr_nonce 可 NULL:手工核销无 nonce;失败/重放的真实 nonce 记 attempt_nonce(非唯一),
-- 否则重放的第二次连日志都撞 uk 记不下来
CREATE TABLE IF NOT EXISTS `verification_log` (
  `verify_id`      BIGINT      NOT NULL,
  `reservation_no` BIGINT      NOT NULL,
  `staff_id`       BIGINT      NOT NULL,
  `method`         TINYINT     NOT NULL,                         -- 0 扫码 1 手工
  `qr_nonce`       VARCHAR(64) NULL,                             -- 仅 result=0 时填
  `attempt_nonce`  VARCHAR(64) NULL,
  `result`         TINYINT     NOT NULL,                         -- 0成功 1重复 2已取消 3已过期 4无效码
  `verify_time`    DATETIME    NOT NULL,
  PRIMARY KEY (`verify_id`),
  UNIQUE KEY `uk_qr_nonce` (`qr_nonce`),                         -- MySQL uk 允许多 NULL,唯一性仍成立
  KEY `idx_res` (`reservation_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核销流水(第二道防重复,第一道是 CAS)';

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`            BIGINT      NOT NULL,
  `operator_type` VARCHAR(16) NOT NULL,
  `operator_id`   BIGINT      NULL,
  `action`        VARCHAR(64) NOT NULL,                          -- ADMIN_BOOTSTRAP/CREATE_STAFF/DECRYPT_IDCARD/...
  `target_type`   VARCHAR(32) NULL,
  `target_id`     BIGINT      NULL,
  `before`        TEXT        NULL,                              -- JSON 摘要
  `after`         TEXT        NULL,
  `request_id`    VARCHAR(64) NOT NULL,
  `create_at`     DATETIME    NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计';

-- 卡单表(scanner 补投耗尽转人工),02 §3.2
-- 存在理由:回滚所需参数(bucket_key/dup_key)原本只在 Redis occupy 里,
--          occupy TTL 过期后人工连"该回滚什么"都查不到 → 桶余量永久泄漏
CREATE TABLE IF NOT EXISTS `stuck_reservation` (
  `reservation_no` BIGINT       NOT NULL,                        -- 天然幂等:同一 rno 只一条
  `slot_id`        BIGINT       NOT NULL,
  `bucket_key`     VARCHAR(64)  NOT NULL,                        -- 完整桶 key,如 slot:101:b:3(10.2a INCR 用)
  `dup_key`        VARCHAR(128) NOT NULL,                        -- dup:{slot_date}:{id_card_hash}(10.2a DEL 用)
  `user_id`        BIGINT       NOT NULL,
  `id_card_hash`   CHAR(64)     NOT NULL,
  `slot_date`      DATE         NOT NULL,
  `reinject_count` INT          NOT NULL,                        -- 达 reinject-max 才入表
  `last_error`     VARCHAR(512) NULL,
  `status`         TINYINT      NOT NULL DEFAULT 0,              -- 0待研判 1已重投成功 2已回滚 3已忽略
  `create_at`      DATETIME     NOT NULL,
  `resolve_at`     DATETIME     NULL,
  `resolver_id`    BIGINT       NULL,
  PRIMARY KEY (`reservation_no`),
  KEY `idx_status` (`status`, `create_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡单(补投耗尽转人工)';
