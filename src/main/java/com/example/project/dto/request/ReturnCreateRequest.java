package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Form backing the "Create customer return" screen. The user picks one completed sale invoice, then
 * chooses how many units of each of its lines to return, plus a reason.
 *
 * <p>No money is posted from here: the refund is computed server-side from the
 * original invoice-line sell prices and only recorded on the slip. How the customer is actually paid
 * back belongs to the Expense module.</p>
 */
@Getter
@Setter
public class ReturnCreateRequest {

    /** The original sale invoice being returned against. */
    private Integer invoiceId;

    /** Reason for the return (required). */
    private String reason;

    /** Optional free-text note. */
    private String note;

    /** One entry per candidate invoice line; blank/zero rows are dropped server-side. */
    private List<ReturnLineRequest> items = new ArrayList<>();
}
