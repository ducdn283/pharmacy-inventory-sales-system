package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * "Chốt kỳ thuế" — the only write on a tax period.
 *
 * <p>The VAT figures are deliberately <em>not</em> here: they are recomputed server-side from the
 * documents in the period at the moment of closing, exactly like Expense's refund payout ignores the
 * posted amount. A declaration is an obligation derived from the books, not a number somebody types.
 * The group to apply next is likewise derived, by {@code TaxperiodsnapshotService.autoNextGroup} —
 * not something the form collects. What the user does decide is the reference cash balance and a
 * note.</p>
 */
@Getter
@Setter
public class TaxPeriodCloseRequest {

    /**
     * The period the form was rendered for. Compared against the period actually due for closing so
     * a stale tab cannot close the wrong quarter.
     */
    private String periodLabel;

    /** Reference figure only — the docx calls it "tham chiếu, không phải theo dõi liên tục". */
    private BigDecimal cashBalanceAtPeriodEnd;

    private String note;
}
