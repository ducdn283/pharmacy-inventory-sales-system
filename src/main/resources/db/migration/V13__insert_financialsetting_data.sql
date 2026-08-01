INSERT INTO financialsetting (
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
    revenueGroup,
    annualRevenueThreshold1,
    annualRevenueThreshold2
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
    1,
    1000000000.00,
    3000000000.00
WHERE NOT EXISTS (
    SELECT 1
    FROM financialsetting
    WHERE financialSettingID = 1
);

ALTER TABLE financialsetting AUTO_INCREMENT = 2;
