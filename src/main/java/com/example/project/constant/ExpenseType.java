package com.example.project.constant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central definition of the valid {@code Expense.expenseType} values, confirmed by the team's BA
 * on 2026-07-23 (the docx's own table was ambiguous — see project memory
 * {@code expense-price-settings-open-questions}). {@code SALARY} is not its own type: per the BA,
 * regular payroll falls under {@link #OPERATIONAL}. {@code RETURN_TO_SUPPLIER_LOSS} was dropped
 * from the earlier docx draft entirely — not a real type.
 */
public final class ExpenseType {

    public static final String OPERATIONAL = "OPERATIONAL";
    public static final String DEBT_PAYMENT = "DEBT_PAYMENT";
    public static final String RETURN_REFUND_PAYOUT = "RETURN_REFUND_PAYOUT";
    public static final String EMPLOYEE_ADVANCE_REPAYMENT = "EMPLOYEE_ADVANCE_REPAYMENT";
    public static final String OTHER = "OTHER";

    /** All valid types, in display order. */
    public static final List<String> ALL = List.of(
            OPERATIONAL, DEBT_PAYMENT, RETURN_REFUND_PAYOUT, EMPLOYEE_ADVANCE_REPAYMENT, OTHER);

    /**
     * Types whose slip may settle a {@code PurchaseInvoice}. <strong>{@link #OPERATIONAL} only</strong>
     * — BA decision 2026-07-26.
     *
     * <p>"Chi phí vận hành" is the pharmacy's bucket for regular day-to-day outgoings, and
     * <em>everything that touches a purchase invoice lives here</em>, money owed to the supplier
     * included: that debt <em>is</em> the import invoice, so it is not a separate kind of payment.
     * It sits alongside lương, tiền điện, tiền nước, which are not modelled as sub-types — they are
     * just what the user writes in {@code reason}. Only the import-invoice case needs a document to
     * reconcile against, hence a link rather than a bigger taxonomy.</p>
     *
     * <p>{@link #DEBT_PAYMENT} is deliberately <em>not</em> here. It covers debt arising somewhere
     * other than an import invoice, which has no document in this system to point at — {@code reason}
     * carries the whole story. An earlier build did allow it; that was reverted once the BA settled
     * the split, so do not add it back without re-checking with them.</p>
     *
     * <p>The link is orthogonal to the type: the type says how the money is classified, the link
     * says which document it settles. It is always optional, never required.</p>
     */
    public static final List<String> PURCHASE_LINKABLE = List.of(OPERATIONAL);

    private ExpenseType() {
    }

    public static boolean isValid(String type) {
        return type != null && ALL.contains(type);
    }

    /** Whether a slip of this type may point at a purchase invoice. Never required, only allowed. */
    public static boolean supportsPurchaseInvoiceLink(String type) {
        return type != null && PURCHASE_LINKABLE.contains(type);
    }

    /** Vietnamese display label, e.g. {@code OPERATIONAL -> "Chi phí vận hành"}. */
    public static String vietnameseName(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case OPERATIONAL -> "Chi phí vận hành";
            case DEBT_PAYMENT -> "Trả nợ";
            case RETURN_REFUND_PAYOUT -> "Hoàn tiền trả hàng";
            case EMPLOYEE_ADVANCE_REPAYMENT -> "Lương ứng của nhân viên";
            case OTHER -> "Chi khác";
            default -> type;
        };
    }

    /** Ordered map of type code -> Vietnamese label, for building the type dropdown. */
    public static Map<String, String> vietnameseLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String type : ALL) {
            labels.put(type, vietnameseName(type));
        }
        return labels;
    }
}
