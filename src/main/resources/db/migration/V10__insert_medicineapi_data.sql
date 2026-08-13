INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 1, 1, 'Ampicillin', '500mg'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 1
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 2, 5, 'Mebendazol', '500mg'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 2
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 3, 6, 'N-Acetyl-DL-Leucin', '500mg'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 3
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 4, 14, 'Hoàng liên', '1.35g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 4
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 5, 14, 'Mộc hương', '1.2g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 5
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 6, 14, 'Bạch truật', '0.9g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 6
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 7, 14, 'Bạch thược', '0.9g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 7
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 8, 14, 'Ngũ bội tử', '0.9g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 8
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 9, 14, 'Hậu phác', '0.6g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 9
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 10, 14, 'Cam thảo', '0.45g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 10
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 11, 14, 'Xa tiền tử', '0.45g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 11
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 12, 14, 'Hoạt thạch', '0.15g'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 12
);

INSERT INTO medicineapi (medicineAPIID, productID, apiName, strength)
SELECT 13, 17, 'Tamoxifen', '20mg'
WHERE NOT EXISTS (
    SELECT 1
    FROM medicineapi
    WHERE medicineAPIID = 13
);

ALTER TABLE medicineapi AUTO_INCREMENT = 14;
