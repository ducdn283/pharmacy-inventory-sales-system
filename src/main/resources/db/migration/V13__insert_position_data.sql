INSERT INTO position (positionID, productID, name)
SELECT 1, 1, 'A1'
WHERE NOT EXISTS (SELECT 1 FROM position WHERE positionID = 1);

INSERT INTO position (positionID, productID, name)
SELECT 2, 1, 'A2'
WHERE NOT EXISTS (SELECT 1 FROM position WHERE positionID = 2);

INSERT INTO position (positionID, productID, name)
SELECT 3, 2, 'B1'
WHERE NOT EXISTS (SELECT 1 FROM position WHERE positionID = 3);

INSERT INTO position (positionID, productID, name)
SELECT 4, 2, 'B2'
WHERE NOT EXISTS (SELECT 1 FROM position WHERE positionID = 4);

ALTER TABLE position AUTO_INCREMENT = 5;
