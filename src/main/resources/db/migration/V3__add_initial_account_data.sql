-- Initial staff accounts for login usage until account management is implemented.
-- IDs are inserted explicitly because account.accountID and accountpermission.accountPermissionID
-- are not auto-generated in the confirmed database schema.

SET @initial_password_hash = '$2a$10$ukNGSf9ibpOpPipclZUYm.Qkh.yKW23vrOXoxR37KV7D3zw23WDZq';

INSERT INTO account (accountID, name, username, password, status, phoneNumber, email)
VALUES
    (1, 'Trần Nguyên Ngọc', 'ngoctn01', @initial_password_hash, 1, NULL, 'hangngocub@gmail.com'),
    (2, 'Vũ Thị Hằng', 'hangvt02', @initial_password_hash, 1, '0983276660', 'vuthihang01@gmail.com'),
    (3, 'Trần Anh Vũ', 'vuta03', @initial_password_hash, 1, '0329377669', 'vuta03@gmail.com'),
    (4, 'Vũ Cường Thịnh', 'thinhvc04', @initial_password_hash, 1, '0922553838', 'thinhvc04@gmail.com'),
    (5, 'Nguyễn Việt Hoàng', 'hoangnv05', @initial_password_hash, 1, '0866767746', 'hoangnv05@gmail.com'),
    (6, 'Nguyễn Đăng Trường', 'truongnd06', @initial_password_hash, 1, '0349618003', 'truongnd06@gmail.com'),
    (7, 'Đỗ Ngọc Đức', 'ducdn07', @initial_password_hash, 1, '0966563122', 'ducdn07@gmail.com')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    username = VALUES(username),
    password = VALUES(password),
    status = VALUES(status),
    phoneNumber = VALUES(phoneNumber),
    email = VALUES(email);

INSERT INTO account_permission (accountPermissionID, accountID, role)
VALUES
    (1, 1, 'OWNER'),
    (3, 2, 'PHARMACIST'),
    (5, 3, 'PHARMACIST'),
    (7, 4, 'ACCOUNTANT'),
    (8, 5, 'ACCOUNTANT'),
    (9, 6, 'PHARMACIST'),
    (10, 7, 'PHARMACIST')
ON DUPLICATE KEY UPDATE
    accountID = VALUES(accountID),
    role = VALUES(role);
