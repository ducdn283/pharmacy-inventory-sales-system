package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Form backing the "create expense" screen. {@code applicantID} is always the current user.
 * {@code returnID} (see {@link #returnId}) and {@code purchaseID} (see {@link #purchaseId}) are
 * both wired here; {@code customerID}/{@code supplierID} are derived server-side from whichever is
 * linked. {@code shiftReportID} and {@code accountID} are never posted by this form.
 */
@Getter
@Setter
public class ExpenseCreateRequest {

    /** One of {@link com.example.project.constant.ExpenseType}'s ALL values. */
    private String expenseType;

    /**
     * The customer return this slip pays out. Required when {@code expenseType} is
     * {@link com.example.project.constant.ExpenseType#RETURN_REFUND_PAYOUT}, ignored otherwise.
     * When set, the server overrides {@link #amount} with the return's own cash refund figure —
     * the posted value is never trusted.
     */
    private Integer returnId;

    /**
     * The purchase invoice this slip settles. Only read for the types in
     * {@link com.example.project.constant.ExpenseType#PURCHASE_LINKABLE} and optional even there —
     * paying a supplier without pointing at one specific invoice is legitimate. When set,
     * {@link #amount} stays the user's to choose (a partial payment is normal) but may not exceed
     * what the invoice still has outstanding.
     */
    private Integer purchaseId;

    /** yyyy-MM-dd from the date input; defaults to today when blank. */
    private String date;

    private String reason;

    /**
     * Số tiền chi lần này — <strong>không phải</strong> tổng nghĩa vụ của chứng từ được gắn.
     *
     * <p>Một phiếu chi là một lần chi tiền và không sửa được: nợ 50.000 mà hôm nay trả 30.000 thì
     * phiếu này là 30.000, hôm sau trả nốt là một phiếu khác. Nghĩa vụ còn lại nằm ở chứng từ
     * (phiếu nhập / phiếu trả hàng), không nằm ở đây. Vì vậy không còn {@code fullyPaid} lẫn
     * {@code paid}: {@code paid} luôn bằng {@code amount}.</p>
     */
    private BigDecimal amount;

    private BigDecimal paidByCash;

    private BigDecimal paidByBanking;

    private String note;
}
