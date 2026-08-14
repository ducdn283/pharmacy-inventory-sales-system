package com.example.project.constant;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Danh sách hợp lệ của {@code Expense.expenseType}. {@code SALARY} không phải một loại riêng —
 * lương thông thường nằm trong {@link #OPERATIONAL}.
 */
public final class ExpenseType {

    /** Tiền hàng trả nhà cung cấp — loại duy nhất gắn được phiếu nhập. Xem {@link #PURCHASE_LINKABLE}. */
    public static final String GOODS_PAYMENT = "GOODS_PAYMENT";
    public static final String OPERATIONAL = "OPERATIONAL";
    public static final String RETURN_REFUND_PAYOUT = "RETURN_REFUND_PAYOUT";
    public static final String EMPLOYEE_ADVANCE_REPAYMENT = "EMPLOYEE_ADVANCE_REPAYMENT";
    public static final String OTHER = "OTHER";

    /** Toàn bộ loại hợp lệ, theo thứ tự hiển thị. */
    public static final List<String> ALL = List.of(
            GOODS_PAYMENT, OPERATIONAL, RETURN_REFUND_PAYOUT, EMPLOYEE_ADVANCE_REPAYMENT, OTHER);

    /** Dược sĩ chỉ được lập phiếu hoàn tiền có giá trị nhỏ hơn ngưỡng này. */
    public static final BigDecimal PHARMACIST_REFUND_LIMIT = BigDecimal.valueOf(500_000);

    /**
     * Phiếu chi do Dược sĩ lập với giá trị dưới ngưỡng này thì không cần Chủ nhà thuốc duyệt — tự
     * động sang thẳng {@code ExpenseStatus#AWAITING_PAYMENT}, cùng cách Owner tự duyệt phiếu của
     * chính mình. Tách riêng với {@link #PHARMACIST_REFUND_LIMIT} dù cùng giá trị: đây là ngưỡng
     * DUYỆT, còn hằng kia là trần SỐ TIỀN được tạo.
     */
    public static final BigDecimal PHARMACIST_AUTO_APPROVE_LIMIT = BigDecimal.valueOf(500_000);

    /**
     * Các loại phiếu được phép gắn và tất toán một {@code PurchaseInvoice} — <strong>chỉ
     * {@link #GOODS_PAYMENT}</strong>. {@link #OPERATIONAL} (điện, nước, lương...) cố tình không
     * gắn phiếu nhập, để tách riêng tiền hàng khỏi chi phí vận hành.
     *
     * <p>Việc tách này còn ảnh hưởng tính thuế: {@code TaxperiodsnapshotService
     * .DEDUCTIBLE_EXPENSE_TYPES} lọc thêm điều kiện "không gắn phiếu nhập" để loại tiền hàng ra
     * khỏi chi phí được trừ (tiền hàng đã nằm trong giá vốn).
     * <strong>Đừng thêm {@link #GOODS_PAYMENT} vào danh sách được trừ.</strong></p>
     */
    public static final List<String> PURCHASE_LINKABLE = List.of(GOODS_PAYMENT);

    private ExpenseType() {
    }

    public static boolean isValid(String type) {
        return type != null && ALL.contains(type);
    }

    /** Loại phiếu này có được phép trỏ tới một phiếu nhập hay không (không bắt buộc, chỉ cho phép). */
    public static boolean supportsPurchaseInvoiceLink(String type) {
        return type != null && PURCHASE_LINKABLE.contains(type);
    }

    /** Nhãn hiển thị tiếng Việt, ví dụ {@code OPERATIONAL -> "Chi phí vận hành"}. */
    public static String vietnameseName(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case GOODS_PAYMENT -> "Thanh toán hàng";
            case OPERATIONAL -> "Chi phí vận hành";
            case RETURN_REFUND_PAYOUT -> "Hoàn tiền trả hàng";
            case EMPLOYEE_ADVANCE_REPAYMENT -> "Lương ứng của nhân viên";
            case OTHER -> "Chi khác";
            default -> type;
        };
    }

    /** Map có thứ tự loại -> nhãn tiếng Việt, dùng để dựng dropdown chọn loại. */
    public static Map<String, String> vietnameseLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String type : ALL) {
            labels.put(type, vietnameseName(type));
        }
        return labels;
    }
}
