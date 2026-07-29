package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * "Nếu bán 1 đơn vị cơ bản với giá hiện tại thì thuế ra sao" — the tax side of the Price Settings
 * detail modal, worked out for <strong>one base unit</strong> under the revenue group the pharmacy
 * is on <em>right now</em> ({@code TaxperiodsnapshotService.currentRevenueGroup()}, i.e. the last
 * snapshot's {@code nextPeriodTaxType}, not the raw {@code Financialsetting.revenueGroup}).
 *
 * <p>The three branches deliberately mirror {@code TaxperiodsnapshotService.computePeriod()} so a
 * price decision and the quarterly declaration cannot tell the Owner different stories:</p>
 *
 * <table>
 *   <tr><th>Group</th><th>GTGT</th><th>TNCN</th></tr>
 *   <tr><td>1 — miễn thuế</td><td>—</td><td>—</td></tr>
 *   <tr><td>2 — trực tiếp</td><td>{@code giá bán × 1%}</td><td>{@code giá bán × 0,5%}</td></tr>
 *   <tr><td>3 — khấu trừ</td><td>{@code VAT đầu ra − VAT đầu vào}</td><td>{@code 15% × (giá bán − giá vốn)}</td></tr>
 * </table>
 *
 * <p><strong>Two honest limits, surfaced as {@link #caveat} rather than hidden:</strong></p>
 * <ul>
 *   <li>Group 3's taxable income at period level is {@code doanh thu − giá vốn − chi phí vận hành}.
 *       Operating costs (điện, nước, lương) belong to the period, not to one unit, so the per-unit
 *       {@code incomeTax} here is an <em>upper bound</em>.</li>
 *   <li>Group 3's input VAT is only deductible from an invoice that passes
 *       {@code Purchaseinvoice.isValidForDeduction} (Điều 26 NĐ 181/2025), which is time-dependent.
 *       This projection assumes the batch's purchase invoice qualifies.</li>
 * </ul>
 *
 * <p>Under group 2 the product's own VAT rate is <strong>irrelevant</strong> — the percentage method
 * taxes revenue flat, so an 8% product and a 0% product are charged identically. That surprises
 * people, so {@link #productVatApplies} lets the screen say it out loud.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingTaxProjectionResponse {

    private Integer group;
    private String groupLabel;
    /** Group 1 — declares nothing at all. */
    private boolean exempt;
    /** Group 3 — input VAT is offset against output VAT. */
    private boolean deduction;
    /** Group 2 — a flat percentage of revenue. */
    private boolean direct;

    /** The product's own VAT rate as a percentage ({@code 8.00}), from override or type. */
    private BigDecimal productVatRatePercent;
    /** Where that rate came from, for the "vì sao lại là số này" line. */
    private String productVatRateSource;
    /** Whether the product's VAT rate affects the numbers below at all — false for groups 1 and 2. */
    private boolean productVatApplies;

    /**
     * Whether a cost basis exists at all, i.e. the product has at least one in-stock lot.
     *
     * <p>When false, every figure that needs the cost side stays <strong>{@code null}</strong> and
     * the screen shows {@code —}. Defaulting an unknown cost to zero would report the whole sell
     * price as profit, which is the most damaging thing a pricing screen could get wrong.</p>
     */
    private boolean costKnown;

    /** Base-unit sell price used as the revenue side (GROSS). */
    private BigDecimal sellPricePerBase;
    /** Average in-stock import price used as the cost side (GROSS); {@code null} with no stock. */
    private BigDecimal importPricePerBase;

    private BigDecimal outputVat;
    private BigDecimal inputVat;
    /** {@code outputVat − inputVat}. Negative means credit carried to the next period, not a refund. */
    private BigDecimal vatPayable;

    private BigDecimal incomeTaxBase;
    private BigDecimal incomeTaxRatePercent;
    private BigDecimal incomeTax;

    /** {@code vatPayable + incomeTax} — the whole tax bite on this one unit. */
    private BigDecimal totalTax;

    /** {@code sellPrice − importPrice}, before tax. Negative when the lot cost more than it sells for. */
    private BigDecimal grossMargin;
    /** {@code grossMargin − totalTax}. What the unit is actually worth to the pharmacy. */
    private BigDecimal netProfit;

    /** Vietnamese one-liner naming the formula actually applied. */
    private String formula;
    /** Vietnamese caveat, or {@code null} when the projection is exact. */
    private String caveat;
}
