package com.example.project.constant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The household-business revenue group ("nhóm doanh thu hộ kinh doanh") that decides how the
 * pharmacy is taxed, per {@code docs/context/Pharmacy-Database-Description.docx}.
 *
 * <p>The value lives in two places and this class is the single definition of what it means:
 * {@code Financialsetting.revenueGroup} (the group in force <em>right now</em>) and
 * {@code Taxperiodsnapshot.nextPeriodTaxType} (the group to apply <em>after</em> that snapshot's
 * {@code endDate}). Both are plain {@code int} columns.</p>
 *
 * <p><strong>Group 4 is deliberately out of scope</strong> (BA, 2026-07-27). The docx describes it
 * as a monthly-declaration group, but {@code nextPeriodTaxType} is documented as {@code 1/2/3}, the
 * Financial Settings screen only offers three options and only two revenue thresholds exist. Keeping
 * it out means a declaration period is <em>always</em> a calendar quarter, which is why
 * {@code TaxperiodsnapshotService} has no month branch. {@link #isDeductionGroup(Integer)} is still
 * written as {@code >= 3} so that a group-4 row inserted by hand does not silently fall back to the
 * no-deduction path — it matches {@code ReturnPurchaseService.isDeductionGroup()}, which already
 * encodes the same rule for reversing input VAT on a supplier return.</p>
 */
public final class TaxRevenueGroup {

    /** Below threshold 1 — tax exempt. No VAT is declared at all. */
    public static final int EXEMPT = 1;

    /** Between threshold 1 and 2 — taxed directly on revenue, so there is no input VAT to deduct. */
    public static final int DIRECT = 2;

    /**
     * Above threshold 2. The name is historical — this group used to offset input VAT against output
     * VAT for its GTGT liability. <strong>BA quyết định trực tiếp (chưa có tài liệu): group 3's GTGT
     * is now computed directly on revenue too</strong>, exactly like group 2 ({@link #DIRECT_VAT_RATE}
     * — see {@code TaxperiodsnapshotService.computePeriod}); only its personal income tax ({@link
     * #GROUP3_PIT_RATE}) still works off profit rather than a flat rate. {@link #isDeductionGroup}
     * keeps its literal {@code group >= 3} meaning for callers that still care about group identity
     * for other reasons (supplier-return VAT reversal, price-projection display) — it no longer
     * implies "this group's GTGT is deducted".
     */
    public static final int DEDUCTION = 3;

    /** Selectable groups, in ascending order. */
    public static final List<Integer> ALL = List.of(EXEMPT, DIRECT, DEDUCTION);

    /**
     * Annual revenue threshold between {@link #EXEMPT} and {@link #DIRECT} — 1 tỷ đồng.
     *
     * <p>Was configurable via {@code Financialsetting.annualRevenueThreshold1} until it was dropped
     * from the schema (2026-08-04, per {@code Tax-Invoice.xlsx}): the group boundaries are fixed by
     * law, not a per-pharmacy setting, so this is now the single hardcoded definition.</p>
     */
    public static final BigDecimal THRESHOLD_1 = new BigDecimal("1000000000.00");

    /** Annual revenue threshold between {@link #DIRECT} and {@link #DEDUCTION} — 3 tỷ đồng. See {@link #THRESHOLD_1}. */
    public static final BigDecimal THRESHOLD_2 = new BigDecimal("3000000000.00");

    /**
     * VAT rate applied to revenue under the {@link #DIRECT} method: <strong>1%</strong> for retail
     * and wholesale of goods, which is what a pharmacy does.
     *
     * <p>Hardcoded on purpose — the docx states the per-group GTGT/TNCN formulas are "FIX CỨNG
     * trong code", and {@code Financialsetting} has thresholds but no rate column. A different
     * sector would use a different rate (services are 5%), so this constant is only correct because
     * the whole system models one pharmacy.</p>
     *
     * <p>Its personal-income-tax twin is {@link #DIRECT_PIT_RATE}.</p>
     */
    public static final BigDecimal DIRECT_VAT_RATE = new BigDecimal("0.01");

    /**
     * Personal income tax under the {@link #DIRECT} method: <strong>0.5%</strong> of revenue for
     * retail and wholesale of goods — the twin of {@link #DIRECT_VAT_RATE}, declared in the same
     * quarterly filing.
     */
    public static final BigDecimal DIRECT_PIT_RATE = new BigDecimal("0.005");

    /**
     * Personal income tax on taxable income (doanh thu tính thuế TNCN − chi phí hợp lý), rather than
     * a flat slice of revenue — a loss-making quarter owes nothing, which a percentage of revenue
     * could never express.
     *
     * <p><strong>Group 2's optional profit method only</strong> (BA quyết định trực tiếp,
     * chưa có tài liệu — xem {@code TaxperiodsnapshotService}): since group 2 can now choose between
     * the flat {@link #DIRECT_PIT_RATE} and this profit-based rate via
     * {@code Financialsetting.taxCalculationMethod}, while group 3 always uses the profit method at
     * its own, higher rate ({@link #GROUP3_PIT_RATE}), the two can no longer share one constant.</p>
     */
    public static final BigDecimal DEDUCTION_PIT_RATE = new BigDecimal("0.15");

    /**
     * Personal income tax under the profit method for {@link #DEDUCTION} (group 3): <strong>17%</strong>
     * of taxable income — group 3's own rate, distinct from group 2's optional {@link
     * #DEDUCTION_PIT_RATE} (15%) even though both compute taxable income the same way.
     */
    public static final BigDecimal GROUP3_PIT_RATE = new BigDecimal("0.17");

    private static final Map<Integer, String> LABELS = Map.of(
            EXEMPT, "Nhóm 1 — dưới ngưỡng 1 (miễn thuế)",
            DIRECT, "Nhóm 2 — từ ngưỡng 1 đến ngưỡng 2 (tính trực tiếp trên doanh thu)",
            DEDUCTION, "Nhóm 3 — trên ngưỡng 2 (phương pháp khấu trừ)");

    /**
     * {@code group >= 3}. <strong>No longer means "GTGT is deducted for this group"</strong> — see
     * {@link #DEDUCTION}'s javadoc; neither {@code TaxperiodsnapshotService} nor {@code
     * PricesettingService} call this to decide a GTGT formula any more (group 3 projects identically
     * to group 2 in both). Kept for {@code ReturnPurchaseService.isDeductionGroup()} — whether a
     * supplier return still reverses input VAT, left unchanged since {@code vatInput} no longer
     * affects the amount payable either way — and for group-identity display (badges, labels).
     */
    public static boolean isDeductionGroup(Integer group) {
        return group != null && group >= DEDUCTION;
    }

    /** Group 1 declares nothing; a snapshot still exists to keep the period chain unbroken. */
    public static boolean isTaxExempt(Integer group) {
        return group != null && group == EXEMPT;
    }

    /** Whether the value is one of the groups this app knows about. */
    public static boolean isKnown(Integer group) {
        return group != null && ALL.contains(group);
    }

    /** Vietnamese label for a group; an unrecognised value renders verbatim rather than blank. */
    public static String label(Integer group) {
        if (group == null) {
            return "—";
        }
        return LABELS.getOrDefault(group, "Nhóm " + group);
    }

    /** Short label for table cells, where the full sentence does not fit. */
    public static String shortLabel(Integer group) {
        return group == null ? "—" : "Nhóm " + group;
    }

    private TaxRevenueGroup() {
    }
}
