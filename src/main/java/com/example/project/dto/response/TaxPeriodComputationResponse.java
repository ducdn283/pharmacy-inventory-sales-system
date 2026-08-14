package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Kết quả tính một kỳ thuế trực tiếp từ chứng từ, không lưu gì cả — dùng cho màn "xem trước" và để
 * điền sẵn số liệu khi chốt kỳ.
 */
@Getter
@AllArgsConstructor
public class TaxPeriodComputationResponse {

    private String periodLabel;
    private String startDateDisplay;
    private String endDateDisplay;

    private Integer revenueGroup;
    private String revenueGroupDisplay;

    /** {@code group == 3} — chỉ còn là định danh nhóm, không còn nghĩa "GTGT khấu trừ". */
    private boolean deductionGroup;
    private boolean taxExempt;

    /** Phương pháp GTGT trực tiếp trên doanh thu — true khi kỳ không miễn thuế (áp dụng cả Nhóm 2 lẫn Nhóm 3). */
    private boolean percentageMethod;

    /** Doanh thu của kỳ: hóa đơn còn hiệu lực cộng hàng biếu tặng. */
    private BigDecimal periodRevenue;

    /**
     * Doanh thu tính thuế TNCN = {@link #periodRevenue} cộng thêm các khoản riêng cho TNCN (tiền đền
     * bù của nhân viên, giá vốn hàng thừa kiểm kê không rõ nguồn gốc, phần nhà thuốc giữ lại khi hoàn
     * tiền khách &lt;100%). Chỉ dùng cho TNCN, không dùng cho GTGT.
     */
    private BigDecimal taxableIncomeRevenue;

    /**
     * "Thu nhập phát sinh (thu nhập khác)" từ trả hàng một phần — phần nhà thuốc GIỮ LẠI khi hoàn
     * tiền khách ở tỷ lệ &lt;100%, một trong ba khoản cộng vào {@link #taxableIncomeRevenue}. Tách
     * riêng để hiển thị trên `tax-period/preview.html`.
     */
    private BigDecimal customerReturnRetained;

    /** Thuế suất GTGT dạng phần trăm cho người đọc (vd {@code 1.00} nghĩa là 1%). */
    private BigDecimal directVatRatePercent;

    // --- GTGT đầu ra. vatOutputFromSales/vatOutput hiện là cùng một số (doanh thu × 1%);
    //     vatOutputReturnDeduction luôn bằng 0 — giữ field riêng để không đổi shape DTO.
    private BigDecimal vatOutputFromSales;
    private BigDecimal vatOutputReturnDeduction;
    private BigDecimal vatOutput;

    // --- GTGT đầu vào. Luôn bằng 0 — không nhóm nào còn khấu trừ đầu vào nữa.
    private BigDecimal vatInputFromPurchases;
    private BigDecimal vatInputReturnReversal;
    private BigDecimal vatInput;

    private BigDecimal vatCarryforwardIn;
    private BigDecimal vatCarryforwardOut;
    private BigDecimal vatPayable;

    // --- thuế TNCN. Chỉ có ý nghĩa khi pitCostMethod = true.
    private BigDecimal costOfGoodsSold;
    private BigDecimal operatingCost;

    /**
     * Khoản lỗ do NCC không hoàn đủ tiền khi trả hàng ({@code originalLineValue - lineRefund} cộng
     * dồn trên các dòng trả hàng NCC đã duyệt trong kỳ) — một chi phí hợp lý. Bằng 0 nếu không có
     * trường hợp nào.
     */
    private BigDecimal supplierReturnShortfall;

    private BigDecimal taxableIncome;
    private BigDecimal incomeTax;

    /** Thuế suất TNCN dạng phần trăm: 0,50 (Nhóm 2 Cách 1), 15,00 (Nhóm 2 Cách 2), 17,00 (Nhóm 3). */
    private BigDecimal incomeTaxRatePercent;

    // --- Phần bóc tách lợi nhuận (chỉ có ý nghĩa khi pitCostMethod = true):
    //     Doanh thu (periodRevenue) − giảm trừ (revenueDeduction) + thu nhập khác (otherIncome)
    //     − giá vốn (costOfGoodsSold) − chi phí hoạt động (operatingCost, đã gồm
    //     supplierReturnShortfall) = lợi nhuận trước thuế (profitBeforeTax);
    //     lợi nhuận trước thuế − thuế TNCN (incomeTax) = lợi nhuận sau thuế (netProfitAfterTax).

    /**
     * "Các khoản giảm trừ doanh thu" — luôn bằng 0 trong hệ thống này: chiết khấu đã được trừ thẳng
     * vào {@code Invoice.total} lúc bán, còn hàng trả lại đã được loại trừ tại nguồn qua cơ chế hóa
     * đơn thay thế. Giữ field để hiển thị đủ dòng, dù luôn ra 0đ.
     */
    private BigDecimal revenueDeduction;

    /**
     * "Thu nhập khác" = {@link #taxableIncomeRevenue} − {@link #periodRevenue} — gồm tiền đền bù
     * nhân viên, giá vốn hàng thừa kiểm kê không rõ nguồn gốc, và {@link #customerReturnRetained}.
     */
    private BigDecimal otherIncome;

    /**
     * "Lợi nhuận trước thuế" = {@link #taxableIncomeRevenue} − {@link #costOfGoodsSold} −
     * {@link #operatingCost} − {@link #supplierReturnShortfall}, <strong>không floor tại 0</strong>
     * (khác {@link #taxableIncome}, vốn floor vì một quý lỗ không phải nộp thuế âm). Bằng 0 khi
     * {@link #pitCostMethod} là false.
     */
    private BigDecimal profitBeforeTax;

    /** "Lợi nhuận sau thuế" = {@link #profitBeforeTax} − {@link #incomeTax}. Bằng 0 khi {@link #pitCostMethod} là false. */
    private BigDecimal netProfitAfterTax;

    /** Tổng thuế phải nộp trong kỳ = {@code vatPayable + incomeTax}. Không lưu cột riêng, tính lại mỗi lần. */
    private BigDecimal totalTaxPayable;

    /** Số chứng từ phát sinh trong kỳ — dùng để kiểm tra nhanh khi kết quả trông trống rỗng. */
    private int invoiceCount;
    private int customerReturnCount;
    private int purchaseInvoiceCount;
    private int supplierReturnCount;

    /** Số phiếu nhập trong kỳ bị loại vì không đủ điều kiện khấu trừ. */
    private int nonDeductiblePurchaseCount;

    /** Nhãn kỳ cung cấp {@code vatCarryforwardIn}; null nếu đây là kỳ đầu tiên. */
    private String previousPeriodLabel;

    /** True khi kỳ với {@code periodLabel} này đã được chốt. */
    private boolean alreadyClosed;

    /**
     * Phương pháp TNCN: true khi tính theo lợi nhuận (doanh thu tính TNCN trừ chi phí hợp lý) thay vì
     * tỷ lệ cố định trên doanh thu. Luôn true với Nhóm 3; Nhóm 2 theo lựa chọn
     * {@code Financialsetting.taxCalculationMethod}. Độc lập với {@link #percentageMethod} (chỉ nói
     * về GTGT) — một kỳ Nhóm 2 có thể vừa GTGT-theo-tỷ-lệ vừa TNCN-theo-lợi-nhuận.
     */
    private boolean pitCostMethod;
}
