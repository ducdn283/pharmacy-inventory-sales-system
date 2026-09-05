-- PISMS - initial database schema (structure only, no data).
-- Import this into an empty database BEFORE the first application start:
--   CREATE DATABASE hang_ngoc_pisms CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--   mysql -u root -p hang_ngoc_pisms < docs/schema.sql
-- Flyway V2..V14 then seed reference data on startup.


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `account` (
  `accountID` int NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `username` varchar(50) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `status` tinyint(1) DEFAULT '1',
  `phoneNumber` varchar(20) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`accountID`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `account_permission` (
  `accountPermissionID` int NOT NULL AUTO_INCREMENT,
  `accountID` int DEFAULT NULL,
  `role` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`accountPermissionID`),
  KEY `accountID` (`accountID`),
  CONSTRAINT `account_permission_ibfk_1` FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `batch` (
  `batchID` int NOT NULL AUTO_INCREMENT,
  `batchCode` varchar(50) NOT NULL,
  `batchName` varchar(50) NOT NULL,
  `productID` int DEFAULT NULL,
  `purchaseDetailID` int DEFAULT NULL,
  `storageQuantity` int NOT NULL,
  `importUnitID` int DEFAULT NULL,
  `importQtyInUnit` int DEFAULT NULL,
  `importPrice` decimal(15,2) NOT NULL,
  `importPricePerBase` decimal(15,2) NOT NULL,
  `importDate` datetime NOT NULL,
  `productionDate` date DEFAULT NULL,
  `expirationDate` date DEFAULT NULL,
  `lotNumber` varchar(50) DEFAULT NULL,
  `status` tinyint(1) DEFAULT '1',
  `note` text,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`batchID`),
  UNIQUE KEY `batchCode` (`batchCode`),
  KEY `productID` (`productID`),
  KEY `purchaseDetailID` (`purchaseDetailID`),
  KEY `importUnitID` (`importUnitID`),
  CONSTRAINT `batch_ibfk_1` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`),
  CONSTRAINT `batch_ibfk_2` FOREIGN KEY (`purchaseDetailID`) REFERENCES `purchase_detail` (`purchaseDetailID`),
  CONSTRAINT `batch_ibfk_3` FOREIGN KEY (`importUnitID`) REFERENCES `product_unit` (`productUnitID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customer` (
  `customerID` int NOT NULL AUTO_INCREMENT,
  `customerType` varchar(50) NOT NULL,
  `name` varchar(100) NOT NULL,
  `taxCode` varchar(100) DEFAULT NULL,
  `address` varchar(100) DEFAULT NULL,
  `bankAccountNumber` varchar(100) DEFAULT NULL,
  `bankName` varchar(100) DEFAULT NULL,
  `phoneNumber` varchar(20) DEFAULT NULL,
  `note` text,
  PRIMARY KEY (`customerID`),
  UNIQUE KEY `taxCode` (`taxCode`),
  UNIQUE KEY `phoneNumber` (`phoneNumber`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `employee_note` (
  `noteID` int NOT NULL AUTO_INCREMENT,
  `accountID` int DEFAULT NULL,
  `date` datetime NOT NULL,
  `content` text NOT NULL,
  PRIMARY KEY (`noteID`),
  KEY `accountID` (`accountID`),
  CONSTRAINT `employee_note_ibfk_1` FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `expense` (
  `expenseID` int NOT NULL AUTO_INCREMENT,
  `expenseCode` varchar(50) NOT NULL,
  `applicantID` int NOT NULL,
  `expenseType` varchar(50) NOT NULL,
  `returnID` int DEFAULT NULL,
  `purchaseID` int DEFAULT NULL,
  `shiftReportID` int DEFAULT NULL,
  `date` datetime NOT NULL,
  `reason` text NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `paid` decimal(15,2) NOT NULL,
  `paidByCash` decimal(15,2) DEFAULT '0.00',
  `paidByBanking` decimal(15,2) DEFAULT '0.00',
  `paidByCredit` decimal(15,2) DEFAULT '0.00',
  `supplierID` int DEFAULT NULL,
  `customerID` int DEFAULT NULL,
  `accountID` int DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `approvedAt` datetime DEFAULT NULL,
  `note` text,
  PRIMARY KEY (`expenseID`),
  UNIQUE KEY `expenseCode` (`expenseCode`),
  KEY `applicantID` (`applicantID`),
  KEY `returnID` (`returnID`),
  KEY `purchaseID` (`purchaseID`),
  KEY `shiftReportID` (`shiftReportID`),
  KEY `supplierID` (`supplierID`),
  KEY `customerID` (`customerID`),
  KEY `accountID` (`accountID`),
  CONSTRAINT `expense_ibfk_1` FOREIGN KEY (`applicantID`) REFERENCES `account` (`accountID`),
  CONSTRAINT `expense_ibfk_2` FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`),
  CONSTRAINT `expense_ibfk_3` FOREIGN KEY (`purchaseID`) REFERENCES `purchase_invoice` (`purchaseID`),
  CONSTRAINT `expense_ibfk_4` FOREIGN KEY (`shiftReportID`) REFERENCES `shift_report` (`shiftReportID`),
  CONSTRAINT `expense_ibfk_5` FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`),
  CONSTRAINT `expense_ibfk_6` FOREIGN KEY (`customerID`) REFERENCES `customer` (`customerID`),
  CONSTRAINT `expense_ibfk_7` FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `financial_setting` (
  `financialSettingID` int NOT NULL AUTO_INCREMENT,
  `taxCalculationMethod` int DEFAULT '1',
  `returnProductOnInvoiceValueRate` decimal(5,2) DEFAULT '0.00',
  `autoGenerateVATInvoice` tinyint(1) DEFAULT '0',
  `vatInvoiceSeries` varchar(10) NOT NULL,
  `openingCashDefault` decimal(15,2) DEFAULT '0.00',
  `taxCode` varchar(100) NOT NULL,
  `locationCode` varchar(20) NOT NULL,
  `locationName` varchar(100) NOT NULL,
  `address` varchar(100) NOT NULL,
  `phoneNumber` varchar(10) NOT NULL,
  `email` varchar(100) NOT NULL,
  `bankAccountNumber` varchar(20) NOT NULL,
  `bankName` varchar(100) NOT NULL,
  `revenueGroup` int DEFAULT '1',
  `autoOffsetDebtOnRefund` tinyint(1) DEFAULT '1',
  `returnPolicyMaxDays` int DEFAULT NULL,
  `bankAccountBalance` decimal(15,2) DEFAULT NULL,
  `cashSafeBalance` decimal(15,2) DEFAULT NULL,
  `balanceUpdatedAt` datetime DEFAULT NULL,
  `setupConfirmed` tinyint(1) DEFAULT '0',
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`financialSettingID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `income` (
  `incomeID` int NOT NULL AUTO_INCREMENT,
  `incomeCode` varchar(50) NOT NULL,
  `applicantID` int NOT NULL,
  `incomeType` varchar(50) NOT NULL,
  `invoiceID` int DEFAULT NULL,
  `returnID` int DEFAULT NULL,
  `shiftReportID` int DEFAULT NULL,
  `date` datetime NOT NULL,
  `reason` text NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `paidByCash` decimal(15,2) NOT NULL,
  `paidByBanking` decimal(15,2) NOT NULL,
  `paidByCredit` decimal(15,2) NOT NULL,
  `supplierID` int DEFAULT NULL,
  `customerID` int DEFAULT NULL,
  `accountID` int DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `note` text,
  `stockAdjustmentID` int DEFAULT NULL,
  `shiftReportOfAccountID` int DEFAULT NULL,
  PRIMARY KEY (`incomeID`),
  UNIQUE KEY `incomeCode` (`incomeCode`),
  KEY `applicantID` (`applicantID`),
  KEY `invoiceID` (`invoiceID`),
  KEY `returnID` (`returnID`),
  KEY `shiftReportID` (`shiftReportID`),
  KEY `supplierID` (`supplierID`),
  KEY `customerID` (`customerID`),
  KEY `accountID` (`accountID`),
  KEY `stockAdjustmentID` (`stockAdjustmentID`),
  KEY `shiftReportOfAccountID` (`shiftReportOfAccountID`),
  CONSTRAINT `income_ibfk_1` FOREIGN KEY (`applicantID`) REFERENCES `account` (`accountID`),
  CONSTRAINT `income_ibfk_2` FOREIGN KEY (`invoiceID`) REFERENCES `invoice` (`invoiceID`),
  CONSTRAINT `income_ibfk_3` FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`),
  CONSTRAINT `income_ibfk_4` FOREIGN KEY (`shiftReportID`) REFERENCES `shift_report` (`shiftReportID`),
  CONSTRAINT `income_ibfk_5` FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`),
  CONSTRAINT `income_ibfk_6` FOREIGN KEY (`customerID`) REFERENCES `customer` (`customerID`),
  CONSTRAINT `income_ibfk_7` FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`),
  CONSTRAINT `income_ibfk_8` FOREIGN KEY (`stockAdjustmentID`) REFERENCES `stock_adjustment` (`stockAdjustmentID`),
  CONSTRAINT `income_ibfk_9` FOREIGN KEY (`shiftReportOfAccountID`) REFERENCES `shift_report` (`shiftReportID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `invoice` (
  `invoiceID` int NOT NULL AUTO_INCREMENT,
  `invoicePattern` varchar(7) NOT NULL,
  `invoiceNumber` varchar(50) NOT NULL,
  `date` datetime NOT NULL,
  `employeeID` int DEFAULT NULL,
  `customerID` int DEFAULT NULL,
  `subtotal` decimal(15,2) NOT NULL,
  `discount` decimal(15,2) DEFAULT '0.00',
  `total` decimal(15,2) NOT NULL,
  `paidByCash` decimal(15,2) NOT NULL,
  `paidByBanking` decimal(15,2) NOT NULL,
  `debtAmount` decimal(15,2) DEFAULT '0.00',
  `prescriptionRequired` tinyint(1) DEFAULT '0',
  `invoiceType` varchar(50) NOT NULL,
  `originalInvoiceID` int DEFAULT NULL,
  `returnID` int DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `returnStatus` varchar(50) DEFAULT NULL,
  `image` varchar(255) DEFAULT NULL,
  `shiftReportID` int DEFAULT NULL,
  `prescriptionCode` text,
  `note` text,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`invoiceID`),
  UNIQUE KEY `invoiceNumber` (`invoiceNumber`),
  KEY `employeeID` (`employeeID`),
  KEY `customerID` (`customerID`),
  KEY `originalInvoiceID` (`originalInvoiceID`),
  KEY `returnID` (`returnID`),
  KEY `shiftReportID` (`shiftReportID`),
  CONSTRAINT `invoice_ibfk_1` FOREIGN KEY (`employeeID`) REFERENCES `account` (`accountID`),
  CONSTRAINT `invoice_ibfk_2` FOREIGN KEY (`customerID`) REFERENCES `customer` (`customerID`),
  CONSTRAINT `invoice_ibfk_3` FOREIGN KEY (`originalInvoiceID`) REFERENCES `invoice` (`invoiceID`),
  CONSTRAINT `invoice_ibfk_4` FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`),
  CONSTRAINT `invoice_ibfk_5` FOREIGN KEY (`shiftReportID`) REFERENCES `shift_report` (`shiftReportID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `invoice_detail` (
  `invoiceDetailID` int NOT NULL AUTO_INCREMENT,
  `invoiceID` int NOT NULL,
  `productID` int NOT NULL,
  `productUnitID` int NOT NULL,
  `batchID` int NOT NULL,
  `quantity` int NOT NULL,
  `unitName` varchar(20) NOT NULL,
  `baseQtyDeducted` int NOT NULL,
  `unitSellPrice` decimal(15,2) NOT NULL,
  `subtotal` decimal(15,2) NOT NULL,
  `returnedQty` int DEFAULT '0',
  `note` text,
  PRIMARY KEY (`invoiceDetailID`),
  KEY `invoiceID` (`invoiceID`),
  KEY `productID` (`productID`),
  KEY `productUnitID` (`productUnitID`),
  KEY `batchID` (`batchID`),
  CONSTRAINT `invoice_detail_ibfk_1` FOREIGN KEY (`invoiceID`) REFERENCES `invoice` (`invoiceID`),
  CONSTRAINT `invoice_detail_ibfk_2` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`),
  CONSTRAINT `invoice_detail_ibfk_3` FOREIGN KEY (`productUnitID`) REFERENCES `product_unit` (`productUnitID`),
  CONSTRAINT `invoice_detail_ibfk_4` FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `medicine_api` (
  `medicineAPIID` int NOT NULL AUTO_INCREMENT,
  `productID` int DEFAULT NULL,
  `apiName` varchar(100) NOT NULL,
  `strength` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`medicineAPIID`),
  KEY `productID` (`productID`),
  CONSTRAINT `medicine_api_ibfk_1` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification` (
  `notificationID` int NOT NULL AUTO_INCREMENT,
  `accountID` int NOT NULL,
  `message` text NOT NULL,
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `title` varchar(255) NOT NULL,
  `notificationType` varchar(50) NOT NULL,
  `category` varchar(50) NOT NULL,
  `severity` varchar(20) NOT NULL DEFAULT 'INFO',
  `status` varchar(20) NOT NULL DEFAULT 'UNREAD',
  `targetRole` varchar(50) NOT NULL,
  `referenceType` varchar(50) DEFAULT NULL,
  `referenceId` int DEFAULT NULL,
  `actionUrl` varchar(255) DEFAULT NULL,
  `isRead` tinyint(1) NOT NULL DEFAULT '0',
  `readAt` datetime DEFAULT NULL,
  `resolvedAt` datetime DEFAULT NULL,
  `expiresAt` datetime DEFAULT NULL,
  `dedupeKey` varchar(255) DEFAULT NULL,
  `isActive` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`notificationID`),
  KEY `idx_notification_account_created` (`accountID`,`createdAt`),
  KEY `idx_notification_account_read` (`accountID`,`isRead`,`isActive`),
  KEY `idx_notification_account_category` (`accountID`,`category`,`isActive`),
  KEY `idx_notification_account_severity` (`accountID`,`severity`,`isActive`),
  KEY `idx_notification_account_status` (`accountID`,`status`,`isActive`),
  KEY `idx_notification_reference` (`referenceType`,`referenceId`),
  KEY `idx_notification_dedupe` (`dedupeKey`,`isActive`),
  CONSTRAINT `notification_ibfk_1` FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `password_reset_token` (
  `tokenID` int NOT NULL AUTO_INCREMENT,
  `accountID` int NOT NULL,
  `tokenHash` varchar(64) NOT NULL,
  `expiresAt` datetime NOT NULL,
  `usedAt` datetime DEFAULT NULL,
  `createdAt` datetime NOT NULL,
  PRIMARY KEY (`tokenID`),
  UNIQUE KEY `uk_password_reset_token_tokenHash` (`tokenHash`),
  KEY `idx_password_reset_token_accountID` (`accountID`),
  CONSTRAINT `fk_password_reset_token_account` FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `position` (
  `positionID` int NOT NULL AUTO_INCREMENT,
  `productID` int NOT NULL,
  `name` varchar(100) NOT NULL,
  PRIMARY KEY (`positionID`),
  KEY `productID` (`productID`),
  CONSTRAINT `position_ibfk_1` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `procurement_plan` (
  `procurementID` int NOT NULL AUTO_INCREMENT,
  `procurementCode` varchar(50) NOT NULL,
  `date` datetime NOT NULL,
  `status` varchar(50) NOT NULL,
  `note` text,
  `createdAt` datetime NOT NULL,
  PRIMARY KEY (`procurementID`),
  UNIQUE KEY `procurementCode` (`procurementCode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `procurement_plan_detail` (
  `procurementDetailID` int NOT NULL AUTO_INCREMENT,
  `procurementID` int DEFAULT NULL,
  `productID` int NOT NULL,
  `requestedQuantity` int NOT NULL,
  `unit` varchar(20) DEFAULT NULL,
  `estimatedPrice` decimal(15,2) DEFAULT NULL,
  `supplierID` int DEFAULT NULL,
  `currentStock` int DEFAULT NULL,
  PRIMARY KEY (`procurementDetailID`),
  KEY `procurementID` (`procurementID`),
  KEY `productID` (`productID`),
  KEY `supplierID` (`supplierID`),
  CONSTRAINT `procurement_plan_detail_ibfk_1` FOREIGN KEY (`procurementID`) REFERENCES `procurement_plan` (`procurementID`),
  CONSTRAINT `procurement_plan_detail_ibfk_2` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`),
  CONSTRAINT `procurement_plan_detail_ibfk_3` FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `producer` (
  `producerID` int NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  PRIMARY KEY (`producerID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product` (
  `productID` int NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `code` varchar(50) NOT NULL,
  `barcode` varchar(50) DEFAULT NULL,
  `typeID` int DEFAULT NULL,
  `maxStock` int DEFAULT '0',
  `minStock` int DEFAULT '0',
  `producerID` int DEFAULT NULL,
  `origin` varchar(50) DEFAULT NULL,
  `registrationNumber` varchar(100) DEFAULT NULL,
  `status` tinyint(1) DEFAULT '1',
  `vatRateOverride` decimal(5,2) DEFAULT NULL,
  `image` varchar(255) DEFAULT NULL,
  `note` text,
  PRIMARY KEY (`productID`),
  UNIQUE KEY `code` (`code`),
  UNIQUE KEY `barcode` (`barcode`),
  KEY `typeID` (`typeID`),
  KEY `producerID` (`producerID`),
  CONSTRAINT `product_ibfk_1` FOREIGN KEY (`typeID`) REFERENCES `type` (`typeID`),
  CONSTRAINT `product_ibfk_2` FOREIGN KEY (`producerID`) REFERENCES `producer` (`producerID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_unit` (
  `productUnitID` int NOT NULL AUTO_INCREMENT,
  `productID` int DEFAULT NULL,
  `unitName` varchar(20) NOT NULL,
  `ratio` decimal(10,4) NOT NULL,
  `sellPrice` decimal(15,2) NOT NULL,
  `isDefault` tinyint(1) DEFAULT '0',
  `isBaseUnit` tinyint(1) DEFAULT '0',
  `isActive` tinyint(1) DEFAULT '1',
  `note` text,
  PRIMARY KEY (`productUnitID`),
  UNIQUE KEY `product_unit_index_0` (`productID`,`unitName`),
  CONSTRAINT `product_unit_ibfk_1` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `purchase_detail` (
  `purchaseDetailID` int NOT NULL AUTO_INCREMENT,
  `purchaseID` int DEFAULT NULL,
  `productID` int DEFAULT NULL,
  `quantity` int NOT NULL,
  `importPrice` decimal(15,2) NOT NULL,
  `productionDate` date DEFAULT NULL,
  `expirationDate` date DEFAULT NULL,
  `lotNumber` varchar(50) DEFAULT NULL,
  `returnQty` int NOT NULL,
  `vatRate` decimal(15,2) DEFAULT NULL,
  `preTaxAmount` decimal(15,2) DEFAULT NULL,
  `vatAmount` decimal(15,2) DEFAULT NULL,
  PRIMARY KEY (`purchaseDetailID`),
  KEY `purchaseID` (`purchaseID`),
  KEY `productID` (`productID`),
  CONSTRAINT `purchase_detail_ibfk_1` FOREIGN KEY (`purchaseID`) REFERENCES `purchase_invoice` (`purchaseID`),
  CONSTRAINT `purchase_detail_ibfk_2` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `purchase_invoice` (
  `purchaseID` int NOT NULL AUTO_INCREMENT,
  `purchaseInvoiceCode` varchar(50) NOT NULL,
  `date` datetime NOT NULL,
  `supplierID` int DEFAULT NULL,
  `employeeID` int DEFAULT NULL,
  `approvedAt` datetime DEFAULT NULL,
  `additionCost` decimal(15,2) DEFAULT NULL,
  `discount` decimal(15,2) DEFAULT NULL,
  `totalAmount` decimal(15,2) DEFAULT NULL,
  `procurementID` int DEFAULT NULL,
  `returnStatus` varchar(50) NOT NULL,
  `paid` decimal(15,2) DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `note` text,
  `vatInvoiceNumber` varchar(50) NOT NULL,
  `vatInvoiceDate` date NOT NULL,
  `dueDate` date DEFAULT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`purchaseID`),
  UNIQUE KEY `purchaseInvoiceCode` (`purchaseInvoiceCode`),
  KEY `supplierID` (`supplierID`),
  KEY `employeeID` (`employeeID`),
  KEY `procurementID` (`procurementID`),
  CONSTRAINT `purchase_invoice_ibfk_1` FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`),
  CONSTRAINT `purchase_invoice_ibfk_2` FOREIGN KEY (`employeeID`) REFERENCES `account` (`accountID`),
  CONSTRAINT `purchase_invoice_ibfk_3` FOREIGN KEY (`procurementID`) REFERENCES `procurement_plan` (`procurementID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `return` (
  `returnID` int NOT NULL AUTO_INCREMENT,
  `returnCode` varchar(50) NOT NULL,
  `invoiceID` int DEFAULT NULL,
  `purchaseID` int DEFAULT NULL,
  `returnedBy` int NOT NULL,
  `returnDate` datetime NOT NULL,
  `returnType` varchar(50) NOT NULL,
  `totalRefund` decimal(15,2) NOT NULL,
  `offsetDebtAmount` decimal(15,2) DEFAULT '0.00',
  `shiftReportID` int DEFAULT NULL,
  `reason` text NOT NULL,
  `status` varchar(50) NOT NULL,
  `approvedAt` datetime DEFAULT NULL,
  `note` text,
  `appliedRefundRate` decimal(5,2) DEFAULT NULL,
  PRIMARY KEY (`returnID`),
  UNIQUE KEY `returnCode` (`returnCode`),
  KEY `invoiceID` (`invoiceID`),
  KEY `purchaseID` (`purchaseID`),
  KEY `returnedBy` (`returnedBy`),
  KEY `shiftReportID` (`shiftReportID`),
  CONSTRAINT `return_ibfk_1` FOREIGN KEY (`invoiceID`) REFERENCES `invoice` (`invoiceID`),
  CONSTRAINT `return_ibfk_2` FOREIGN KEY (`purchaseID`) REFERENCES `purchase_invoice` (`purchaseID`),
  CONSTRAINT `return_ibfk_3` FOREIGN KEY (`returnedBy`) REFERENCES `account` (`accountID`),
  CONSTRAINT `return_ibfk_4` FOREIGN KEY (`shiftReportID`) REFERENCES `shift_report` (`shiftReportID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `return_detail` (
  `returnDetailID` int NOT NULL AUTO_INCREMENT,
  `returnID` int NOT NULL,
  `invoiceDetailID` int DEFAULT NULL,
  `purchaseDetailID` int DEFAULT NULL,
  `productID` int NOT NULL,
  `productUnitID` int NOT NULL,
  `batchID` int NOT NULL,
  `returnQty` int NOT NULL,
  `baseQtyRestored` int NOT NULL,
  `unitSellPrice` decimal(15,2) NOT NULL,
  `lineRefund` decimal(15,2) NOT NULL,
  `restockable` tinyint(1) DEFAULT '1',
  `originalLineValue` decimal(15,2) NOT NULL,
  PRIMARY KEY (`returnDetailID`),
  KEY `returnID` (`returnID`),
  KEY `invoiceDetailID` (`invoiceDetailID`),
  KEY `purchaseDetailID` (`purchaseDetailID`),
  KEY `productID` (`productID`),
  KEY `productUnitID` (`productUnitID`),
  KEY `batchID` (`batchID`),
  CONSTRAINT `return_detail_ibfk_1` FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`),
  CONSTRAINT `return_detail_ibfk_2` FOREIGN KEY (`invoiceDetailID`) REFERENCES `invoice_detail` (`invoiceDetailID`),
  CONSTRAINT `return_detail_ibfk_3` FOREIGN KEY (`purchaseDetailID`) REFERENCES `purchase_detail` (`purchaseDetailID`),
  CONSTRAINT `return_detail_ibfk_4` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`),
  CONSTRAINT `return_detail_ibfk_5` FOREIGN KEY (`productUnitID`) REFERENCES `product_unit` (`productUnitID`),
  CONSTRAINT `return_detail_ibfk_6` FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `shift_report` (
  `shiftReportID` int NOT NULL AUTO_INCREMENT,
  `shiftReportCode` varchar(50) NOT NULL,
  `cashierID` int NOT NULL,
  `shiftDate` date NOT NULL,
  `shiftType` varchar(20) NOT NULL,
  `startTime` datetime NOT NULL,
  `endTime` datetime DEFAULT NULL,
  `openingCash` decimal(15,2) NOT NULL,
  `totalInvoices` int DEFAULT '0',
  `totalRevenue` decimal(15,2) DEFAULT '0.00',
  `totalReturns` int DEFAULT '0',
  `totalReturnAmount` decimal(15,2) DEFAULT '0.00',
  `totalDebtCollected` decimal(15,2) DEFAULT '0.00',
  `totalCashIn` decimal(15,2) DEFAULT '0.00',
  `totalBankingIn` decimal(15,2) DEFAULT '0.00',
  `totalCashOut` decimal(15,2) DEFAULT '0.00',
  `totalBankingOut` decimal(15,2) DEFAULT '0.00',
  `expectedClosingCash` decimal(15,2) DEFAULT NULL,
  `actualClosingCash` decimal(15,2) DEFAULT NULL,
  `cashDiscrepancy` decimal(15,2) DEFAULT NULL,
  `noteDiscrepancy` text,
  `status` varchar(50) NOT NULL,
  `approvedAt` datetime DEFAULT NULL,
  `note` text,
  `createdAt` datetime NOT NULL,
  PRIMARY KEY (`shiftReportID`),
  UNIQUE KEY `shiftReportCode` (`shiftReportCode`),
  KEY `cashierID` (`cashierID`),
  CONSTRAINT `shift_report_ibfk_1` FOREIGN KEY (`cashierID`) REFERENCES `account` (`accountID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_adjustment` (
  `stockAdjustmentID` int NOT NULL AUTO_INCREMENT,
  `stockAdjustmentCode` varchar(50) NOT NULL,
  `adjustmentType` varchar(50) NOT NULL,
  `date` datetime NOT NULL,
  `reason` text NOT NULL,
  `stockReviewID` int DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `note` text,
  PRIMARY KEY (`stockAdjustmentID`),
  UNIQUE KEY `stockAdjustmentCode` (`stockAdjustmentCode`),
  KEY `stockReviewID` (`stockReviewID`),
  CONSTRAINT `stock_adjustment_ibfk_1` FOREIGN KEY (`stockReviewID`) REFERENCES `stock_review` (`stockReviewID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_adjustment_detail` (
  `stockAdjustmentDetailID` int NOT NULL AUTO_INCREMENT,
  `stockAdjustmentID` int NOT NULL,
  `productID` int NOT NULL,
  `productUnitID` int NOT NULL,
  `batchID` int NOT NULL,
  `direction` varchar(50) NOT NULL,
  `quantity` int NOT NULL,
  `baseQtyDeducted` int NOT NULL,
  `unitCostPrice` decimal(15,2) NOT NULL,
  `lineCost` decimal(15,2) NOT NULL,
  `note` text,
  `refSellPrice` decimal(15,2) DEFAULT NULL,
  `oldExpirationDate` date DEFAULT NULL,
  `newExpirationDate` date DEFAULT NULL,
  PRIMARY KEY (`stockAdjustmentDetailID`),
  KEY `stockAdjustmentID` (`stockAdjustmentID`),
  KEY `productID` (`productID`),
  KEY `productUnitID` (`productUnitID`),
  KEY `batchID` (`batchID`),
  CONSTRAINT `stock_adjustment_detail_ibfk_1` FOREIGN KEY (`stockAdjustmentID`) REFERENCES `stock_adjustment` (`stockAdjustmentID`),
  CONSTRAINT `stock_adjustment_detail_ibfk_2` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`),
  CONSTRAINT `stock_adjustment_detail_ibfk_3` FOREIGN KEY (`productUnitID`) REFERENCES `product_unit` (`productUnitID`),
  CONSTRAINT `stock_adjustment_detail_ibfk_4` FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_review` (
  `stockReviewID` int NOT NULL AUTO_INCREMENT,
  `stockReviewCode` varchar(50) NOT NULL,
  `type` varchar(50) NOT NULL,
  `reviewDate` datetime NOT NULL,
  `createdBy` int DEFAULT NULL,
  `approvedBy` int DEFAULT NULL,
  `approvedAt` datetime DEFAULT NULL,
  `status` varchar(50) NOT NULL,
  `note` text,
  PRIMARY KEY (`stockReviewID`),
  UNIQUE KEY `stockReviewCode` (`stockReviewCode`),
  KEY `createdBy` (`createdBy`),
  KEY `approvedBy` (`approvedBy`),
  CONSTRAINT `stock_review_ibfk_1` FOREIGN KEY (`createdBy`) REFERENCES `account` (`accountID`),
  CONSTRAINT `stock_review_ibfk_2` FOREIGN KEY (`approvedBy`) REFERENCES `account` (`accountID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_review_detail` (
  `stockReviewDetailID` int NOT NULL AUTO_INCREMENT,
  `stockReviewID` int DEFAULT NULL,
  `productID` int DEFAULT NULL,
  `batchID` int DEFAULT NULL,
  `systemQty` int NOT NULL,
  `actualQty` int NOT NULL,
  `discrepancy` int DEFAULT NULL,
  `recordedExpirationDate` date DEFAULT NULL,
  `actualExpirationDate` date DEFAULT NULL,
  `conditionStatus` varchar(50) DEFAULT NULL,
  `note` text,
  PRIMARY KEY (`stockReviewDetailID`),
  KEY `stockReviewID` (`stockReviewID`),
  KEY `productID` (`productID`),
  KEY `batchID` (`batchID`),
  CONSTRAINT `stock_review_detail_ibfk_1` FOREIGN KEY (`stockReviewID`) REFERENCES `stock_review` (`stockReviewID`),
  CONSTRAINT `stock_review_detail_ibfk_2` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`),
  CONSTRAINT `stock_review_detail_ibfk_3` FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `supplier` (
  `supplierID` int NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `address` text,
  `phone` varchar(20) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `taxCode` varchar(20) NOT NULL,
  PRIMARY KEY (`supplierID`),
  UNIQUE KEY `taxCode` (`taxCode`),
  UNIQUE KEY `phone` (`phone`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `supplier_product` (
  `supplierProductID` int NOT NULL AUTO_INCREMENT,
  `supplierID` int NOT NULL,
  `productID` int NOT NULL,
  `costPrice` decimal(15,2) DEFAULT NULL,
  `isPreferred` tinyint(1) DEFAULT '0',
  `isActive` tinyint(1) DEFAULT '1',
  `note` text,
  PRIMARY KEY (`supplierProductID`),
  UNIQUE KEY `supplier_product_index_1` (`supplierID`,`productID`),
  KEY `productID` (`productID`),
  CONSTRAINT `supplier_product_ibfk_1` FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`),
  CONSTRAINT `supplier_product_ibfk_2` FOREIGN KEY (`productID`) REFERENCES `product` (`productID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tax_period_snapshot` (
  `taxPeriodSnapshotID` int NOT NULL AUTO_INCREMENT,
  `periodLabel` varchar(10) DEFAULT NULL,
  `periodTaxType` int DEFAULT NULL,
  `startDate` date DEFAULT NULL,
  `endDate` date DEFAULT NULL,
  `incomeTax` decimal(15,2) DEFAULT NULL,
  `vatOutput` decimal(15,2) DEFAULT NULL,
  `quarterlyRevenue` decimal(15,2) DEFAULT NULL,
  `vatRevenue` decimal(15,2) DEFAULT NULL,
  `recordedAt` datetime DEFAULT NULL,
  `note` text,
  PRIMARY KEY (`taxPeriodSnapshotID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `type` (
  `typeID` int NOT NULL AUTO_INCREMENT,
  `sortType` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `defaultVATRate` decimal(5,2) NOT NULL,
  PRIMARY KEY (`typeID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

