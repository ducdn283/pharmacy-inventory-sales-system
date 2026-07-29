package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/** One row in the unified Approve List — aggregates Return/StockAdjustment/StockCount/ShiftReport items
 *  (PENDING plus a recent window of APPROVED/REJECTED, so a just-handled item stays visible for tracking). */
@Getter
@AllArgsConstructor
public class ApprovalItemResponse {

    private String type;

    /** Stable "TYPE_CODE:id" key used by the bulk-approve form checkbox — see ApprovalService's TYPE_CODE_* constants. */
    private String selector;

    private String code;

    private String requesterName;

    private Instant requestedAt;

    private String requestedAtDisplay;

    private String summary;

    private String status;

    private String statusCssClass;

    private boolean pending;

    private String detailUrl;

    private String approveUrl;

    private String rejectUrl;
}
