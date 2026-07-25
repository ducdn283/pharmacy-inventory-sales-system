package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Form backing the "Create customer return" screen. The user picks one completed sale invoice, then
 * chooses how many units of each of its lines to return, how the payout is split and a reason.
 *
 * <p>The refund amount is <strong>not</strong> taken from the client — the service computes it from
 * the original invoice-line sell prices. Debt offset is not chosen either: if the invoice still owes,
 * the refund first cancels that debt automatically (BA 2026-07-26). The client only decides how the
 * <em>remaining</em> payout is split between cash and bank transfer; the service validates that
 * {@code refundCash + refundBanking} equals exactly that remainder.</p>
 */
@Getter
@Setter
public class ReturnCreateRequest {

    /** The original sale invoice being returned against. */
    private Integer invoiceId;

    /** Cash part of the payout (after the automatic debt offset). */
    private BigDecimal refundCash;

    /** Bank-transfer part of the payout (after the automatic debt offset). */
    private BigDecimal refundBanking;

    /** Reason for the return (required). */
    private String reason;

    /** Optional free-text note. */
    private String note;

    /** One entry per candidate invoice line; blank/zero rows are dropped server-side. */
    private List<ReturnLineRequest> items = new ArrayList<>();
}
