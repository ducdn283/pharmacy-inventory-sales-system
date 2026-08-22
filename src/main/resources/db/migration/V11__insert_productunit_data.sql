INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 1, 1, 'Viên', 1.0000, 500.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 1);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 2, 1, 'Vỉ', 10.0000, 5000.00, 0, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 2);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 3, 1, 'Hộp', 100.0000, 50000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 3);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 4, 2, 'Ống', 1.0000, 1000.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 4);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 5, 2, 'Vỉ', 5.0000, 5000.00, 0, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 5);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 6, 2, 'Hộp', 30.0000, 30000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 6);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 7, 3, 'Cái', 1.0000, 940000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 7);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 8, 4, 'Hộp', 1.0000, 317000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 8);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 9, 5, 'Viên', 1.0000, 8000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 9);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 10, 6, 'Viên', 1.0000, 1000.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 10);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 11, 6, 'Vỉ', 10.0000, 10000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 11);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 12, 6, 'Hộp', 100.0000, 100000.00, 0, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 12);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 13, 7, 'Hộp', 1.0000, 730000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 13);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 14, 8, 'Cái', 1.0000, 19300.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 14);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 15, 8, 'Hộp', 20.0000, 386000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 15);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 16, 9, 'Cái', 1.0000, 320.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 16);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 17, 9, 'Hộp', 100.0000, 32000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 17);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 18, 10, 'Tuýp', 1.0000, 15500.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 18);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 19, 11, 'Hộp', 1.0000, 410000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 19);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 20, 12, 'Hộp', 1.0000, 99500.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 20);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 21, 13, 'Hộp', 1.0000, 264000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 21);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 22, 14, 'Viên', 1.0000, 3500.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 22);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 23, 14, 'Vỉ', 10.0000, 35000.00, 0, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 23);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 24, 14, 'Hộp', 30.0000, 105000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 24);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 25, 15, 'Cái', 1.0000, 990000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 25);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 26, 16, 'Cái', 1.0000, 1200000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 26);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 27, 17, 'Viên', 1.0000, 5000.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 27);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 28, 17, 'Vỉ', 10.0000, 50000.00, 0, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 28);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 29, 17, 'Hộp', 30.0000, 150000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 29);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 30, 18, 'Viên', 1.0000, 900.00, 0, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 30);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 31, 18, 'Vỉ', 10.0000, 9000.00, 0, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 31);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 32, 18, 'Hộp', 30.0000, 27000.00, 1, 0, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 32);

INSERT INTO product_unit (productUnitID, productID, unitName, ratio, sellPrice, isDefault, isBaseUnit, isActive, note)
SELECT 33, 19, 'Hộp', 1.0000, 300000.00, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM product_unit WHERE productUnitID = 33);

ALTER TABLE product_unit AUTO_INCREMENT = 34;
