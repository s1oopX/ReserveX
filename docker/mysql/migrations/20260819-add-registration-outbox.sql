USE `reservex_single`;

CREATE TABLE IF NOT EXISTS `registration_outbox` (
  `user_id`          BIGINT        NOT NULL,
  `registration_key` VARCHAR(128)  NULL,
  `email`            VARCHAR(128)  NOT NULL,
  `phone`            VARCHAR(32)   NOT NULL,
  `password`         VARCHAR(100)  NOT NULL,
  `id_card_ciphertext` VARBINARY(128) NOT NULL,
  `id_card_key_id`   VARCHAR(64)   NOT NULL,
  `id_card_hash`     CHAR(64)      NOT NULL,
  `id_card_masked`   VARCHAR(32)   NOT NULL,
  `role`             VARCHAR(16)   NOT NULL,
  `user_status`      TINYINT       NOT NULL,
  `user_version`     INT           NOT NULL,
  `user_must_change_password` TINYINT NOT NULL,
  `status`           TINYINT       NOT NULL DEFAULT 0,
  `attempts`         INT           NOT NULL DEFAULT 0,
  `next_attempt_at`  DATETIME      NOT NULL,
  `lease_until`      DATETIME      NULL,
  `lease_owner`      VARCHAR(64)   NULL,
  `last_error`       VARCHAR(1000) NULL,
  `operator_id`      BIGINT        NULL,
  `audit_id`         BIGINT        NULL,
  `create_at`        DATETIME      NOT NULL,
  `update_at`        DATETIME      NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_registration_request_key` (`registration_key`),
  KEY `idx_registration_outbox_due` (`status`, `next_attempt_at`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注册跨库写入补偿 outbox';

SET @has_registration_key := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = 'reservex_single' AND table_name = 'registration_outbox'
    AND column_name = 'registration_key'
);
SET @registration_key_ddl := IF(
  @has_registration_key > 0,
  'SELECT 1',
  'ALTER TABLE `registration_outbox` ADD COLUMN `registration_key` VARCHAR(128) NULL'
);
PREPARE registration_key_stmt FROM @registration_key_ddl;
EXECUTE registration_key_stmt;
DEALLOCATE PREPARE registration_key_stmt;

SET @has_registration_key_index := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = 'reservex_single' AND table_name = 'registration_outbox'
    AND index_name = 'uk_registration_request_key'
);
SET @registration_key_index_ddl := IF(
  @has_registration_key_index > 0,
  'SELECT 1',
  'ALTER TABLE `registration_outbox` ADD UNIQUE KEY `uk_registration_request_key` (`registration_key`)'
);
PREPARE registration_key_index_stmt FROM @registration_key_index_ddl;
EXECUTE registration_key_index_stmt;
DEALLOCATE PREPARE registration_key_index_stmt;
