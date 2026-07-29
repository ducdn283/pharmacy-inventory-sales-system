package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Form backing the "create expense" screen. {@code applicantID} is always the current user. The
 * optional {@code PurchaseInvoice}/{@code ShiftReport}/{@code Supplier}/{@code Account} links on
 * the entity are still left unset; {@code returnID} is now wired (see {@link #returnId}) and
 * {@code customerID} is derived from it server-side.
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

    /** Tổng số tiền cần chi. */
    private BigDecimal amount;

    /** Whether the full amount was already paid out at creation time. */
    private boolean fullyPaid = true;

    /** Only read when {@code fullyPaid} is false; must be between 0 and {@code amount}. */
    private BigDecimal paid;

    private BigDecimal paidByCash;

    private BigDecimal paidByBanking;

    private String note;
}
