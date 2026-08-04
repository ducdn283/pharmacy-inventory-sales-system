CREATE TABLE `product`
(
    `productID`          int PRIMARY KEY AUTO_INCREMENT,
    `name`               varchar(255)       NOT NULL,
    `code`               varchar(50) UNIQUE NOT NULL,
    `barcode`            varchar(50) UNIQUE,
    `typeID`             int,
    `maxStock`           int     DEFAULT 0,
    `minStock`           int     DEFAULT 0,
    `producerID`         int,
    `origin`             varchar(50),
    `registrationNumber` varchar(100),
    `status`             boolean DEFAULT true,
    `vatRateOverride`    decimal(5, 2),
    `image`              varchar(255),
    `note`               text
);

CREATE TABLE `productunit`
(
    `productUnitID` int PRIMARY KEY AUTO_INCREMENT,
    `productID`     int,
    `unitName`      varchar(20)    NOT NULL,
    `ratio`         decimal(10, 4) NOT NULL,
    `sellPrice`     decimal(15, 2) NOT NULL,
    `isDefault`     boolean DEFAULT false,
    `isBaseUnit`    boolean DEFAULT false,
    `isActive`      boolean DEFAULT true,
    `note`          text
);

CREATE TABLE `batch`
(
    `batchID`            int PRIMARY KEY AUTO_INCREMENT,
    `batchCode`          varchar(50) UNIQUE NOT NULL,
    `batchName`          varchar(50)        NOT NULL,
    `productID`          int,
    `purchaseDetailID`   int,
    `storageQuantity`    int                NOT NULL,
    `importUnitID`       int,
    `importQtyInUnit`    int,
    `importPrice`        decimal(15, 2)     NOT NULL,
    `importPricePerBase` decimal(15, 2)     NOT NULL,
    `importDate`         datetime           NOT NULL,
    `productionDate`     date,
    `expirationDate`     date,
    `lotNumber`          varchar(50),
    `status`             boolean                     DEFAULT true,
    `note`               text,
    `version`            int                NOT NULL DEFAULT 0
);

CREATE TABLE `combocomponent`
(
    `comboComponentID`   int PRIMARY KEY AUTO_INCREMENT,
    `comboID`            int,
    `componentProductID` int,
    `componentUnitID`    int,
    `quantity`           decimal(10, 4) NOT NULL,
    `note`               text
);

CREATE TABLE `medicineapi`
(
    `medicineAPIID` int PRIMARY KEY AUTO_INCREMENT,
    `productID`     int,
    `apiName`       varchar(100) NOT NULL,
    `strength`      varchar(50)
);

CREATE TABLE `type`
(
    `typeID`         int PRIMARY KEY AUTO_INCREMENT,
    `sortType`       varchar(100)  NOT NULL,
    `name`           varchar(100)  NOT NULL,
    `defaultVATRate` decimal(5, 2) NOT NULL
);

CREATE TABLE `position`
(
    `positionID` int PRIMARY KEY AUTO_INCREMENT,
    `productID`  int          NOT NULL,
    `name`       varchar(100) NOT NULL
);

CREATE TABLE `producer`
(
    `producerID` int PRIMARY KEY AUTO_INCREMENT,
    `name`       varchar(255) NOT NULL
);

CREATE TABLE `financialsetting`
(
    `financialSettingID`              int PRIMARY KEY AUTO_INCREMENT,
    `taxCalculationMethod`            int                   DEFAULT 1,
    `returnProductOnInvoiceValueRate` decimal(5, 2)         DEFAULT 0,
    `autoGenerateVATInvoice`          boolean               DEFAULT false,
    `vatInvoiceSeries`                varchar(10)  NOT NULL,
    `openingCashDefault`              decimal(15, 2)        DEFAULT 0,
    `taxCode`                         varchar(100) NOT NULL,
    `locationCode`                    varchar(20)  NOT NULL,
    `locationName`                    varchar(100) NOT NULL,
    `address`                         varchar(100) NOT NULL,
    `phoneNumber`                     varchar(10)  NOT NULL,
    `email`                           varchar(100) NOT NULL,
    `bankAccountNumber`               varchar(20)  NOT NULL,
    `bankName`                        varchar(100) NOT NULL,
    `revenueGroup`                    int                   DEFAULT 1,
    `autoOffsetDebtOnRefund`          boolean               DEFAULT true,
    `returnPolicyMaxDays`             int,
    `bankAccountBalance`              decimal(15, 2),
    `cashSafeBalance`                 decimal(15, 2),
    `balanceUpdatedAt`                datetime,
    `setupConfirmed`                  boolean               DEFAULT false,
    `version`                         int          NOT NULL DEFAULT 0
);

CREATE TABLE `invoice`
(
    `invoiceID`            int PRIMARY KEY AUTO_INCREMENT,
    `invoicePattern`       varchar(7)         NOT NULL,
    `invoiceNumber`        varchar(50) UNIQUE NOT NULL,
    `date`                 datetime           NOT NULL,
    `employeeID`           int,
    `customerID`           int,
    `subtotal`             decimal(15, 2)     NOT NULL,
    `discount`             decimal(15, 2)              DEFAULT 0,
    `total`                decimal(15, 2)     NOT NULL,
    `paidByCash`           decimal(15, 2)     NOT NULL,
    `paidByBanking`        decimal(15, 2)     NOT NULL,
    `debtAmount`           decimal(15, 2)              DEFAULT 0,
    `prescriptionRequired` boolean                     DEFAULT false,
    `invoiceType`          varchar(50)        NOT NULL,
    `originalInvoiceID`    int,
    `returnID`             int,
    `status`               varchar(50)        NOT NULL,
    `returnStatus`         varchar(50),
    `shiftReportID`        int,
    `prescriptionCode`     text,
    `note`                 text,
    `version`              int                NOT NULL DEFAULT 0
);

CREATE TABLE `invoicedetail`
(
    `invoiceDetailID` int PRIMARY KEY AUTO_INCREMENT,
    `invoiceID`       int            NOT NULL,
    `productID`       int            NOT NULL,
    `productUnitID`   int            NOT NULL,
    `batchID`         int            NOT NULL,
    `quantity`        int            NOT NULL,
    `unitName`        varchar(20)    NOT NULL,
    `baseQtyDeducted` int            NOT NULL,
    `unitSellPrice`   decimal(15, 2) NOT NULL,
    `subtotal`        decimal(15, 2) NOT NULL,
    `returnedQty`     int DEFAULT 0
);

CREATE TABLE `return`
(
    `returnID`          int PRIMARY KEY AUTO_INCREMENT,
    `returnCode`        varchar(50) UNIQUE NOT NULL,
    `invoiceID`         int,
    `purchaseID`        int,
    `returnedBy`        int                NOT NULL,
    `returnDate`        datetime           NOT NULL,
    `returnType`        varchar(50)        NOT NULL,
    `totalRefund`       decimal(15, 2)     NOT NULL,
    `offsetDebtAmount`  decimal(15, 2) DEFAULT 0,
    `shiftReportID`     int,
    `reason`            text               NOT NULL,
    `status`            varchar(50)        NOT NULL,
    `approvedAt`        datetime,
    `note`              text,
    `appliedRefundRate` decimal(5, 2)
);

CREATE TABLE `returndetail`
(
    `returnDetailID`    int PRIMARY KEY AUTO_INCREMENT,
    `returnID`          int            NOT NULL,
    `invoiceDetailID`   int,
    `purchaseDetailID`  int,
    `productID`         int            NOT NULL,
    `productUnitID`     int            NOT NULL,
    `batchID`           int            NOT NULL,
    `returnQty`         int            NOT NULL,
    `baseQtyRestored`   int            NOT NULL,
    `unitSellPrice`     decimal(15, 2) NOT NULL,
    `lineRefund`        decimal(15, 2) NOT NULL,
    `restockable`       boolean DEFAULT true,
    `originalLineValue` decimal(15, 2) NOT NULL
);

CREATE TABLE `income`
(
    `incomeID`               int PRIMARY KEY AUTO_INCREMENT,
    `incomeCode`             varchar(50) UNIQUE NOT NULL,
    `applicantID`            int                NOT NULL,
    `incomeType`             varchar(50)        NOT NULL,
    `invoiceID`              int,
    `returnID`               int,
    `shiftReportID`          int,
    `date`                   datetime           NOT NULL,
    `reason`                 text               NOT NULL,
    `amount`                 decimal(15, 2)     NOT NULL,
    `paidByCash`             decimal(15, 2)     NOT NULL,
    `paidByBanking`          decimal(15, 2)     NOT NULL,
    `paidByCredit`           decimal(15, 2)     NOT NULL,
    `supplierID`             int,
    `customerID`             int,
    `accountID`              int,
    `status`                 varchar(50)        NOT NULL,
    `note`                   text,
    `stockAdjustmentID`      int,
    `shiftReportOfAccountID` int
);

CREATE TABLE `expense`
(
    `expenseID`     int PRIMARY KEY AUTO_INCREMENT,
    `expenseCode`   varchar(50) UNIQUE NOT NULL,
    `applicantID`   int                NOT NULL,
    `expenseType`   varchar(50)        NOT NULL,
    `returnID`      int,
    `purchaseID`    int,
    `shiftReportID` int,
    `date`          datetime           NOT NULL,
    `reason`        text               NOT NULL,
    `amount`        decimal(15, 2)     NOT NULL,
    `paid`          decimal(15, 2)     NOT NULL,
    `paidByCash`    decimal(15, 2) DEFAULT 0,
    `paidByBanking` decimal(15, 2) DEFAULT 0,
    `paidByCredit`  decimal(15, 2) DEFAULT 0,
    `supplierID`    int,
    `customerID`    int,
    `accountID`     int,
    `status`        varchar(50)        NOT NULL,
    `approvedAt`    datetime,
    `note`          text
);

CREATE TABLE `supplier`
(
    `supplierID` int PRIMARY KEY AUTO_INCREMENT,
    `name`       varchar(255)       NOT NULL,
    `address`    text,
    `phone`      varchar(20) UNIQUE,
    `email`      varchar(100) UNIQUE,
    `taxCode`    varchar(20) UNIQUE NOT NULL
);

CREATE TABLE `purchaseinvoice`
(
    `purchaseID`          int PRIMARY KEY AUTO_INCREMENT,
    `purchaseInvoiceCode` varchar(50) UNIQUE NOT NULL,
    `date`                datetime           NOT NULL,
    `supplierID`          int,
    `employeeID`          int,
    `approvedAt`          datetime,
    `additionCost`        decimal(15, 2),
    `discount`            decimal(15, 2),
    `totalAmount`         decimal(15, 2),
    `procurementID`       int,
    `returnStatus`        varchar(50)        NOT NULL,
    `paid`                decimal(15, 2),
    `status`              varchar(50)        NOT NULL,
    `note`                text,
    `vatInvoiceNumber`    varchar(50)        NOT NULL,
    `vatInvoiceDate`      date               NOT NULL,
    `dueDate`             date,
    `version`             int                NOT NULL DEFAULT 0
);

CREATE TABLE `purchasedetail`
(
    `purchaseDetailID` int PRIMARY KEY AUTO_INCREMENT,
    `purchaseID`       int,
    `productID`        int,
    `quantity`         int            NOT NULL,
    `importPrice`      decimal(15, 2) NOT NULL,
    `productionDate`   date,
    `expirationDate`   date,
    `lotNumber`        varchar(50),
    `returnQty`        int            NOT NULL,
    `vatRate`          decimal(15, 2),
    `preTaxAmount`     decimal(15, 2),
    `vatAmount`        decimal(15, 2)
);

CREATE TABLE `customer`
(
    `customerID`        int PRIMARY KEY AUTO_INCREMENT,
    `customerType`      varchar(50)  NOT NULL,
    `name`              varchar(100) NOT NULL,
    `taxCode`           varchar(100) UNIQUE,
    `address`           varchar(100),
    `bankAccountNumber` varchar(100),
    `bankName`          varchar(100),
    `phoneNumber`       varchar(20) UNIQUE,
    `note`              text
);

CREATE TABLE `account`
(
    `accountID`   int PRIMARY KEY AUTO_INCREMENT,
    `name`        varchar(100) NOT NULL,
    `username`    varchar(50) UNIQUE,
    `password`    varchar(255),
    `status`      boolean DEFAULT true,
    `phoneNumber` varchar(20),
    `email`       varchar(100)
);

CREATE TABLE `accountpermission`
(
    `accountPermissionID` int PRIMARY KEY AUTO_INCREMENT,
    `accountID`           int,
    `role`                varchar(50)
);

CREATE TABLE `employeenote`
(
    `noteID`    int PRIMARY KEY AUTO_INCREMENT,
    `accountID` int,
    `date`      datetime NOT NULL,
    `content`   text     NOT NULL
);

CREATE TABLE `shiftreport`
(
    `shiftReportID`       int PRIMARY KEY AUTO_INCREMENT,
    `shiftReportCode`     varchar(50) UNIQUE NOT NULL,
    `cashierID`           int                NOT NULL,
    `shiftDate`           date               NOT NULL,
    `shiftType`           varchar(20)        NOT NULL,
    `startTime`           datetime           NOT NULL,
    `endTime`             datetime,
    `openingCash`         decimal(15, 2)     NOT NULL,
    `totalInvoices`       int            DEFAULT 0,
    `totalRevenue`        decimal(15, 2) DEFAULT 0,
    `totalReturns`        int            DEFAULT 0,
    `totalReturnAmount`   decimal(15, 2) DEFAULT 0,
    `totalDebtCollected`  decimal(15, 2) DEFAULT 0,
    `totalCashIn`         decimal(15, 2) DEFAULT 0,
    `totalBankingIn`      decimal(15, 2) DEFAULT 0,
    `totalCashOut`        decimal(15, 2) DEFAULT 0,
    `totalBankingOut`     decimal(15, 2) DEFAULT 0,
    `expectedClosingCash` decimal(15, 2),
    `actualClosingCash`   decimal(15, 2),
    `cashDiscrepancy`     decimal(15, 2),
    `noteDiscrepancy`     text,
    `status`              varchar(50)        NOT NULL,
    `approvedAt`          datetime,
    `note`                text,
    `createdAt`           datetime           NOT NULL
);

CREATE TABLE `supplierproduct`
(
    `supplierProductID` int PRIMARY KEY AUTO_INCREMENT,
    `supplierID`        int NOT NULL,
    `productID`         int NOT NULL,
    `costPrice`         decimal(15, 2),
    `isPreferred`       boolean DEFAULT false,
    `isActive`          boolean DEFAULT true,
    `note`              text
);

CREATE TABLE `procurementplan`
(
    `procurementID`   int PRIMARY KEY AUTO_INCREMENT,
    `procurementCode` varchar(50) UNIQUE NOT NULL,
    `date`            datetime           NOT NULL,
    `status`          varchar(50)        NOT NULL,
    `note`            text,
    `createdAt`       datetime           NOT NULL
);

CREATE TABLE `procurementplandetail`
(
    `procurementDetailID` int PRIMARY KEY AUTO_INCREMENT,
    `procurementID`       int,
    `productID`           int NOT NULL,
    `requestedQuantity`   int NOT NULL,
    `unit`                varchar(20),
    `estimatedPrice`      decimal(15, 2),
    `supplierID`          int,
    `currentStock`        int
);

CREATE TABLE `notification`
(
    `notificationID`   int PRIMARY KEY AUTO_INCREMENT,
    `accountID`        int,
    `message`          text         NOT NULL,
    `createdAt`        datetime     NOT NULL,
    `title`            varchar(255) NOT NULL,
    `notificationType` varchar(50)  NOT NULL,
    `category`         varchar(50)  NOT NULL,
    `severity`         varchar(20)  NOT NULL,
    `status`           varchar(20)  NOT NULL,
    `targetRole`       varchar(50)  NOT NULL,
    `referenceType`    varchar(50),
    `referenceId`      int,
    `actionUrl`        varchar(255),
    `isRead`           boolean      NOT NULL,
    `readAt`           datetime,
    `resolvedAt`       datetime,
    `expiresAt`        datetime,
    `dedupeKey`        varchar(255),
    `isActive`         boolean
);

CREATE TABLE `stockreview`
(
    `stockReviewID`   int PRIMARY KEY AUTO_INCREMENT,
    `stockReviewCode` varchar(50) UNIQUE NOT NULL,
    `type`            varchar(50)        NOT NULL,
    `reviewDate`      datetime           NOT NULL,
    `createdBy`       int,
    `approvedBy`      int,
    `approvedAt`      datetime,
    `status`          varchar(50)        NOT NULL,
    `note`            text
);

CREATE TABLE `stockreviewdetail`
(
    `stockReviewDetailID`    int PRIMARY KEY AUTO_INCREMENT,
    `stockReviewID`          int,
    `productID`              int,
    `batchID`                int,
    `systemQty`              int NOT NULL,
    `actualQty`              int NOT NULL,
    `discrepancy`            int,
    `recordedExpirationDate` date,
    `actualExpirationDate`   date,
    `conditionStatus`        varchar(50),
    `note`                   text
);

CREATE TABLE `stockadjustment`
(
    `stockAdjustmentID`   int PRIMARY KEY AUTO_INCREMENT,
    `stockAdjustmentCode` varchar(50) UNIQUE NOT NULL,
    `adjustmentType`      varchar(50)        NOT NULL,
    `date`                datetime           NOT NULL,
    `reason`              text               NOT NULL,
    `stockReviewID`       int,
    `status`              varchar(50)        NOT NULL,
    `note`                text
);

CREATE TABLE `stockadjustmentdetail`
(
    `stockAdjustmentDetailID` int PRIMARY KEY AUTO_INCREMENT,
    `stockAdjustmentID`       int            NOT NULL,
    `productID`               int            NOT NULL,
    `productUnitID`           int            NOT NULL,
    `batchID`                 int            NOT NULL,
    `direction`               varchar(50)    NOT NULL,
    `quantity`                int            NOT NULL,
    `baseQtyDeducted`         int            NOT NULL,
    `unitCostPrice`           decimal(15, 2) NOT NULL,
    `lineCost`                decimal(15, 2) NOT NULL,
    `note`                    text,
    `refSellPrice`            decimal(15, 2),
    `oldExpirationDate`       date,
    `newExpirationDate`       date
);

CREATE TABLE `taxperiodsnapshot`
(
    `taxPeriodSnapshotID` int PRIMARY KEY AUTO_INCREMENT,
    `periodLabel`         varchar(10),
    `periodTaxType`       int,
    `startDate`           date,
    `endDate`             date,
    `incomeTax`           decimal(15, 2),
    `vatOutput`           decimal(15, 2),
    `quarterlyRevenue`    decimal(15, 2),
    `recordedAt`          datetime,
    `note`                text
);

CREATE UNIQUE INDEX `productunit_index_0` ON `productunit` (`productID`, `unitName`);

CREATE UNIQUE INDEX `supplierproduct_index_1` ON `supplierproduct` (`supplierID`, `productID`);

ALTER TABLE `product`
    ADD FOREIGN KEY (`typeID`) REFERENCES `type` (`typeID`);

ALTER TABLE `product`
    ADD FOREIGN KEY (`producerID`) REFERENCES `producer` (`producerID`);

ALTER TABLE `productunit`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `batch`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `batch`
    ADD FOREIGN KEY (`purchaseDetailID`) REFERENCES `purchasedetail` (`purchaseDetailID`);

ALTER TABLE `batch`
    ADD FOREIGN KEY (`importUnitID`) REFERENCES `productunit` (`productUnitID`);

ALTER TABLE `combocomponent`
    ADD FOREIGN KEY (`comboID`) REFERENCES `product` (`productID`);

ALTER TABLE `combocomponent`
    ADD FOREIGN KEY (`componentProductID`) REFERENCES `product` (`productID`);

ALTER TABLE `combocomponent`
    ADD FOREIGN KEY (`componentUnitID`) REFERENCES `productunit` (`productUnitID`);

ALTER TABLE `medicineapi`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `position`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `invoice`
    ADD FOREIGN KEY (`employeeID`) REFERENCES `account` (`accountID`);

ALTER TABLE `invoice`
    ADD FOREIGN KEY (`customerID`) REFERENCES `customer` (`customerID`);

ALTER TABLE `invoice`
    ADD FOREIGN KEY (`originalInvoiceID`) REFERENCES `invoice` (`invoiceID`);

ALTER TABLE `invoice`
    ADD FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`);

ALTER TABLE `invoice`
    ADD FOREIGN KEY (`shiftReportID`) REFERENCES `shiftreport` (`shiftReportID`);

ALTER TABLE `invoicedetail`
    ADD FOREIGN KEY (`invoiceID`) REFERENCES `invoice` (`invoiceID`);

ALTER TABLE `invoicedetail`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `invoicedetail`
    ADD FOREIGN KEY (`productUnitID`) REFERENCES `productunit` (`productUnitID`);

ALTER TABLE `invoicedetail`
    ADD FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`);

ALTER TABLE `return`
    ADD FOREIGN KEY (`invoiceID`) REFERENCES `invoice` (`invoiceID`);

ALTER TABLE `return`
    ADD FOREIGN KEY (`purchaseID`) REFERENCES `purchaseinvoice` (`purchaseID`);

ALTER TABLE `return`
    ADD FOREIGN KEY (`returnedBy`) REFERENCES `account` (`accountID`);

ALTER TABLE `return`
    ADD FOREIGN KEY (`shiftReportID`) REFERENCES `shiftreport` (`shiftReportID`);

ALTER TABLE `returndetail`
    ADD FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`);

ALTER TABLE `returndetail`
    ADD FOREIGN KEY (`invoiceDetailID`) REFERENCES `invoicedetail` (`invoiceDetailID`);

ALTER TABLE `returndetail`
    ADD FOREIGN KEY (`purchaseDetailID`) REFERENCES `purchasedetail` (`purchaseDetailID`);

ALTER TABLE `returndetail`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `returndetail`
    ADD FOREIGN KEY (`productUnitID`) REFERENCES `productunit` (`productUnitID`);

ALTER TABLE `returndetail`
    ADD FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`applicantID`) REFERENCES `account` (`accountID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`invoiceID`) REFERENCES `invoice` (`invoiceID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`shiftReportID`) REFERENCES `shiftreport` (`shiftReportID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`customerID`) REFERENCES `customer` (`customerID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`stockAdjustmentID`) REFERENCES `stockadjustment` (`stockAdjustmentID`);

ALTER TABLE `income`
    ADD FOREIGN KEY (`shiftReportOfAccountID`) REFERENCES `shiftreport` (`shiftReportID`);

ALTER TABLE `expense`
    ADD FOREIGN KEY (`applicantID`) REFERENCES `account` (`accountID`);

ALTER TABLE `expense`
    ADD FOREIGN KEY (`returnID`) REFERENCES `return` (`returnID`);

ALTER TABLE `expense`
    ADD FOREIGN KEY (`purchaseID`) REFERENCES `purchaseinvoice` (`purchaseID`);

ALTER TABLE `expense`
    ADD FOREIGN KEY (`shiftReportID`) REFERENCES `shiftreport` (`shiftReportID`);

ALTER TABLE `expense`
    ADD FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`);

ALTER TABLE `expense`
    ADD FOREIGN KEY (`customerID`) REFERENCES `customer` (`customerID`);

ALTER TABLE `expense`
    ADD FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`);

ALTER TABLE `purchaseinvoice`
    ADD FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`);

ALTER TABLE `purchaseinvoice`
    ADD FOREIGN KEY (`employeeID`) REFERENCES `account` (`accountID`);

ALTER TABLE `purchaseinvoice`
    ADD FOREIGN KEY (`procurementID`) REFERENCES `procurementplan` (`procurementID`);

ALTER TABLE `purchasedetail`
    ADD FOREIGN KEY (`purchaseID`) REFERENCES `purchaseinvoice` (`purchaseID`);

ALTER TABLE `purchasedetail`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `accountpermission`
    ADD FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`);

ALTER TABLE `employeenote`
    ADD FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`);

ALTER TABLE `shiftreport`
    ADD FOREIGN KEY (`cashierID`) REFERENCES `account` (`accountID`);

ALTER TABLE `supplierproduct`
    ADD FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`);

ALTER TABLE `supplierproduct`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `procurementplandetail`
    ADD FOREIGN KEY (`procurementID`) REFERENCES `procurementplan` (`procurementID`);

ALTER TABLE `procurementplandetail`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `procurementplandetail`
    ADD FOREIGN KEY (`supplierID`) REFERENCES `supplier` (`supplierID`);

ALTER TABLE `notification`
    ADD FOREIGN KEY (`accountID`) REFERENCES `account` (`accountID`);

ALTER TABLE `stockreview`
    ADD FOREIGN KEY (`createdBy`) REFERENCES `account` (`accountID`);

ALTER TABLE `stockreview`
    ADD FOREIGN KEY (`approvedBy`) REFERENCES `account` (`accountID`);

ALTER TABLE `stockreviewdetail`
    ADD FOREIGN KEY (`stockReviewID`) REFERENCES `stockreview` (`stockReviewID`);

ALTER TABLE `stockreviewdetail`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `stockreviewdetail`
    ADD FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`);

ALTER TABLE `stockadjustment`
    ADD FOREIGN KEY (`stockReviewID`) REFERENCES `stockreview` (`stockReviewID`);

ALTER TABLE `stockadjustmentdetail`
    ADD FOREIGN KEY (`stockAdjustmentID`) REFERENCES `stockadjustment` (`stockAdjustmentID`);

ALTER TABLE `stockadjustmentdetail`
    ADD FOREIGN KEY (`productID`) REFERENCES `product` (`productID`);

ALTER TABLE `stockadjustmentdetail`
    ADD FOREIGN KEY (`productUnitID`) REFERENCES `productunit` (`productUnitID`);

ALTER TABLE `stockadjustmentdetail`
    ADD FOREIGN KEY (`batchID`) REFERENCES `batch` (`batchID`);
