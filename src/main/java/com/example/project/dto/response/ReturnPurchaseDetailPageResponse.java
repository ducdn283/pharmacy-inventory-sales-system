package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Everything the supplier-return detail screen renders for one slip. */
@Getter
@AllArgsConstructor
public class ReturnPurchaseDetailPageResponse {

    private Integer id;
    private String returnCode;
    private Instant returnDate;
    private String returnDateDisplay;

    private Integer purchaseId;
    private String purchaseCode;
    private String supplierName;
    private String creatorName;

    private String returnType;
    private String returnTypeDisplay;
    private String reason;
    private String note;

    private String status;
    private String statusCssClass;
    private String approvedAtDisplay;

    private int itemCount;
    private int totalQuantity;

    private BigDecimal totalRefund;
    private BigDecimal offsetDebtAmount;

    /** Giá trị hàng trả lại chưa thuế = Σ preTaxAmount của các dòng. */
    private BigDecimal totalPreTaxRefund;
    /** Thuế GTGT ĐẦU VÀO đã khấu trừ nay phải giảm trừ (0 nếu Nhóm 2) = Return.totalVATRefund. */
    private BigDecimal totalVATRefund;
    /** Nhóm 3/4 mới có thuế đầu vào để đảo — dùng để chú thích đúng ngữ cảnh trên màn hình. */
    private boolean deductionGroup;

    private List<ReturnPurchaseDetailItemResponse> items;
}
