USE `reservex_single`;

SET @has_request_fingerprint := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = 'reservex_single' AND table_name = 'registration_outbox'
    AND column_name = 'request_fingerprint'
);
SET @request_fingerprint_ddl := IF(
  @has_request_fingerprint > 0,
  'SELECT 1',
  'ALTER TABLE `registration_outbox` ADD COLUMN `request_fingerprint` VARCHAR(100) NULL AFTER `registration_key`'
);
PREPARE request_fingerprint_stmt FROM @request_fingerprint_ddl;
EXECUTE request_fingerprint_stmt;
DEALLOCATE PREPARE request_fingerprint_stmt;

ALTER TABLE `registration_outbox`
  MODIFY COLUMN `email` VARCHAR(128) NULL,
  MODIFY COLUMN `phone` VARCHAR(32) NULL,
  MODIFY COLUMN `password` VARCHAR(100) NULL,
  MODIFY COLUMN `id_card_ciphertext` VARBINARY(128) NULL,
  MODIFY COLUMN `id_card_key_id` VARCHAR(64) NULL,
  MODIFY COLUMN `id_card_hash` CHAR(64) NULL,
  MODIFY COLUMN `id_card_masked` VARCHAR(32) NULL,
  MODIFY COLUMN `role` VARCHAR(16) NULL,
  MODIFY COLUMN `user_status` TINYINT NULL,
  MODIFY COLUMN `user_version` INT NULL,
  MODIFY COLUMN `user_must_change_password` TINYINT NULL,
  MODIFY COLUMN `next_attempt_at` DATETIME NULL;

-- 旧版本无法从 BCrypt 反推出原请求指纹；删除已完成载荷比永久保留敏感副本更安全。
DELETE FROM `registration_outbox` WHERE `status` = 3;
