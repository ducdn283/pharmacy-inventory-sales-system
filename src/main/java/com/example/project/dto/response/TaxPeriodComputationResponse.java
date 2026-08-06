package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * A tax period computed live from the transactions in it — nothing is stored. This is what the
 * "xem trước kỳ hiện tại" screen renders, and what pre-fills the figures when the period is
 * eventually closed. The docx allows either ("chốt tay hoặc tính runtime rồi lưu lại"), so the
 * numbers are a starting point, not a verdict.
 *
 * <p>Each VAT total is exposed together with the two components it was built from, because an
 * accountant checking a declaration needs to see <em>why</em> a figure moved, not just the net.</p>
 */
@Getter
@AllArgsConstructor
public class TaxPeriodComputationResponse {

    private String periodLabel;
    private String startDateDisplay;
    private String endDateDisplay;

    private Integer revenueGroup;
    private String revenueGroupDisplay;

    /**
     * {@code group == 3} on its own terms — no longer means "GTGT uses the deduction method" (see
     * {@code TaxRevenueGroup.DEDUCTION}'s javadoc). Kept for callers that still need group identity;
     * the GTGT-deduction-breakdown block on the tax-period screens it used to gate is permanently
     * hidden now (hardcoded off in the template), since no group uses that method any more.
     */
    private boolean deductionGroup;
    private boolean taxExempt;

    /**
     * GTGT method: true whenever the period is not tax-exempt — <strong>both</strong> group 2 and
     * group 3 now pay a flat percentage of revenue instead of output minus input.
     */
    private boolean percentageMethod;

    /** Revenue of the period: sales that are "còn hiệu lực" plus hoa hồng NCC and hàng biếu tặng. */
    private BigDecimal periodRevenue;

    /**
     * Doanh thu tính thuế TNCN — {@code periodRevenue} cộng thêm các khoản riêng cho TNCN (tiền đền
     * bù của nhân viên, giá vốn hàng thừa kiểm kê không rõ nguồn gốc, phần nhà thuốc giữ lại khi hoàn
     * tiền khách &lt;100%) — xem {@code TaxperiodsnapshotService.taxableIncomeRevenueOf}. Khác
     * {@code taxableIncome} (đã trừ chi phí hợp lý) và khác {@code periodRevenue} (GTGT) mỗi khi có
     * ít nhất một trong ba khoản trên phát sinh trong kỳ.
     */
    private BigDecimal taxableIncomeRevenue;

    /** The percentage-method rate as a human number (e.g. {@code 1.00} for 1%). */
    private BigDecimal directVatRatePercent;

    // --- output VAT. vatOutputFromSales/vatOutput are the same figure now (revenue × 1%);
    //     vatOutputReturnDeduction is always zero — kept as separate fields only so the DTO shape is
    //     unchanged, since the "Nhóm 3 khấu trừ" block that showed them broken out is hidden for good.
    private BigDecimal vatOutputFromSales;
    private BigDecimal vatOutputReturnDeduction;
    private BigDecimal vatOutput;

    // --- input VAT. Always zero now — no group deducts input VAT against output VAT any more.
    //     Kept as fields (never removed from the DTO, never shown) because Taxperiodsnapshot's
    //     vatInput/vatCarryforwardIn/vatCarryforwardOut columns stay in the schema unchanged.
    private BigDecimal vatInputFromPurchases;
    private BigDecimal vatInputReturnReversal;
    private BigDecimal vatInput;

    private BigDecimal vatCarryforwardIn;
    private BigDecimal vatCarryforwardOut;
    private BigDecimal vatPayable;

    // --- personal income tax. Only populated (and only meaningful) when pitCostMethod is true.
    private BigDecimal costOfGoodsSold;
    private BigDecimal operatingCost;

    /**
     * Loss on supplier returns not refunded in full ({@code originalLineValue - lineRefund} summed
     * across the period's approved supplier-return lines) — a chi phí hợp lý per {@code
     * Tax-Invoice.xlsx}, sheet "03_Cong_Thuc_TNCN". Zero whenever no supplier return fell short.
     */
    private BigDecimal supplierReturnShortfall;

    private BigDecimal taxableIncome;
    private BigDecimal incomeTax;

    /**
     * PIT rate as a human number: {@code 0.50} for group 2 on the revenue method, {@code 15.00} for
     * group 2 on the profit method, {@code 17.00} for group 3.
     */
    private BigDecimal incomeTaxRatePercent;

    /**
     * Tổng thuế phải nộp trong kỳ = {@code vatPayable + incomeTax} (cùng công thức
     * {@code PricesettingService.totalTax} đã dùng cho một sản phẩm, áp cho cả kỳ). Không có cột
     * riêng trên {@code Taxperiodsnapshot} — tính lại mỗi lần từ hai cột đã lưu sẵn
     * ({@code vatOutput}, {@code incomeTax}), giống mọi tổng khác trong DTO này.
     */
    private BigDecimal totalTaxPayable;

    /** How many source documents fell in the period — a sanity check for an empty-looking result. */
    private int invoiceCount;
    private int customerReturnCount;
    private int purchaseInvoiceCount;
    private int supplierReturnCount;

    /** Purchase invoices inside the period that were skipped as not deductible. */
    private int nonDeductiblePurchaseCount;

    /** Label of the period supplying {@code vatCarryforwardIn}; null when this is the first one. */
    private String previousPeriodLabel;

    /** True when a snapshot with this {@code periodLabel} has already been closed. */
    private boolean alreadyClosed;

    /**
     * TNCN method: true when this period's personal income tax is computed on profit (taxable-income
     * revenue minus chi phí hợp lý) rather than a flat rate on revenue. Always true for group 3;
     * for group 2 it follows {@code Financialsetting.taxCalculationMethod} — the Owner's choice
     * between the two. Independent from {@link #percentageMethod}, which is about GTGT only: a
     * group-2 period can be GTGT-percentage <em>and</em> PIT-cost-method at the same time.
     */
    private boolean pitCostMethod;
}
