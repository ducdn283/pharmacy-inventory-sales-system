INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 1, 1, 1, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 1
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 2, 1, 2, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 2
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 3, 1, 3, NULL, 0, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 3
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 4, 2, 3, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 4
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 5, 2, 4, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 5
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 6, 2, 5, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 6
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 7, 3, 5, NULL, 0, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 7
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 8, 3, 6, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 8
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 9, 3, 7, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 9
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 10, 4, 8, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 10
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 11, 4, 9, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 11
);

INSERT INTO supplier_product (supplierProductID, supplierID, productID, costPrice, isPreferred, isActive, note)
SELECT 12, 4, 10, NULL, 1, 1, NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM supplier_product
    WHERE supplierProductID = 12
);

ALTER TABLE supplier_product AUTO_INCREMENT = 13;
