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

    /** Revenue of the period: sales that are "còn hiệu lực" plus hàng biếu tặng. */
    private BigDecimal periodRevenue;

    /**
     * Doanh thu tính thuế TNCN — {@code periodRevenue} cộng thêm các khoản riêng cho TNCN (tiền đền
     * bù của nhân viên, giá vốn hàng thừa kiểm kê không rõ nguồn gốc, phần nhà thuốc giữ lại khi hoàn
     * tiền khách &lt;100%) — xem {@code TaxperiodsnapshotService.taxableIncomeRevenueOf}. Khác
     * {@code taxableIncome} (đã trừ chi phí hợp lý) và khác {@code periodRevenue} (GTGT) mỗi khi có
     * ít nhất một trong ba khoản trên phát sinh trong kỳ.
     */
    private BigDecimal taxableIncomeRevenue;

    /**
     * "Thu nhập phát sinh (thu nhập khác)" từ trả hàng một phần ({@code Ho_so_nghiep_vu_v2.xlsx},
     * sheet "05_Tra_Hang") — phần nhà thuốc GIỮ LẠI khi hoàn tiền khách ở tỷ lệ &lt;100%, một trong
     * ba khoản cộng vào {@link #taxableIncomeRevenue}. Tách riêng ra đây (thay vì chỉ nằm trong tổng)
     * để hiển thị được trên `tax-period/preview.html`, cùng tinh thần "mỗi tổng đi kèm các thành
     * phần đã tạo ra nó" của DTO này. Hai khoản còn lại (tiền đền bù nhân viên, giá vốn hàng thừa
     * kiểm kê không rõ nguồn gốc) chưa được tách riêng — chỉ nằm trong {@link #taxableIncomeRevenue}.
     */
    private BigDecimal customerReturnRetained;

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

    // --- P&L breakdown ("lợi nhuận sau thuế"), the 8-line framework the user handed over 2026-08-14:
    //     1. Doanh thu = periodRevenue; 2. revenueDeduction; 3. otherIncome; 4. costOfGoodsSold;
    //     5. operatingCost (+ supplierReturnShortfall, folded in — see profitBeforeTax's javadoc);
    //     6. profitBeforeTax = 1-2+3-4-5; 7. incomeTax (already above, same figure, no new field);
    //     8. netProfitAfterTax = 6-7. Only meaningful when pitCostMethod is true, same as
    //     costOfGoodsSold/operatingCost above — a revenue-method period never breaks its tax out of
    //     profit, so there is no "lợi nhuận sau thuế" to show for it either.

    /**
     * "Các khoản giảm trừ doanh thu" (line 2 of the framework) — always zero in this system: a
     * discount is already netted straight into {@code Invoice.total} at the point of sale (no
     * separate chiết khấu ledger), and a return is already excluded/netted at the source via the
     * "hóa đơn còn hiệu lực" replacement-invoice mechanism ({@code InvoiceRepository
     * .findValidInPeriod}) rather than tracked as a contra-revenue line — by the time {@link
     * #periodRevenue} is computed, both are already gone from it structurally. Exposed as a real
     * field (not omitted) so the 8-line shape stays intact on screen, even though it always renders
     * {@code 0đ} today.
     */
    private BigDecimal revenueDeduction;

    /**
     * "Thu nhập khác" (line 3) = {@link #taxableIncomeRevenue} − {@link #periodRevenue} — the three
     * TNCN-only add-ons {@code TaxperiodsnapshotService.taxableIncomeRevenueOf} folds in (tiền đền
     * bù nhân viên, giá vốn hàng thừa kiểm kê không rõ nguồn gốc, và {@link #customerReturnRetained}
     * — the one the user named explicitly: "từ hàng bán trả lại không hoàn 100% tiền"). The other two
     * are not broken out on their own field, same as before this change.
     */
    private BigDecimal otherIncome;

    /**
     * "Lợi nhuận trước thuế" (line 6) = {@link #taxableIncomeRevenue} − {@link #costOfGoodsSold} −
     * {@link #operatingCost} − {@link #supplierReturnShortfall} — the same subtraction {@link
     * #taxableIncome} already does, <strong>except never floored at zero</strong>: {@link
     * #taxableIncome} floors because a loss-making quarter owes no tax, but a P&L figure must be able
     * to show a real loss as negative rather than hide it as {@code 0đ}. {@code
     * supplierReturnShortfall} is folded into this line rather than kept as a 9th one the user did
     * not ask for — it is a real operating loss (a supplier not refunding a return in full), so it
     * reads naturally as part of "chi phí hoạt động". Zero whenever {@link #pitCostMethod} is false.
     */
    private BigDecimal profitBeforeTax;

    /**
     * "Lợi nhuận sau thuế" (line 8) = {@link #profitBeforeTax} − {@link #incomeTax} (line 7 is {@link
     * #incomeTax} itself — already exactly {@code max(profitBeforeTax, 0) × incomeTaxRatePercent}
     * when {@link #pitCostMethod} is true, so no separate "line 7" field was added). Zero whenever
     * {@link #pitCostMethod} is false.
     */
    private BigDecimal netProfitAfterTax;

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
