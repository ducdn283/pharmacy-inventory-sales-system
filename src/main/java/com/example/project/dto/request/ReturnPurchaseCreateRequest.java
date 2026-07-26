package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Form backing the "Create supplier return" screen. The Owner picks one received purchase invoice,
 * then chooses how many units of each of its lines to return to the supplier, plus a reason.
 *
 * <p>No money is posted from here: the refund is computed server-side from each
 * batch's import cost and only recorded on the slip. Collecting it back from the supplier belongs to
 * the Income module.</p>
 */
@Getter
@Setter
public class ReturnPurchaseCreateRequest {

    /** The original purchase invoice being returned against. */
    private Integer purchaseId;

    /** Reason for the return (required). */
    private String reason;

    /** Optional free-text note. */
    private String note;

    /** One entry per candidate purchase line; blank/zero rows are dropped server-side. */
    private List<ReturnPurchaseLineRequest> items = new ArrayList<>();
}
