package com.example.project.service;

import com.example.project.dto.response.EmployeenoteResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Employeenote;
import com.example.project.repository.AccountRepository;
import com.example.project.repository.EmployeenoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class EmployeenoteService {

    private final EmployeenoteRepository employeenoteRepository;
    private final AccountRepository accountRepository;

    public EmployeenoteService(
            EmployeenoteRepository employeenoteRepository,
            AccountRepository accountRepository
    ) {
        this.employeenoteRepository = employeenoteRepository;
        this.accountRepository = accountRepository;
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
                .findByAccountID_IdOrderByDateDescIdDesc(accountId)
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
                content == null ? "" : content.trim();

        if (normalizedContent.isBlank()) {
            throw new IllegalArgumentException(
                    "Nội dung ghi chú không được để trống"
            );
        }

        Employeenote note = new Employeenote();
        note.setAccountID(account);
        note.setDate(Instant.now());
        note.setContent(normalizedContent);

        Employeenote savedNote =
                employeenoteRepository.save(note);

        return EmployeenoteResponse.from(savedNote);
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