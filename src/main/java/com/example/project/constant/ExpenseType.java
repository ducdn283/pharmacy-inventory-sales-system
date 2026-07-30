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
 *
 * <p>2026-07-30: thêm {@link #GOODS_PAYMENT} để tách tiền hàng ra khỏi {@link #OPERATIONAL} —
 * xem {@link #PURCHASE_LINKABLE} để biết vì sao đảo lại quyết định cũ.</p>
 */
public final class ExpenseType {

    /** Tiền hàng trả nhà cung cấp — loại duy nhất gắn được phiếu nhập. Xem {@link #PURCHASE_LINKABLE}. */
    public static final String GOODS_PAYMENT = "GOODS_PAYMENT";
    public static final String OPERATIONAL = "OPERATIONAL";
    public static final String DEBT_PAYMENT = "DEBT_PAYMENT";
    public static final String RETURN_REFUND_PAYOUT = "RETURN_REFUND_PAYOUT";
    public static final String EMPLOYEE_ADVANCE_REPAYMENT = "EMPLOYEE_ADVANCE_REPAYMENT";
    public static final String OTHER = "OTHER";

    /** All valid types, in display order. */
    public static final List<String> ALL = List.of(
            GOODS_PAYMENT, OPERATIONAL, DEBT_PAYMENT, RETURN_REFUND_PAYOUT, EMPLOYEE_ADVANCE_REPAYMENT, OTHER);

    /**
     * Types whose slip may settle a {@code PurchaseInvoice}. <strong>{@link #GOODS_PAYMENT} only</strong>
     * — BA decision 2026-07-30.
     *
     * <p><strong>Đây là chỗ đảo lại quyết định ngày 2026-07-26.</strong> Trước đó tiền hàng nằm chung
     * trong {@link #OPERATIONAL} với lý lẽ "nợ nhà cung cấp <em>chính là</em> phiếu nhập, không phải
     * một loại chi riêng". Thực tế dùng cho thấy gộp như vậy làm hỏng ý nghĩa của "Chi phí vận hành":
     * nó phình ra gấp nhiều lần và không còn trả lời được câu "tháng này tiêu bao nhiêu cho điện,
     * nước, lương". Nay tách hẳn:</p>
     *
     * <ul>
     *   <li>{@link #GOODS_PAYMENT} "Thanh toán hàng" — tiền trả cho hàng nhập, luôn gắn phiếu nhập;</li>
     *   <li>{@link #OPERATIONAL} "Chi phí vận hành" — <em>chỉ</em> điện, nước, lương và những khoản
     *       vận hành tương tự, <strong>không gắn phiếu nhập nữa</strong>. Các khoản này không được
     *       mô hình hoá thành loại con: người dùng tự viết vào {@code reason}.</li>
     * </ul>
     *
     * <p>Việc tách còn làm sạch chỗ tính thuế: {@code TaxperiodsnapshotService.DEDUCTIBLE_EXPENSE_TYPES}
     * vốn phải lọc thêm "không gắn phiếu nhập" để loại tiền hàng ra khỏi chi phí được trừ (tiền hàng
     * đã nằm trong giá vốn). Từ nay điều kiện đó chỉ còn cần cho các phiếu cũ lưu trước ngày đổi —
     * <strong>đừng thêm {@link #GOODS_PAYMENT} vào danh sách được trừ.</strong></p>
     *
     * <p>{@link #DEBT_PAYMENT} vẫn cố tình không nằm ở đây: nó dành cho khoản nợ phát sinh ở chỗ khác,
     * không có chứng từ nào trong hệ thống để trỏ tới.</p>
     *
     * <p>Các phiếu cũ mang {@code OPERATIONAL} kèm {@code purchaseID} vẫn còn trong DB và vẫn hiển thị
     * bình thường — theo lệ "màn hình đọc đúng cột trong DB". Không có migration nào đổi chúng.</p>
     */
    public static final List<String> PURCHASE_LINKABLE = List.of(GOODS_PAYMENT);

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
            case GOODS_PAYMENT -> "Thanh toán hàng";
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
