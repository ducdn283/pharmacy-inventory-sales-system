INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Ampicillin MKP 500', 'SP5122419', NULL, 1, 200, 0, 6, 'Việt Nam', '893110402724', 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122419'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'ỐNG UỐNG CANXI - D3 - K2', 'SP5122420', NULL, 4, 200, 0, 7, 'Việt Nam', '1399/2023/ĐKSP', 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122420'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Omron HEM 7120', 'SP5122421', NULL, 7, 10, 0, 8, 'Việt Nam', NULL, 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122421'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Kids Smart Liquid Zinc Nature''s Way', 'SP5122422', NULL, 5, 20, 0, 9, 'Australia', '4378/2024/ĐKSP', 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122422'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Fucagi 500mg Agimexpharm', 'SP5122423', NULL, 2, 50, 0, 10, 'Việt Nam', '893100431024', 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122423'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Gikanin 500mg Khapharco', 'SP5122424', NULL, 2, 200, 0, 11, 'Việt Nam', 'VD-22909-15', 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122424'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'OMEGA 3 PLUS Kenko', 'SP5122425', NULL, 4, 25, 0, 12, 'Nhật Bản', '2001/2024/ĐKSP', 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122425'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Bifina R Health Aid', 'SP5122426', NULL, 5, 200, 0, 13, 'Nhật Bản', '7026/2019/ĐKSP', 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122426'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Lancets Medicleen BL-28', 'SP5122427', NULL, 8, 1000, 0, 14, 'Trung Quốc', NULL, 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122427'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Fixderma Scar Gel', 'SP5122428', NULL, 6, 20, 0, 15, 'Ấn Độ', NULL, 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122428'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Immuvita Easylife', 'SP5122429', NULL, 5, 15, 0, 16, 'Đức', '638/2023/ĐKSP', 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122429'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Blood Care Jpanwell', 'SP5122430', NULL, 4, 15, 0, 17, 'Nhật Bản', '6378/2021/ĐKSP', 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122430'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Omega 3 Power DAO Nordic Health', 'SP5122431', NULL, 4, 15, 0, 18, 'Đan Mạch', '8455/2023/ĐKSP', 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122431'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'COMBO: 11-12-13', 'SP5122432', NULL, 10, 0, 0, NULL, NULL, NULL, 1, 0.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122432'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Thuốc Đại Tràng Trường Phúc', 'SP5122433', NULL, 2, 200, 0, 19, 'Việt Nam', 'VD-32592-19', 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122433'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Fuji PG-2507', 'SP5122434', NULL, 7, 10, 0, 20, 'Trung Quốc', NULL, 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122434'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Bonbone size M (68-85cm) Pro Hard Slim', 'SP5122435', NULL, 9, 10, 0, 21, 'Nhật Bản', NULL, 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122435'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Nolvadex-D 20mg', 'SP5122436', NULL, 1, 300, 0, 3, 'Nhật Bản', 'VN-19007-15', 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122436'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Caldihasan 1250mg/125IU Hasan', 'SP5122437', NULL, 2, 300, 0, 22, 'Việt Nam', 'VD-20539-14', 1, 5.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122437'
);

INSERT INTO product (name, code, barcode, typeID, maxStock, minStock, producerID, origin, registrationNumber, status, vatRateOverride, note)
SELECT 'Mashiro Tooth Powder Zakuro Mint Flavor 30g', 'SP5122438', NULL, 6, 15, 0, 23, 'Nhật Bản', NULL, 1, 8.00, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM product
    WHERE code = 'SP5122438'
);
