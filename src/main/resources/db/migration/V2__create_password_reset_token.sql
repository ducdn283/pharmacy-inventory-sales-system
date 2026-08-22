CREATE TABLE password_reset_token (
    tokenID INT NOT NULL AUTO_INCREMENT,
    accountID INT NOT NULL,
    tokenHash VARCHAR(64) NOT NULL,
    expiresAt DATETIME NOT NULL,
    usedAt DATETIME NULL,
    createdAt DATETIME NOT NULL,
    PRIMARY KEY (tokenID),
    UNIQUE KEY uk_password_reset_token_tokenHash (tokenHash),
    KEY idx_password_reset_token_accountID (accountID),
    CONSTRAINT fk_password_reset_token_account
        FOREIGN KEY (accountID) REFERENCES account (accountID)
);
