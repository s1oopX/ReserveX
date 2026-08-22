-- Existing sharded user tables need the version used by STAFF If-Match updates.
SET @ds0_has_user_version := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = 'reservex_ds0' AND table_name = 'user' AND column_name = 'version'
);
SET @ds0_user_version_ddl := IF(
  @ds0_has_user_version > 0,
  'SELECT 1',
  'ALTER TABLE reservex_ds0.`user` ADD COLUMN `version` INT NOT NULL DEFAULT 0 AFTER `status`, ALGORITHM=INSTANT'
);
PREPARE ds0_user_version_stmt FROM @ds0_user_version_ddl;
EXECUTE ds0_user_version_stmt;
DEALLOCATE PREPARE ds0_user_version_stmt;

SET @ds1_has_user_version := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = 'reservex_ds1' AND table_name = 'user' AND column_name = 'version'
);
SET @ds1_user_version_ddl := IF(
  @ds1_has_user_version > 0,
  'SELECT 1',
  'ALTER TABLE reservex_ds1.`user` ADD COLUMN `version` INT NOT NULL DEFAULT 0 AFTER `status`, ALGORITHM=INSTANT'
);
PREPARE ds1_user_version_stmt FROM @ds1_user_version_ddl;
EXECUTE ds1_user_version_stmt;
DEALLOCATE PREPARE ds1_user_version_stmt;
