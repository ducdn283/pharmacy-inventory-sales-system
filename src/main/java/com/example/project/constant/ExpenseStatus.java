package com.example.project.constant;

import java.util.List;

/**
 * Central definition of the valid {@code Expense.status} values, per
 * {@code docs/context/Pharmacy-Database-Description.docx}. The column is a plain
 * {@code varchar(50)} (no {@code Status} table, see CLAUDE.md) — these constants exist so the
 * vocabulary is written in exactly one place and never drifts between the service and templates.
 *
 * <p><strong>Workflow (BA 2026-08): approval and real payment are two separate steps again</strong>
 * — reverses the 2026-07-30 "approval = instant completion" rule. {@link #AWAITING_PAYMENT} is a
 * real, reachable state now, not legacy display-only:
 * <ul>
 *   <li>{@link #DRAFT} — a work-in-progress slip the creator has not sent yet.</li>
 *   <li>{@link #PENDING} — submitted by a non-Owner creator, awaiting the Owner's approval.</li>
 *   <li>{@link #REJECTED} — the Owner declined a pending slip.</li>
 *   <li>{@link #AWAITING_PAYMENT} — approved (by Owner directly, or Owner approving an
 *       Accountant's pending slip), but the money has not actually left yet. Not editable, but can
 *       still be cancelled — nothing has been disbursed, so there is nothing to reverse.</li>
 *   <li>{@link #COMPLETED} — the Owner has confirmed the money was actually paid out. This is when
 *       the linked purchase invoice/customer return's obligation is settled, the financial-setting
 *       fund is debited, and the shift is stamped — see {@code ExpenseService.confirmPayment()}.
 *       Only the Owner may reach this state, even for a slip an Accountant raised/approved-into.
 *       Terminal — cannot be cancelled once real money has left.</li>
 *   <li>{@link #CANCELLED} — internal correction for a wrongly-entered slip (same spirit as
 *       {@code PurchaseInvoiceStatus.CANCELLED}), not a real accounting reversal. Reachable from
 *       {@link #DRAFT}/{@link #PENDING}/{@link #AWAITING_PAYMENT} only — never from
 *       {@link #COMPLETED}.</li>
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
