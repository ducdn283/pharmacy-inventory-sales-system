package com.example.project.service;

import com.example.project.entity.Account;
import com.example.project.repository.AccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountPasswordService {

    /** Cũng là giá trị đặt cho minlength của ô nhập ở màn đổi / đặt lại mật khẩu. */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountPasswordService(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void changePassword(Integer accountId, String currentPassword, String newPassword, String confirmPassword) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Current password is required.");
        }

        if (account.getPassword() == null || account.getPassword().isBlank()
                || !passwordEncoder.matches(currentPassword, account.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }

        validateNewPassword(newPassword, confirmPassword);
        account.setPassword(passwordEncoder.encode(newPassword));
        accountRepository.save(account);
    }

    /**
     * Luật mật khẩu dùng chung cho CẢ đổi mật khẩu lẫn đặt lại qua email — một chỗ duy nhất để hai
     * luồng không bao giờ lệch luật:
     * {@value #MIN_PASSWORD_LENGTH} ký tự + có cả chữ và số; không bắt chữ hoa hay ký tự đặc biệt.
     */
    public void validateNewPassword(String newPassword, String confirmPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password is required.");
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("New password must be at least 8 characters.");
        }
        if (!hasLetterAndDigit(newPassword)) {
            throw new IllegalArgumentException("New password must contain both letters and digits.");
        }
        if (confirmPassword == null || confirmPassword.isBlank()) {
            throw new IllegalArgumentException("Password confirmation is required.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("Password confirmation does not match.");
        }
    }

    /** Duyệt từng ký tự thay vì regex để không phụ thuộc bảng chữ cái: 'à', 'ñ' vẫn tính là chữ. */
    private boolean hasLetterAndDigit(String password) {
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) letter = true;
            else if (Character.isDigit(c)) digit = true;
            if (letter && digit) return true;
        }
        return false;
    }
}
