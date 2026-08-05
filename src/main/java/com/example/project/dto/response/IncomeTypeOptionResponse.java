package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Locale;

/** One income-type option for dropdowns (code + Vietnamese label). */
@Getter
@AllArgsConstructor
public class IncomeTypeOptionResponse {

    public static final String SUPPLIER = "SUPPLIER";
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String SHIFT_SHORTAGE = "SHIFT_SHORTAGE";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String TAX_REFUND = "TAX_REFUND";
    public static final String SUPPLIER_COMMISSION = "SUPPLIER_COMMISSION";
    public static final String OTHER = "OTHER";

    private String code;
    private String label;

    public static List<IncomeTypeOptionResponse> all() {
        return List.of(
                new IncomeTypeOptionResponse(SUPPLIER, "Thu nợ nhà cung cấp"),
                new IncomeTypeOptionResponse(EMPLOYEE, "Thu tiền nhân viên làm hỏng hàng"),
                new IncomeTypeOptionResponse(SHIFT_SHORTAGE, "Thu tiền nhân viên làm thiếu tiền khi kết ca"),
                new IncomeTypeOptionResponse(CUSTOMER, "Thu nợ khách hàng"),
                new IncomeTypeOptionResponse(TAX_REFUND, "Thu hoàn thuế"),
                new IncomeTypeOptionResponse(SUPPLIER_COMMISSION, "Thu hoa hồng NCC"),
                new IncomeTypeOptionResponse(OTHER, "Khác")
        );
    }

    /** Manual amount entry only — no linked party or reference document. */
    public static boolean isSimpleManualType(String storedOrCode) {
        String code = codeOf(storedOrCode);
        return OTHER.equals(code) || TAX_REFUND.equals(code) || SUPPLIER_COMMISSION.equals(code);
    }

    public static boolean isValid(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String trimmed = type.trim();
        return all().stream().anyMatch(option -> matches(option, trimmed));
    }

    /** Normalizes a stored Vietnamese label or legacy English code to the internal code. */
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

    /** Vietnamese label persisted in {@code Income.incomeType}. */
    public static String storageLabelOf(String codeOrLabel) {
        return labelOf(codeOrLabel);
    }

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

    public static boolean isCustomer(String storedOrCode) {
        return CUSTOMER.equals(codeOf(storedOrCode));
    }

    private static boolean matches(IncomeTypeOptionResponse option, String value) {
        return option.code.equalsIgnoreCase(value)
                || option.label.equalsIgnoreCase(value)
                || legacyLabelMatches(option, value);
    }

    /** Recognizes Vietnamese labels persisted before the income-type rename. */
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
