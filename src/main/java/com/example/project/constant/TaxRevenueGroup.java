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

    /** Above threshold 2 — deduction method: input VAT is offset against output VAT. */
    public static final int DEDUCTION = 3;

    /** Selectable groups, in ascending order. */
    public static final List<Integer> ALL = List.of(EXEMPT, DIRECT, DEDUCTION);

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
     * Personal income tax under the {@link #DEDUCTION} method: <strong>15%</strong> of taxable
     * income, i.e. revenue less legitimate costs, rather than a flat slice of revenue. That is the
     * whole reason group 3 is described as "tính theo lợi nhuận" — a loss-making quarter owes
     * nothing, which a percentage of revenue could never express.
     */
    public static final BigDecimal DEDUCTION_PIT_RATE = new BigDecimal("0.15");

    private static final Map<Integer, String> LABELS = Map.of(
            EXEMPT, "Nhóm 1 — dưới ngưỡng 1 (miễn thuế)",
            DIRECT, "Nhóm 2 — từ ngưỡng 1 đến ngưỡng 2 (tính trực tiếp trên doanh thu)",
            DEDUCTION, "Nhóm 3 — trên ngưỡng 2 (phương pháp khấu trừ)");

    /**
     * Whether input VAT may be offset against output VAT for this group. Mirrors
     * {@code ReturnPurchaseService.isDeductionGroup()} — the two must agree, otherwise a supplier
     * return would reverse input VAT that the tax period never counted in the first place.
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
