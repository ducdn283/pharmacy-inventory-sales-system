INSERT INTO position (positionID, productID, name)
SELECT 1, 1, 'A1'
WHERE NOT EXISTS (SELECT 1 FROM position WHERE positionID = 1);

INSERT INTO position (positionID, productID, name)
SELECT 2, 2, 'B1'
WHERE NOT EXISTS (SELECT 1 FROM position WHERE positionID = 2);

ALTER TABLE position AUTO_INCREMENT = 3;
