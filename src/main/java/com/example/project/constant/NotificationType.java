package com.example.project.constant;

public final class NotificationType {

    public static final String LOW_STOCK = "LOW_STOCK";
    public static final String OUT_OF_STOCK = "OUT_OF_STOCK";
    public static final String EXPIRING_BATCH = "EXPIRING_BATCH";
    public static final String EXPIRED_BATCH = "EXPIRED_BATCH";

    public static final String RETURN_PENDING = "RETURN_PENDING";
    public static final String RETURN_APPROVED = "RETURN_APPROVED";
    public static final String RETURN_REJECTED = "RETURN_REJECTED";

    public static final String EXPENSE_PENDING = "EXPENSE_PENDING";
    public static final String EXPENSE_APPROVED = "EXPENSE_APPROVED";
    public static final String EXPENSE_REJECTED = "EXPENSE_REJECTED";

    public static final String STOCK_REVIEW_PENDING = "STOCK_REVIEW_PENDING";
    public static final String STOCK_REVIEW_APPROVED = "STOCK_REVIEW_APPROVED";
    public static final String STOCK_REVIEW_REJECTED = "STOCK_REVIEW_REJECTED";

    public static final String STOCK_ADJUSTMENT_CREATED = "STOCK_ADJUSTMENT_CREATED";
    public static final String STOCK_ADJUSTMENT_COMPLETED = "STOCK_ADJUSTMENT_COMPLETED";
    public static final String STOCK_ADJUSTMENT_CANCELLED = "STOCK_ADJUSTMENT_CANCELLED";

    public static final String SHIFT_REPORT_PENDING = "SHIFT_REPORT_PENDING";
    public static final String SHIFT_REPORT_APPROVED = "SHIFT_REPORT_APPROVED";
    public static final String SHIFT_REPORT_REJECTED = "SHIFT_REPORT_REJECTED";

    public static final String INCOME_CREATED = "INCOME_CREATED";
    public static final String INCOME_CANCELLED = "INCOME_CANCELLED";

    public static final String INVOICE_DEBT = "INVOICE_DEBT";
    public static final String PURCHASE_INVOICE_DUE = "PURCHASE_INVOICE_DUE";

    public static final String TAX_REVENUE_WARNING = "TAX_REVENUE_WARNING";
    public static final String TAX_REVENUE_EXCEEDED = "TAX_REVENUE_EXCEEDED";
    public static final String TAX_GROUP_CHANGED = "TAX_GROUP_CHANGED";

    public static final String SYSTEM = "SYSTEM";

    public static final String EMPLOYEE_NOTE_CREATED = "EMPLOYEE_NOTE_CREATED";

    private NotificationType() {
    }
}