package com.example.project.controller;

import com.example.project.dto.request.EmployeenoteCreateRequest;
import com.example.project.dto.request.EmployeenoteUpdateRequest;
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

    @GetMapping("/{accountId}/notes")
    public ResponseEntity<?> getByAccountId(
            @PathVariable Integer accountId
    ) {
        try {
            List<EmployeenoteResponse> notes =
                    employeenoteService.getByAccountId(
                            accountId
                    );

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

    @PostMapping("/{accountId}/notes")
    public ResponseEntity<?> create(
            @PathVariable Integer accountId,

            @Valid
            @RequestBody EmployeenoteCreateRequest request,

            BindingResult bindingResult
    ) {
        if (bindingResult.hasErrors()) {
            return validationError(bindingResult);
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

    /*
     * Chỉ Owner truy cập được vì URL bắt đầu bằng /owner.
     */
    @PutMapping("/{accountId}/notes/{noteId}")
    public ResponseEntity<?> update(
            @PathVariable Integer accountId,
            @PathVariable Integer noteId,

            @Valid
            @RequestBody EmployeenoteUpdateRequest request,

            BindingResult bindingResult
    ) {
        if (bindingResult.hasErrors()) {
            return validationError(bindingResult);
        }

        try {
            EmployeenoteResponse updatedNote =
                    employeenoteService.update(
                            accountId,
                            noteId,
                            request.getContent()
                    );

            return ResponseEntity.ok(updatedNote);

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            exception.getMessage()
                    ));
        }
    }

    private ResponseEntity<?> validationError(
            BindingResult bindingResult
    ) {
        String message = bindingResult.getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Dữ liệu ghi chú không hợp lệ");

        return ResponseEntity.badRequest()
                .body(Map.of("message", message));
    }
}