package com.example.project.controller;

import com.example.project.dto.request.ProfileUpdateRequest;
import com.example.project.dto.response.ProfileViewResponse;
import com.example.project.entity.Account;
import com.example.project.repository.AccountRepository;
import com.example.project.security.AccountPrincipal;
import com.example.project.service.EmployeenoteService;
import com.example.project.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final AccountRepository accountRepository;
    private final EmployeenoteService employeenoteService;

    public ProfileController(
            ProfileService profileService,
            AccountRepository accountRepository,
            EmployeenoteService employeenoteService
    ) {
        this.profileService = profileService;
        this.accountRepository = accountRepository;
        this.employeenoteService = employeenoteService;
    }

    @GetMapping
    public String viewProfile(
            Model model,
            Authentication authentication
    ) {
        Account currentAccount =
                getCurrentAccount(authentication);

        addProfilePageData(
                model,
                currentAccount
        );

        return "profile";
    }

    @PostMapping
    public String updateProfile(
            @Valid
            @ModelAttribute("profileForm")
            ProfileUpdateRequest form,

            BindingResult bindingResult,
            Model model,
            Authentication authentication
    ) {
        Account currentAccount =
                getCurrentAccount(authentication);

        if (bindingResult.hasErrors()) {
            ProfileViewResponse profile =
                    profileService.getProfile(
                            currentAccount.getId()
                    );

            model.addAttribute("profile", profile);

            model.addAttribute(
                    "employeeNotes",
                    employeenoteService.getByAccountId(
                            currentAccount.getId()
                    )
            );

            model.addAttribute(
                    "pageTitle",
                    "Hồ sơ cá nhân"
            );

            return "profile";
        }

        profileService.updateProfile(
                currentAccount.getId(),
                form
        );

        refreshCurrentPrincipal(authentication);

        return "redirect:/profile?success";
    }

    private void addProfilePageData(
            Model model,
            Account currentAccount
    ) {
        ProfileViewResponse profile =
                profileService.getProfile(
                        currentAccount.getId()
                );

        ProfileUpdateRequest form =
                new ProfileUpdateRequest();

        form.setName(profile.getName());
        form.setEmail(profile.getEmail());
        form.setPhoneNumber(profile.getPhoneNumber());

        model.addAttribute("profile", profile);
        model.addAttribute("profileForm", form);

        /*
         * Chỉ lấy note của chính tài khoản đang đăng nhập.
         */
        model.addAttribute(
                "employeeNotes",
                employeenoteService.getByAccountId(
                        currentAccount.getId()
                )
        );

        model.addAttribute(
                "pageTitle",
                "Hồ sơ cá nhân"
        );
    }

    private Account getCurrentAccount(
            Authentication authentication
    ) {
        if (
                authentication == null
                        || !authentication.isAuthenticated()
                        || "anonymousUser".equals(
                        authentication.getName()
                )
        ) {
            throw new RuntimeException(
                    "Người dùng chưa đăng nhập"
            );
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof AccountPrincipal accountPrincipal) {
            return accountRepository
                    .findById(accountPrincipal.getAccountId())
                    .orElseThrow(() ->
                            new RuntimeException(
                                    "Không tìm thấy tài khoản đang đăng nhập"
                            )
                    );
        }

        String loginValue = authentication.getName();

        return accountRepository
                .findByUsernameOrEmail(
                        loginValue,
                        loginValue
                )
                .orElseThrow(() ->
                        new RuntimeException(
                                "Không tìm thấy tài khoản đang đăng nhập: "
                                        + loginValue
                        )
                );
    }

    private void refreshCurrentPrincipal(
            Authentication authentication
    ) {
        if (
                authentication == null
                        || !(authentication.getPrincipal()
                        instanceof AccountPrincipal oldPrincipal)
        ) {
            return;
        }

        Account updatedAccount =
                accountRepository
                        .findById(oldPrincipal.getAccountId())
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Không tìm thấy tài khoản đang đăng nhập"
                                )
                        );

        AccountPrincipal updatedPrincipal =
                new AccountPrincipal(
                        updatedAccount.getId(),
                        updatedAccount.getName(),
                        updatedAccount.getUsername(),
                        updatedAccount.getEmail(),
                        updatedAccount.getPassword(),
                        Boolean.TRUE.equals(
                                updatedAccount.getStatus()
                        ),
                        authentication.getAuthorities(),
                        oldPrincipal.getPrimaryRole()
                );

        UsernamePasswordAuthenticationToken newAuthentication =
                new UsernamePasswordAuthenticationToken(
                        updatedPrincipal,
                        authentication.getCredentials(),
                        authentication.getAuthorities()
                );

        newAuthentication.setDetails(
                authentication.getDetails()
        );

        SecurityContextHolder
                .getContext()
                .setAuthentication(newAuthentication);
    }
}