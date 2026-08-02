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
 * <p>The two branches deliberately mirror {@code TaxperiodsnapshotService.computePeriod()} so a
 * price decision and the quarterly declaration cannot tell the Owner different stories:</p>
 *
 * <table>
 *   <tr><th>Group</th><th>GTGT</th><th>TNCN</th></tr>
 *   <tr><td>1 — miễn thuế</td><td>—</td><td>—</td></tr>
 *   <tr><td>2/3 — trực tiếp</td><td>{@code giá bán × 1%}</td><td>{@code giá bán × 0,5%}</td></tr>
 * </table>
 *
 * <p><strong>BA quyết định trực tiếp (chưa có tài liệu):</strong> group 3 no longer offsets input
 * VAT against output VAT — it now projects identically to group 2 (see {@code
 * TaxRevenueGroup.DEDUCTION}'s javadoc). This panel does not yet reflect group 2's optional
 * profit-based TNCN ({@code Financialsetting.taxCalculationMethod}) — it always shows the flat
 * revenue-based figure.</p>
 *
 * <p>The product's own VAT rate is <strong>irrelevant to every group now</strong> — the percentage
 * method taxes revenue flat, so an 8% product and a 0% product are charged identically. That
 * surprises people, so {@link #productVatApplies} (always {@code false} now) lets the screen say it
 * out loud unconditionally.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingTaxProjectionResponse {

    private Integer group;
    private String groupLabel;
    /** Group 1 — declares nothing at all. */
    private boolean exempt;
    /**
     * {@code group == 3} on its own terms — <strong>always {@code false}</strong> now for the
     * purpose this field used to serve (no group offsets input VAT against output VAT any more), so
     * the per-batch "GTGT đầu vào" column it used to gate in {@code price-settings.html} never shows.
     */
    private boolean deduction;
    /** Not exempt — a flat percentage of revenue, for groups 2 and 3 alike. */
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
