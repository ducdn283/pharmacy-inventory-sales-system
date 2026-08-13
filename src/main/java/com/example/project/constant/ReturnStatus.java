package com.example.project.constant;

import java.util.List;

/**
 * Central definition of the valid {@code Return.status} values for a <em>customer</em> return.
 *
 * <p>The column is a plain {@code varchar(50)} (there is no {@code Status} table — confirmed by
 * {@code Pharmacy Database Description.docx}). These constants keep the vocabulary in one place so
 * it never drifts between the service, controller and templates.</p>
 *
 * <p>Workflow (owner-confirmed 2026-07-09):
 * <ul>
 *   <li>{@link #DRAFT} — a work-in-progress slip the creator has not sent yet.</li>
 *   <li>{@link #PENDING} — a Pharmacist has submitted it and it is awaiting the Owner's approval.</li>
 *   <li>{@link #DEBT} — approved, and the pharmacy <em>still owes the customer</em> money. There is
 *       intentionally no "Duyệt" state: approval is expressed by the slip landing in a settlement
 *       state. This is where stock is actually restored and the original invoice's return status is
 *       updated. Owner-created slips are auto-approved.</li>
 *   <li>{@link #COMPLETED} — approved and <em>nothing further is owed</em>. Either the netting against
 *       the invoice's debt swallowed the whole refund at approval time, or an Expense has since paid
 *       out the remainder.</li>
 *   <li>{@link #REJECTED} — the Owner declined a pending slip.</li>
 * </ul>
 *
 * <p><strong>{@link #DEBT} vs {@link #COMPLETED} is about money still owed, not about approval.</strong>
 * Both mean "approved". Code asking <em>"which returns do we still owe on?"</em> must therefore keep
 * matching {@link #DEBT} alone — see {@code ExpenseService.listCustomerReturns},
 * {@code DebtOffsetService.validateCustomerReturnOffset} and the accountant dashboard, none of which
 * should surface a settled slip. Code asking <em>"which returns were approved?"</em> needs both.</p>
 *
 * <p><strong>Chỉ một trong hai đường vào {@link #COMPLETED} đã nối.</strong> Phiếu tự tất toán ngay
 * lúc duyệt khi bù trừ công nợ nuốt trọn khoản hoàn ({@code ReturnService.settledStatusOf}) —
 * đường này đã chạy. Đường còn lại, <em>"phiếu chi hoàn tiền trả xong ⇒ chuyển Hoàn thành"</em>
 * (hồ sơ nghiệp vụ v2 sheet 08), <strong>CHƯA làm</strong>: chỗ móc duy nhất là
 * {@code ExpenseService.applyApproval} — điểm chi tiền duy nhất của hệ thống — thuộc module Thu/Chi
 * của thành viên khác, và đã bàn giao cho chủ module tự nối.</p>
 *
 * <p>Hệ quả trong lúc chờ: phiếu trả được hoàn bằng TIỀN sẽ nằm mãi ở {@link #DEBT} kể cả sau khi
 * phiếu chi đã chi đủ. Mọi bộ lọc "còn phải hoàn" vẫn đúng (chúng đo {@link #DEBT}), chỉ là phiếu
 * không tự chuyển sang {@link #COMPLETED}.</p>
 */
public final class ReturnStatus {

    public static final String DRAFT = "Nháp";
    public static final String PENDING = "Chờ duyệt";
    public static final String DEBT = "Nợ";
    public static final String COMPLETED = "Hoàn thành";
    public static final String REJECTED = "Từ chối";

    /** All valid statuses, in workflow order (for the filter dropdown). */
    public static final List<String> ALL = List.of(DRAFT, PENDING, DEBT, COMPLETED, REJECTED);

    private ReturnStatus() {
    }
}
