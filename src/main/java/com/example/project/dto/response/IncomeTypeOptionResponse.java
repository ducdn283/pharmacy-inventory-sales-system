package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Locale;

/** Một loại phiếu thu cho dropdown — mã nội bộ và nhãn tiếng Việt. */
@Getter
@AllArgsConstructor
public class IncomeTypeOptionResponse {

    /** Mã loại: thu nợ NCC. */
    public static final String SUPPLIER = "SUPPLIER";
    /** Mã loại: thu đền bù nhân viên làm hỏng hàng. */
    public static final String EMPLOYEE = "EMPLOYEE";
    /** Mã loại: thu thất thoát tiền mặt khi kết ca. */
    public static final String SHIFT_SHORTAGE = "SHIFT_SHORTAGE";
    /** Mã loại: thu nợ khách hàng. */
    public static final String CUSTOMER = "CUSTOMER";
    /** Mã loại: thu khác (nhập tay, không liên kết chứng từ). */
    public static final String OTHER = "OTHER";

    /** Mã loại nội bộ (SUPPLIER, CUSTOMER, …). */
    private String code;
    /** Nhãn hiển thị trên form và danh sách. */
    private String label;

    /** Tất cả loại phiếu thu cho dropdown form tạo phiếu. */
    public static List<IncomeTypeOptionResponse> all() {
        return List.of(
                new IncomeTypeOptionResponse(SUPPLIER, "Thu nợ nhà cung cấp"),
                new IncomeTypeOptionResponse(EMPLOYEE, "Thu tiền nhân viên làm hỏng hàng"),
                new IncomeTypeOptionResponse(SHIFT_SHORTAGE, "Thu tiền nhân viên làm thiếu tiền khi kết ca"),
                new IncomeTypeOptionResponse(CUSTOMER, "Thu nợ khách hàng"),
                new IncomeTypeOptionResponse(OTHER, "Khác")
        );
    }

    /** Loại nhập tay — không bắt buộc đối tượng hay chứng từ liên quan. */
    public static boolean isSimpleManualType(String storedOrCode) {
        return OTHER.equals(codeOf(storedOrCode));
    }

    /** Kiểm tra mã hoặc nhãn loại phiếu thu có hợp lệ. */
    public static boolean isValid(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String trimmed = type.trim();
        return all().stream().anyMatch(option -> matches(option, trimmed));
    }

    /** Chuẩn hóa nhãn tiếng Việt hoặc mã legacy sang mã nội bộ. */
    public static String codeOf(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return all().stream()
                .filter(option -> matches(option, trimmed))
                .map(IncomeTypeOptionResponse::getCode)
                .findFirst()
                .orElse(trimmed.toUpperCase(Locale.ROOT));
    }

    /** Nhãn tiếng Việt lưu vào cột {@code Income.incomeType}. */
    public static String storageLabelOf(String codeOrLabel) {
        return labelOf(codeOrLabel);
    }

    /** Nhãn hiển thị từ mã hoặc nhãn đã lưu. */
    public static String labelOf(String type) {
        if (type == null) {
            return "";
        }
        String trimmed = type.trim();
        return all().stream()
                .filter(option -> matches(option, trimmed))
                .map(IncomeTypeOptionResponse::getLabel)
                .findFirst()
                .orElse(trimmed);
    }

    /** Có phải loại thu nợ khách hàng hay không. */
    public static boolean isCustomer(String storedOrCode) {
        return CUSTOMER.equals(codeOf(storedOrCode));
    }

    /** So khớp mã, nhãn hiện tại hoặc nhãn legacy với giá trị đầu vào. */
    private static boolean matches(IncomeTypeOptionResponse option, String value) {
        return option.code.equalsIgnoreCase(value)
                || option.label.equalsIgnoreCase(value)
                || legacyLabelMatches(option, value);
    }

    /** Nhận diện nhãn tiếng Việt cũ trước khi đổi tên loại phiếu thu. */
    private static boolean legacyLabelMatches(IncomeTypeOptionResponse option, String value) {
        return switch (option.code) {
            case CUSTOMER -> "Khách hàng".equalsIgnoreCase(value);
            case SUPPLIER -> "Nhà cung cấp".equalsIgnoreCase(value);
            case EMPLOYEE -> "Nhân viên".equalsIgnoreCase(value)
                    || "Thu tiền nhân viên đền bù".equalsIgnoreCase(value)
                    || "Thu tiền người chịu trách nhiệm đền bù".equalsIgnoreCase(value);
            case SHIFT_SHORTAGE -> "Thu tiền nhân viên làm thất thoát".equalsIgnoreCase(value)
                    || "Thu tiền người chịu trách nhiệm làm thất thoát".equalsIgnoreCase(value);
            default -> false;
        };
    }
}
