package com.example.project.controller;

import com.example.project.dto.request.EmployeenoteCreateRequest;
import com.example.project.dto.response.EmployeenoteResponse;
import com.example.project.service.EmployeenoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/owner/users")
public class EmployeenoteController {

    private final EmployeenoteService employeenoteService;

    public EmployeenoteController(
            EmployeenoteService employeenoteService
    ) {
        this.employeenoteService = employeenoteService;
    }

    /*
     * Lấy toàn bộ ghi chú của một nhân viên.
     */
    @GetMapping("/{accountId}/notes")
    public ResponseEntity<?> getByAccountId(
            @PathVariable Integer accountId
    ) {
        try {
            List<EmployeenoteResponse> notes =
                    employeenoteService.getByAccountId(accountId);

            return ResponseEntity.ok(notes);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "message",
                            exception.getMessage()
                    ));
        }
    }

    /*
     * Tạo ghi chú mới cho một nhân viên.
     */
    @PostMapping("/{accountId}/notes")
    public ResponseEntity<?> create(
            @PathVariable Integer accountId,
            @Valid
            @RequestBody EmployeenoteCreateRequest request,
            BindingResult bindingResult
    ) {
        if (bindingResult.hasErrors()) {
            String message = bindingResult.getFieldErrors()
                    .stream()
                    .findFirst()
                    .map(error -> error.getDefaultMessage())
                    .orElse("Dữ liệu ghi chú không hợp lệ");

            return ResponseEntity.badRequest()
                    .body(Map.of("message", message));
        }

        try {
            EmployeenoteResponse createdNote =
                    employeenoteService.create(
                            accountId,
                            request.getContent()
                    );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(createdNote);

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            exception.getMessage()
                    ));
        }
    }
}