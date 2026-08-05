package com.example.project.constant;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central definition of the valid {@code Expense.expenseType} values — the docx's own table was
 * ambiguous, so this is the authoritative list. {@code SALARY} is not its own type: regular payroll
 * falls under {@link #OPERATIONAL}. {@code RETURN_TO_SUPPLIER_LOSS} is not a real type, despite
 * appearing in an earlier docx draft.
 *
 * <p>{@link #GOODS_PAYMENT} splits tiền hàng out of {@link #OPERATIONAL} — see
 * {@link #PURCHASE_LINKABLE} for why.</p>
 */
public final class ExpenseType {

    /** Tiền hàng trả nhà cung cấp — loại duy nhất gắn được phiếu nhập. Xem {@link #PURCHASE_LINKABLE}. */
    public static final String GOODS_PAYMENT = "GOODS_PAYMENT";
    public static final String OPERATIONAL = "OPERATIONAL";
    public static final String RETURN_REFUND_PAYOUT = "RETURN_REFUND_PAYOUT";
    public static final String EMPLOYEE_ADVANCE_REPAYMENT = "EMPLOYEE_ADVANCE_REPAYMENT";
    public static final String OTHER = "OTHER";

    /** All valid types, in display order. */
    public static final List<String> ALL = List.of(
            GOODS_PAYMENT, OPERATIONAL, RETURN_REFUND_PAYOUT, EMPLOYEE_ADVANCE_REPAYMENT, OTHER);

    /**
     * Types whose slip may settle a {@code PurchaseInvoice}. <strong>{@link #GOODS_PAYMENT}
     * only</strong>. {@link #OPERATIONAL} deliberately does NOT link a purchase invoice — gộp tiền
     * hàng vào đó từng làm "Chi phí vận hành" phình to và không còn trả lời được câu "tháng này tốn
     * bao nhiêu cho điện, nước, lương", nên tiền hàng được tách hẳn:
     *
     * <ul>
     *   <li>{@link #GOODS_PAYMENT} "Thanh toán hàng" — tiền trả cho hàng nhập, luôn gắn phiếu nhập;</li>
     *   <li>{@link #OPERATIONAL} "Chi phí vận hành" — <em>chỉ</em> điện, nước, lương và những khoản
     *       vận hành tương tự, không gắn phiếu nhập. Các khoản này không được mô hình hoá thành loại
     *       con: người dùng tự viết vào {@code reason}.</li>
     * </ul>
     *
     * <p>Việc tách còn ảnh hưởng tới tính thuế: {@code TaxperiodsnapshotService.DEDUCTIBLE_EXPENSE_TYPES}
     * lọc thêm điều kiện "không gắn phiếu nhập" để loại tiền hàng ra khỏi chi phí được trừ (tiền hàng
     * đã nằm trong giá vốn) — điều kiện đó chỉ còn cần cho các phiếu cũ lưu trước khi tách loại.
     * <strong>Đừng thêm {@link #GOODS_PAYMENT} vào danh sách được trừ.</strong></p>
     *
     * <p>Các phiếu cũ mang {@code OPERATIONAL} kèm {@code purchaseID} vẫn còn trong DB và vẫn hiển thị
     * bình thường — theo lệ "màn hình đọc đúng cột trong DB".</p>
     */
    public static final List<String> PURCHASE_LINKABLE = List.of(GOODS_PAYMENT);

    /**
     * Types the "trên 5 triệu bắt buộc chuyển khoản" rule below applies to (BA, 2026-08). Cùng
     * ngưỡng 5 triệu Điều 26 Nghị định 181/2025/NĐ-CP đã dùng cho
     * {@code PurchaseinvoiceService.VAT_DEDUCTION_THRESHOLD} (hóa đơn ≥5tr cần chứng từ thanh toán
     * không dùng tiền mặt mới hợp lệ khấu trừ/tính vào chi phí hợp lý) — áp cho ba loại phát sinh
     * chi phí hợp lý hoặc thanh toán NCC: {@link #GOODS_PAYMENT}, {@link #OPERATIONAL},
     * {@link #RETURN_REFUND_PAYOUT}. {@link #EMPLOYEE_ADVANCE_REPAYMENT} và {@link #OTHER} không bị
     * ràng buộc — BA xác nhận trực tiếp.
     */
    public static final List<String> CASH_LIMIT_APPLICABLE =
            List.of(GOODS_PAYMENT, OPERATIONAL, RETURN_REFUND_PAYOUT);

    /** Ngưỡng của {@link #CASH_LIMIT_APPLICABLE} — trên mức này, phiếu bắt buộc chuyển khoản. */
    public static final BigDecimal CASH_LIMIT_THRESHOLD = BigDecimal.valueOf(5_000_000);

    private ExpenseType() {
    }

    public static boolean isValid(String type) {
        return type != null && ALL.contains(type);
    }

    /** Whether a slip of this type may point at a purchase invoice. Never required, only allowed. */
    public static boolean supportsPurchaseInvoiceLink(String type) {
        return type != null && PURCHASE_LINKABLE.contains(type);
    }

    /**
     * Whether a slip of this type and amount may NOT be paid (even partially) in cash — must go
     * through chuyển khoản instead. See {@link #CASH_LIMIT_APPLICABLE}/{@link #CASH_LIMIT_THRESHOLD}.
     */
    public static boolean requiresBankTransfer(String type, BigDecimal amount) {
        return type != null && CASH_LIMIT_APPLICABLE.contains(type)
                && amount != null && amount.compareTo(CASH_LIMIT_THRESHOLD) > 0;
    }

    /** Vietnamese display label, e.g. {@code OPERATIONAL -> "Chi phí vận hành"}. */
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

    /** Ordered map of type code -> Vietnamese label, for building the type dropdown. */
    public static Map<String, String> vietnameseLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String type : ALL) {
            labels.put(type, vietnameseName(type));
        }
        return labels;
    }
}
