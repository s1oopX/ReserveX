-- Existing-volume migration. Safe to rerun: ADMIN/STAFF are backfilled only
-- when the column is first added, so a later successful password change stays cleared.

SET @ds0_had_column := EXISTS (
  SELECT 1 FROM information_schema.columns
  WHERE table_schema = 'reservex_ds0'
    AND table_name = 'user'
    AND column_name = 'must_change_password'
);
SET @ds0_ddl := IF(
  @ds0_had_column,
  'SELECT 1',
  'ALTER TABLE reservex_ds0.`user` ADD COLUMN `must_change_password` TINYINT NOT NULL DEFAULT 0 AFTER `status`, ALGORITHM=INSTANT, LOCK=NONE'
);
PREPARE ds0_stmt FROM @ds0_ddl;
EXECUTE ds0_stmt;
DEALLOCATE PREPARE ds0_stmt;
SET @ds0_backfill := IF(
  @ds0_had_column,
  'SELECT 1',
  'UPDATE reservex_ds0.`user` SET `must_change_password` = 1 WHERE `role` IN (''ADMIN'', ''STAFF'')'
);
PREPARE ds0_stmt FROM @ds0_backfill;
EXECUTE ds0_stmt;
DEALLOCATE PREPARE ds0_stmt;

SET @ds1_had_column := EXISTS (
  SELECT 1 FROM information_schema.columns
  WHERE table_schema = 'reservex_ds1'
    AND table_name = 'user'
    AND column_name = 'must_change_password'
);
SET @ds1_ddl := IF(
  @ds1_had_column,
  'SELECT 1',
  'ALTER TABLE reservex_ds1.`user` ADD COLUMN `must_change_password` TINYINT NOT NULL DEFAULT 0 AFTER `status`, ALGORITHM=INSTANT, LOCK=NONE'
);
PREPARE ds1_stmt FROM @ds1_ddl;
EXECUTE ds1_stmt;
DEALLOCATE PREPARE ds1_stmt;
SET @ds1_backfill := IF(
  @ds1_had_column,
  'SELECT 1',
  'UPDATE reservex_ds1.`user` SET `must_change_password` = 1 WHERE `role` IN (''ADMIN'', ''STAFF'')'
);
PREPARE ds1_stmt FROM @ds1_backfill;
EXECUTE ds1_stmt;
DEALLOCATE PREPARE ds1_stmt;
