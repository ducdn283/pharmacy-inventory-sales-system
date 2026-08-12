INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 1, 'thuốc', 'Thuốc kê đơn', 5.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'thuốc'
      AND name = 'Thuốc kê đơn'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 2, 'thuốc', 'Thuốc không kê đơn', 5.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'thuốc'
      AND name = 'Thuốc không kê đơn'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 3, 'thuốc', 'Thuốc chưa phân loại', 5.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'thuốc'
      AND name = 'Thuốc chưa phân loại'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 4, 'hàng hóa', 'Thực phẩm chức năng', 8.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'hàng hóa'
      AND name = 'Thực phẩm chức năng'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 5, 'hàng hóa', 'Thực phẩm dinh dưỡng', 8.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'hàng hóa'
      AND name = 'Thực phẩm dinh dưỡng'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 6, 'hàng hóa', 'Mỹ phẩm', 8.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'hàng hóa'
      AND name = 'Mỹ phẩm'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 7, 'thiết bị y tế', 'Thiết bị y tế (máy)', 5.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'thiết bị y tế'
      AND name = 'Thiết bị y tế (máy)'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 8, 'thiết bị y tế', 'Thiết bị y tế (có hạn)', 5.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'thiết bị y tế'
      AND name = 'Thiết bị y tế (có hạn)'
);

INSERT INTO type (typeID, sortType, name, defaultVATRate)
SELECT 9, 'thiết bị y tế', 'Thiết bị y tế (không hạn)', 5.00
WHERE NOT EXISTS (
    SELECT 1
    FROM type
    WHERE sortType = 'thiết bị y tế'
      AND name = 'Thiết bị y tế (không hạn)'
);

ALTER TABLE type AUTO_INCREMENT = 11;
