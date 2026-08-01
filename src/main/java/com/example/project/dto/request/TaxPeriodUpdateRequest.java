package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Correcting the most recently closed period — the escape hatch for a period closed with the wrong
 * numbers, chosen (2026-07-27) over a destructive "hủy chốt" that would delete the snapshot.
 *
 * <p>The three VAT inputs are editable because a snapshot is a filed declaration, and a filed
 * declaration gets amended, not recomputed: input-VAT deductibility is time dependent (an unpaid
 * invoice stops being deductible once its due date passes), so silently re-running the calculation
 * would rewrite history every time somebody opened the screen. To see what the books say <em>now</em>,
 * the preview screen already computes any quarter on demand.</p>
 *
 * <p>{@code vatCarryforwardOut} is deliberately absent: it is re-derived from the three fields below
 * so that "còn phải nộp" and "chuyển kỳ sau" stay two sides of one subtraction. Letting both be typed
 * would allow a period that simultaneously owes tax and carries credit forward.</p>
 */
@Getter
@Setter
public class TaxPeriodUpdateRequest {

    private BigDecimal vatOutput;

    private BigDecimal vatInput;

    private BigDecimal vatCarryforwardIn;

    /** Thuế TNCN của kỳ — typed as-is, like the VAT lines above. */
    private BigDecimal incomeTax;

    /** Reference figure only — the docx calls it "tham chiếu, không phải theo dõi liên tục". */
    private BigDecimal cashBalanceAtPeriodEnd;

    private String note;
}
