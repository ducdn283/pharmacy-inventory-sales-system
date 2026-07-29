package com.example.project.constant;

import java.util.List;

/**
 * Central definition of the valid {@code Expense.status} values, per
 * {@code docs/context/Pharmacy-Database-Description.docx}. The column is a plain
 * {@code varchar(50)} (no {@code Status} table, see CLAUDE.md) — these constants exist so the
 * vocabulary is written in exactly one place and never drifts between the service and templates.
 *
 * <p>Workflow (mirrors {@code StockAdjustmentStatus}'s draft/submit/approve/reject shape, plus a
 * payment step since an Expense tracks real cash leaving the register):
 * <ul>
 *   <li>{@link #DRAFT} — a work-in-progress slip the creator has not sent yet.</li>
 *   <li>{@link #PENDING} — submitted by a non-Owner creator, awaiting the Owner's approval.</li>
 *   <li>{@link #REJECTED} — the Owner declined a pending slip.</li>
 *   <li>{@link #AWAITING_PAYMENT} — approved but {@code paid < amount}.</li>
 *   <li>{@link #COMPLETED} — approved and fully paid ({@code paid >= amount}).</li>
 *   <li>{@link #CANCELLED} — internal correction for a wrongly-entered slip (same spirit as
 *       {@code PurchaseInvoiceStatus.CANCELLED}), not a real accounting reversal.</li>
 * </ul>
 */
public final class ExpenseStatus {

    public static final String DRAFT = "Nháp";
    public static final String PENDING = "Chờ duyệt";
    public static final String REJECTED = "Từ chối";
    public static final String AWAITING_PAYMENT = "Chờ thanh toán";
    public static final String COMPLETED = "Đã hoàn thành";
    public static final String CANCELLED = "Đã hủy";

    /** All valid statuses, in workflow order (for the filter dropdown). */
    public static final List<String> ALL =
            List.of(DRAFT, PENDING, REJECTED, AWAITING_PAYMENT, COMPLETED, CANCELLED);

    private ExpenseStatus() {
    }
}
