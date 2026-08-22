-- Immutable existing-volume migration. Keep this DDL here; do not SOURCE the
-- mutable current sharded-tables.sql or a future edit would rewrite history.
USE `reservex_ds0`;
CREATE TABLE IF NOT EXISTS `reservation_transition_outbox` (
  `transition_id`  VARCHAR(64) NOT NULL,
  `user_id`        BIGINT      NOT NULL,
  `reservation_no` BIGINT      NOT NULL,
  `event_type`     VARCHAR(32) NOT NULL,
  `operator_type`  VARCHAR(16) NOT NULL,
  `operator_id`    BIGINT      NULL,
  `method`         TINYINT     NULL,
  `qr_nonce`       VARCHAR(64) NULL,
  `manual`         TINYINT     NOT NULL DEFAULT 0,
  `verification_id` BIGINT     NULL,
  `audit_id`       BIGINT      NULL,
  `request_id`     VARCHAR(64) NOT NULL,
  `event_time`     DATETIME    NOT NULL,
  `create_at`      DATETIME    NOT NULL,
  PRIMARY KEY (`transition_id`),
  KEY `idx_outbox_user` (`user_id`, `create_at`),
  UNIQUE KEY `uk_outbox_reservation` (`reservation_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约终态流水 outbox';

USE `reservex_ds1`;
CREATE TABLE IF NOT EXISTS `reservation_transition_outbox` (
  `transition_id`  VARCHAR(64) NOT NULL,
  `user_id`        BIGINT      NOT NULL,
  `reservation_no` BIGINT      NOT NULL,
  `event_type`     VARCHAR(32) NOT NULL,
  `operator_type`  VARCHAR(16) NOT NULL,
  `operator_id`    BIGINT      NULL,
  `method`         TINYINT     NULL,
  `qr_nonce`       VARCHAR(64) NULL,
  `manual`         TINYINT     NOT NULL DEFAULT 0,
  `verification_id` BIGINT     NULL,
  `audit_id`       BIGINT      NULL,
  `request_id`     VARCHAR(64) NOT NULL,
  `event_time`     DATETIME    NOT NULL,
  `create_at`      DATETIME    NOT NULL,
  PRIMARY KEY (`transition_id`),
  KEY `idx_outbox_user` (`user_id`, `create_at`),
  UNIQUE KEY `uk_outbox_reservation` (`reservation_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约终态流水 outbox';
