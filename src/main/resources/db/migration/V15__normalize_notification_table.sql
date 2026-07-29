-- Normalize Notification table for notification feature.
-- No DELIMITER / PROCEDURE version, safer for Flyway through Spring Boot.

-- Add missing columns safely by dynamic SQL.

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'title') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `title` varchar(255) NULL AFTER `createdAt`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'notificationType') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `notificationType` varchar(50) NULL AFTER `title`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'category') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `category` varchar(50) NULL AFTER `notificationType`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'severity') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `severity` varchar(20) NULL AFTER `category`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'status') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `status` varchar(20) NULL AFTER `severity`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'targetRole') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `targetRole` varchar(50) NULL AFTER `status`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'referenceType') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `referenceType` varchar(50) NULL AFTER `targetRole`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'referenceId') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `referenceId` int NULL AFTER `referenceType`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'actionUrl') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `actionUrl` varchar(255) NULL AFTER `referenceId`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'isRead') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `isRead` boolean NULL AFTER `actionUrl`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'readAt') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `readAt` datetime NULL AFTER `isRead`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'resolvedAt') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `resolvedAt` datetime NULL AFTER `readAt`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'expiresAt') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `expiresAt` datetime NULL AFTER `resolvedAt`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'dedupeKey') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `dedupeKey` varchar(255) NULL AFTER `expiresAt`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND COLUMN_NAME = 'isActive') = 0,
        'ALTER TABLE `Notification` ADD COLUMN `isActive` boolean NULL AFTER `dedupeKey`',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- Backfill old data.
UPDATE `Notification`
SET `title` = 'Thông báo'
WHERE `title` IS NULL OR TRIM(`title`) = '';

UPDATE `Notification`
SET `notificationType` = 'SYSTEM'
WHERE `notificationType` IS NULL OR TRIM(`notificationType`) = '';

UPDATE `Notification`
SET `category` = 'HE_THONG'
WHERE `category` IS NULL OR TRIM(`category`) = '';

UPDATE `Notification`
SET `severity` = 'INFO'
WHERE `severity` IS NULL OR TRIM(`severity`) = '';

UPDATE `Notification`
SET `status` = 'UNREAD'
WHERE `status` IS NULL OR TRIM(`status`) = '';

UPDATE `Notification`
SET `targetRole` = 'SYSTEM'
WHERE `targetRole` IS NULL OR TRIM(`targetRole`) = '';

UPDATE `Notification`
SET `isRead` = false
WHERE `isRead` IS NULL;

UPDATE `Notification`
SET `isActive` = true
WHERE `isActive` IS NULL;

UPDATE `Notification`
SET `createdAt` = CURRENT_TIMESTAMP
WHERE `createdAt` IS NULL;

DELETE FROM `Notification`
WHERE `accountID` IS NULL;


-- Normalize constraints and defaults.
ALTER TABLE `Notification`
    MODIFY COLUMN `accountID` int NOT NULL,
    MODIFY COLUMN `message` text NOT NULL,
    MODIFY COLUMN `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    MODIFY COLUMN `title` varchar(255) NOT NULL,
    MODIFY COLUMN `notificationType` varchar(50) NOT NULL,
    MODIFY COLUMN `category` varchar(50) NOT NULL,
    MODIFY COLUMN `severity` varchar(20) NOT NULL DEFAULT 'INFO',
    MODIFY COLUMN `status` varchar(20) NOT NULL DEFAULT 'UNREAD',
    MODIFY COLUMN `targetRole` varchar(50) NOT NULL,
    MODIFY COLUMN `referenceType` varchar(50) NULL,
    MODIFY COLUMN `referenceId` int NULL,
    MODIFY COLUMN `actionUrl` varchar(255) NULL,
    MODIFY COLUMN `isRead` boolean NOT NULL DEFAULT false,
    MODIFY COLUMN `readAt` datetime NULL,
    MODIFY COLUMN `resolvedAt` datetime NULL,
    MODIFY COLUMN `expiresAt` datetime NULL,
    MODIFY COLUMN `dedupeKey` varchar(255) NULL,
    MODIFY COLUMN `isActive` boolean NOT NULL DEFAULT true;


-- Add indexes safely.

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND INDEX_NAME = 'idx_notification_account_created') = 0,
        'CREATE INDEX idx_notification_account_created ON `Notification` (`accountID`, `createdAt`)',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND INDEX_NAME = 'idx_notification_account_read') = 0,
        'CREATE INDEX idx_notification_account_read ON `Notification` (`accountID`, `isRead`, `isActive`)',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND INDEX_NAME = 'idx_notification_account_category') = 0,
        'CREATE INDEX idx_notification_account_category ON `Notification` (`accountID`, `category`, `isActive`)',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND INDEX_NAME = 'idx_notification_account_severity') = 0,
        'CREATE INDEX idx_notification_account_severity ON `Notification` (`accountID`, `severity`, `isActive`)',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND INDEX_NAME = 'idx_notification_account_status') = 0,
        'CREATE INDEX idx_notification_account_status ON `Notification` (`accountID`, `status`, `isActive`)',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND INDEX_NAME = 'idx_notification_reference') = 0,
        'CREATE INDEX idx_notification_reference ON `Notification` (`referenceType`, `referenceId`)',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'Notification'
           AND INDEX_NAME = 'idx_notification_dedupe') = 0,
        'CREATE INDEX idx_notification_dedupe ON `Notification` (`dedupeKey`, `isActive`)',
        'SELECT 1'
            );
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;