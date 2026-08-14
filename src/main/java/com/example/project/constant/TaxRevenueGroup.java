package com.example.project.constant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Nhóm doanh thu hộ kinh doanh — quyết định cách tính thuế của nhà thuốc.
 *
 * <p>Giá trị nằm ở hai nơi, class này là định nghĩa duy nhất: {@code Financialsetting.revenueGroup}
 * (nhóm đang áp dụng) và {@code Taxperiodsnapshot.periodTaxType} (nhóm áp dụng cho kỳ sau).</p>
 *
 * <p>Nhóm 4 không nằm trong phạm vi hệ thống — kỳ khai báo luôn là quý, không có nhánh tháng.</p>
 */
public final class TaxRevenueGroup {

    /** Dưới ngưỡng 1 — miễn thuế hoàn toàn, không kê khai GTGT. */
    public static final int EXEMPT = 1;

    /** Từ ngưỡng 1 đến ngưỡng 2 — GTGT tính trực tiếp trên doanh thu, không có đầu vào để khấu trừ. */
    public static final int DIRECT = 2;

    /**
     * Trên ngưỡng 2. Tên gọi "DEDUCTION" mang tính lịch sử — hiện Nhóm 3 cũng tính GTGT trực tiếp
     * trên doanh thu như Nhóm 2 ({@link #DIRECT_VAT_RATE}), không còn khấu trừ đầu vào; chỉ riêng
     * thuế TNCN ({@link #GROUP3_PIT_RATE}) vẫn tính theo lợi nhuận. {@link #isDeductionGroup} vẫn
     * giữ nghĩa {@code group >= 3} cho các nơi khác cần biết định danh nhóm (đảo GTGT khi trả hàng
     * NCC, hiển thị giá) — không còn nghĩa là "nhóm này khấu trừ GTGT".
     */
    public static final int DEDUCTION = 3;

    /** Các nhóm có thể chọn, theo thứ tự tăng dần. */
    public static final List<Integer> ALL = List.of(EXEMPT, DIRECT, DEDUCTION);

    /** Ngưỡng doanh thu năm giữa Nhóm 1 và Nhóm 2 — 1 tỷ đồng. Hardcode theo luật, không cấu hình theo từng nhà thuốc. */
    public static final BigDecimal THRESHOLD_1 = new BigDecimal("1000000000.00");

    /** Ngưỡng doanh thu năm giữa Nhóm 2 và Nhóm 3 — 3 tỷ đồng. */
    public static final BigDecimal THRESHOLD_2 = new BigDecimal("3000000000.00");

    /** Thuế suất GTGT trên doanh thu (Nhóm 2 và Nhóm 3): 1% — mức bán lẻ/bán buôn hàng hóa. */
    public static final BigDecimal DIRECT_VAT_RATE = new BigDecimal("0.01");

    /**
     * TNCN Cách 1 (theo doanh thu, chỉ Nhóm 2): 0,5% trên phần doanh thu tính TNCN VƯỢT
     * {@link #THRESHOLD_1} (khác {@link #DIRECT_VAT_RATE} — GTGT tính trên toàn bộ doanh thu, không
     * trừ ngưỡng). Nhóm 3 luôn dùng {@link #GROUP3_PIT_RATE} theo lợi nhuận.
     */
    public static final BigDecimal DIRECT_PIT_RATE = new BigDecimal("0.005");

    /**
     * TNCN Cách 2 (theo lợi nhuận = doanh thu − chi phí hợp lý), không trừ ngưỡng nào — lựa chọn tùy
     * ý của Nhóm 2 qua {@code Financialsetting.taxCalculationMethod}. Nhóm 3 dùng
     * {@link #GROUP3_PIT_RATE} riêng, cao hơn.
     */
    public static final BigDecimal DEDUCTION_PIT_RATE = new BigDecimal("0.15");

    /** TNCN theo lợi nhuận của Nhóm 3: 17% trên thu nhập chịu thuế. */
    public static final BigDecimal GROUP3_PIT_RATE = new BigDecimal("0.17");

    private static final Map<Integer, String> LABELS = Map.of(
            EXEMPT, "Nhóm 1 — dưới ngưỡng 1 (miễn thuế)",
            DIRECT, "Nhóm 2 — từ ngưỡng 1 đến ngưỡng 2 (tính trực tiếp trên doanh thu)",
            DEDUCTION, "Nhóm 3 — trên ngưỡng 2 (phương pháp khấu trừ)");

    /** {@code group >= 3} — chỉ còn dùng cho định danh nhóm (hiển thị, trả hàng NCC), không còn nghĩa "GTGT khấu trừ". */
    public static boolean isDeductionGroup(Integer group) {
        return group != null && group >= DEDUCTION;
    }

    /** Nhóm 1 không kê khai gì; kỳ vẫn được chốt để giữ chuỗi liên tục. */
    public static boolean isTaxExempt(Integer group) {
        return group != null && group == EXEMPT;
    }

    /** Giá trị có phải một nhóm hệ thống nhận biết được không. */
    public static boolean isKnown(Integer group) {
        return group != null && ALL.contains(group);
    }

    /** Nhãn tiếng Việt của nhóm; giá trị lạ vẫn hiện thay vì để trống. */
    public static String label(Integer group) {
        if (group == null) {
            return "—";
        }
        return LABELS.getOrDefault(group, "Nhóm " + group);
    }

    /** Nhãn ngắn cho ô bảng. */
    public static String shortLabel(Integer group) {
        return group == null ? "—" : "Nhóm " + group;
    }

    private TaxRevenueGroup() {
    }
}
