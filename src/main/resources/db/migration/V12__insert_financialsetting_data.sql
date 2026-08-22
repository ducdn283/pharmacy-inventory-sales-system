INSERT INTO financial_setting (
    financialSettingID,
    taxCalculationMethod,
    returnProductOnInvoiceValueRate,
    autoGenerateVATInvoice,
    vatInvoiceSeries,
    openingCashDefault,
    taxCode,
    locationCode,
    locationName,
    address,
    phoneNumber,
    email,
    bankAccountNumber,
    bankName,
    revenueGroup
)
SELECT
    1,
    1,
    80.00,
    0,
    'YY',
    1000000.00,
    '022176001896',
    '00999',
    'HỘ KINH DOANH NHÀ THUỐC HẰNG NGỌC',
    'Số 172 Phố Tuệ Tĩnh, Phường Uông Bí, Tỉnh Quảng Ninh, Việt Nam',
    '0983276660',
    'nhathuochangngoc1976@gmail.com',
    '123456789',
    'vietcombank',
    1
WHERE NOT EXISTS (
    SELECT 1
    FROM financial_setting
    WHERE financialSettingID = 1
);

ALTER TABLE financial_setting AUTO_INCREMENT = 2;
