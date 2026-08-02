package com.example.project.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmployeenoteCreateRequest {

    @NotBlank(message = "Nội dung ghi chú không được để trống")
    @Size(
            max = 2000,
            message = "Nội dung ghi chú không được vượt quá 2000 ký tự"
    )
    private String content;
}