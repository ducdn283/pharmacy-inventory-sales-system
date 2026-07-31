package com.example.project.controller;

import com.example.project.security.AccountPrincipal;
import com.example.project.service.AccountPasswordService;
import com.example.project.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class AuthController {
    private static final String RESET_RESPONSE_MESSAGE = "Nếu email tồn tại trong hệ thống, liên kết đặt lại mật khẩu sẽ được gửi vào email đó.";

    private final AccountPasswordService accountPasswordService;
    private final PasswordResetService passwordResetService;

    public AuthController(
            AccountPasswordService accountPasswordService,
            PasswordResetService passwordResetService) {
        this.accountPasswordService = accountPasswordService;
        this.passwordResetService = passwordResetService;
    }

    @GetMapping("/signin")
    public String signin() {
        return "signin";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestPasswordReset(
            @RequestParam(name = "email", required = false) String email,
            HttpServletRequest request,
            Model model) {
        passwordResetService.requestReset(email, resetBaseUrl(request));
        model.addAttribute("message", RESET_RESPONSE_MESSAGE);
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam(name = "token", required = false) String token, Model model) {
        if (!passwordResetService.isTokenValid(token)) {
            model.addAttribute("error", "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.");
            return "reset-password";
        }
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(
            @RequestParam(name = "token", required = false) String token,
            @RequestParam(name = "newPassword", required = false) String newPassword,
            @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
            Model model,
            RedirectAttributes redirectAttributes) {
        try {
            passwordResetService.resetPassword(token, newPassword, confirmPassword);
            redirectAttributes.addAttribute("resetSuccess", "");
            return "redirect:/signin";
        } catch (IllegalArgumentException exception) {
            model.addAttribute("token", token);
            model.addAttribute("error", toVietnamesePasswordMessage(exception.getMessage()));
            return "reset-password";
        }
    }

    @GetMapping("/change-password")
    public String changePassword() {
        return "redirect:/profile?section=password";
    }

    @PostMapping("/change-password")
    public String changePassword(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(name = "currentPassword", required = false) String currentPassword,
            @RequestParam(name = "newPassword", required = false) String newPassword,
            @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
            RedirectAttributes redirectAttributes) {
        try {
            accountPasswordService.changePassword(principal.getAccountId(), currentPassword, newPassword, confirmPassword);
            return "redirect:/profile?passwordChanged";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("passwordError", toVietnamesePasswordMessage(exception.getMessage()));
            return "redirect:/profile?section=password";
        }
    }

    private String toVietnamesePasswordMessage(String message) {
        return switch (message) {
            case "Current password is required." -> "Vui lòng nhập mật khẩu hiện tại.";
            case "Current password is incorrect." -> "Mật khẩu hiện tại không chính xác.";
            case "New password is required." -> "Vui lòng nhập mật khẩu mới.";
            case "New password must be at least 8 characters." -> "Mật khẩu mới phải có ít nhất 8 ký tự.";
            case "New password must contain both letters and digits." ->
                    "Mật khẩu mới phải có cả chữ và số.";
            case "Password confirmation is required." -> "Vui lòng xác nhận mật khẩu mới.";
            case "Password confirmation does not match." -> "Mật khẩu xác nhận không trùng khớp.";
            case "Password reset link is invalid or expired." -> "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.";
            default -> "Không thể cập nhật mật khẩu. Vui lòng kiểm tra lại thông tin.";
        };
    }

    private String resetBaseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath() + "/reset-password")
                .replaceQuery(null)
                .build()
                .toUriString();
    }
}
