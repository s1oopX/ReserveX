USE `reservex_single`;

CREATE TABLE IF NOT EXISTS `dead_letter_message` (
  `message_id`      VARCHAR(64) NOT NULL,
  `source_group`    VARCHAR(64) NOT NULL,
  `target_topic`    VARCHAR(64) NOT NULL,
  `body`            MEDIUMTEXT  NOT NULL,
  `reconsume_times` INT         NOT NULL,
  `status`          TINYINT     NOT NULL DEFAULT 0,
  `captured_at`     DATETIME    NOT NULL,
  `update_at`       DATETIME    NULL,
  `resolver_id`     BIGINT      NULL,
  PRIMARY KEY (`message_id`),
  KEY `idx_dead_letter_status_time` (`status`, `captured_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RocketMQ 死信落库与人工重放';
