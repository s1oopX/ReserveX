-- Add the single-database identity owner used by registration.
-- Existing duplicates are retained for audit; the oldest account remains the owner and later
-- accounts are quarantined so no two active accounts can use the same identity.

USE `reservex_single`;

CREATE TABLE IF NOT EXISTS `id_card_identity` (
  `id_card_hash` CHAR(64) NOT NULL,
  `user_id`      BIGINT   NOT NULL,
  `create_at`    DATETIME NOT NULL,
  PRIMARY KEY (`id_card_hash`),
  UNIQUE KEY `uk_id_card_identity_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='身份证全局唯一账号归属';

DROP TEMPORARY TABLE IF EXISTS `tmp_id_card_owner`;
CREATE TEMPORARY TABLE `tmp_id_card_owner` AS
SELECT `id_card_hash`, MIN(`user_id`) AS `user_id`, MIN(`create_at`) AS `create_at`
FROM (
  SELECT `id_card_hash`, `user_id`, `create_at` FROM `reservex_ds0`.`user`
  UNION ALL
  SELECT `id_card_hash`, `user_id`, `create_at` FROM `reservex_ds1`.`user`
) AS `all_users`
GROUP BY `id_card_hash`;

INSERT IGNORE INTO `id_card_identity` (`id_card_hash`, `user_id`, `create_at`)
SELECT `id_card_hash`, `user_id`, `create_at` FROM `tmp_id_card_owner`;

INSERT IGNORE INTO `audit_log`
  (`id`, `operator_type`, `operator_id`, `action`, `target_type`, `target_id`,
   `before`, `after`, `request_id`, `create_at`)
SELECT -u.`user_id`, 'SYSTEM', NULL, 'QUARANTINE_DUPLICATE_ID_CARD', 'USER', u.`user_id`,
       '{"status":0}', '{"status":1}', 'migration-20260818-id-card', NOW()
FROM `reservex_ds0`.`user` u
JOIN `tmp_id_card_owner` o ON o.`id_card_hash` = u.`id_card_hash`
WHERE u.`user_id` <> o.`user_id` AND u.`status` = 0;

INSERT IGNORE INTO `audit_log`
  (`id`, `operator_type`, `operator_id`, `action`, `target_type`, `target_id`,
   `before`, `after`, `request_id`, `create_at`)
SELECT -u.`user_id`, 'SYSTEM', NULL, 'QUARANTINE_DUPLICATE_ID_CARD', 'USER', u.`user_id`,
       '{"status":0}', '{"status":1}', 'migration-20260818-id-card', NOW()
FROM `reservex_ds1`.`user` u
JOIN `tmp_id_card_owner` o ON o.`id_card_hash` = u.`id_card_hash`
WHERE u.`user_id` <> o.`user_id` AND u.`status` = 0;

UPDATE `reservex_ds0`.`user` u
JOIN `tmp_id_card_owner` o ON o.`id_card_hash` = u.`id_card_hash`
SET u.`status` = 1, u.`update_at` = NOW()
WHERE u.`user_id` <> o.`user_id` AND u.`status` = 0;

UPDATE `reservex_ds1`.`user` u
JOIN `tmp_id_card_owner` o ON o.`id_card_hash` = u.`id_card_hash`
SET u.`status` = 1, u.`update_at` = NOW()
WHERE u.`user_id` <> o.`user_id` AND u.`status` = 0;

DROP TEMPORARY TABLE `tmp_id_card_owner`;
