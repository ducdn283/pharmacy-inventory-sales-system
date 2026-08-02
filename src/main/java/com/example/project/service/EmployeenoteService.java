package com.example.project.service;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationReferenceType;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationType;
import com.example.project.dto.response.EmployeenoteResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Accountpermission;
import com.example.project.entity.Employeenote;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.repository.EmployeenoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class EmployeenoteService {

    private final EmployeenoteRepository employeenoteRepository;
    private final AccountRepository accountRepository;
    private final AccountpermissionRepository accountpermissionRepository;
    private final NotificationService notificationService;

    public EmployeenoteService(
            EmployeenoteRepository employeenoteRepository,
            AccountRepository accountRepository,
            AccountpermissionRepository accountpermissionRepository,
            NotificationService notificationService
    ) {
        this.employeenoteRepository = employeenoteRepository;
        this.accountRepository = accountRepository;
        this.accountpermissionRepository =
                accountpermissionRepository;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public List<EmployeenoteResponse> getAll() {
        return employeenoteRepository.findAll()
                .stream()
                .map(EmployeenoteResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EmployeenoteResponse> getByAccountId(
            Integer accountId
    ) {
        validateAccountId(accountId);

        return employeenoteRepository
                .findByAccountID_IdOrderByDateDescIdDesc(
                        accountId
                )
                .stream()
                .map(EmployeenoteResponse::from)
                .toList();
    }

    @Transactional
    public EmployeenoteResponse create(
            Integer accountId,
            String content
    ) {
        Account account = findAccount(accountId);

        String normalizedContent =
                normalizeContent(content);

        Employeenote note = new Employeenote();
        note.setAccountID(account);
        note.setDate(Instant.now());
        note.setContent(normalizedContent);

        Employeenote savedNote =
                employeenoteRepository.save(note);

        /*
         * Tạo notification cho đúng nhân viên.
         */
        notificationService.createIfMissing(
                account,
                resolveTargetRole(accountId),
                "Bạn có ghi chú nhân viên mới",
                "Chủ nhà thuốc đã tạo một ghi chú mới trong hồ sơ của bạn.",
                NotificationType.EMPLOYEE_NOTE_CREATED,
                NotificationCategory.HE_THONG,
                NotificationSeverity.INFO,
                NotificationReferenceType.EMPLOYEE_NOTE,
                savedNote.getId(),
                "/profile#employee-notes",
                "EMPLOYEE_NOTE_CREATED_"
                        + savedNote.getId()
                        + "_ACCOUNT_"
                        + accountId
        );

        return EmployeenoteResponse.from(savedNote);
    }

    @Transactional
    public EmployeenoteResponse update(
            Integer accountId,
            Integer noteId,
            String content
    ) {
        validateAccountId(accountId);

        String normalizedContent =
                normalizeContent(content);

        Employeenote note = employeenoteRepository
                .findByIdAndAccountID_Id(
                        noteId,
                        accountId
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy ghi chú"
                        )
                );

        /*
         * Chỉ sửa nội dung.
         * Ngày tạo ban đầu được giữ nguyên.
         */
        note.setContent(normalizedContent);

        Employeenote savedNote =
                employeenoteRepository.save(note);

        return EmployeenoteResponse.from(savedNote);
    }

    private String normalizeContent(String content) {
        String normalized =
                content == null ? "" : content.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Nội dung ghi chú không được để trống"
            );
        }

        if (normalized.length() > 2000) {
            throw new IllegalArgumentException(
                    "Nội dung ghi chú không được vượt quá 2000 ký tự"
            );
        }

        return normalized;
    }

    private String resolveTargetRole(Integer accountId) {
        return accountpermissionRepository
                .findByAccountId(accountId)
                .stream()
                .map(Accountpermission::getRole)
                .filter(role ->
                        role != null && !role.isBlank()
                )
                .findFirst()
                .orElse("EMPLOYEE");
    }

    private void validateAccountId(Integer accountId) {
        findAccount(accountId);
    }

    private Account findAccount(Integer accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException(
                    "Nhân viên không hợp lệ"
            );
        }

        return accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy nhân viên"
                        )
                );
    }
}