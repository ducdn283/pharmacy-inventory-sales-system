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

    /** Số tiền NCC THỰC hoàn = Σ lineRefund (đã áp tỷ lệ NCC chấp nhận hoàn). */
    private BigDecimal totalRefund;
    /** Phần đã CẤN TRỪ vào công nợ nhà thuốc nợ NCC = MIN(totalRefund, dư nợ phiếu nhập lúc duyệt). */
    private BigDecimal offsetDebtAmount;
    /** Tiền thật NCC còn phải hoàn sau bù trừ = totalRefund − offsetDebtAmount (phiếu thu ghi nhận). */
    private BigDecimal cashRefundDue;

    /** Tỷ lệ % NCC chấp nhận hoàn cho phiếu này (Return.appliedRefundRate). */
    private BigDecimal appliedRefundRate;
    /** Tổng giá trị nhập GỐC 100% của hàng trả = Σ originalLineValue. */
    private BigDecimal totalOriginalValue;
    /** Khoản LỖ do NCC không hoàn đủ = totalOriginalValue − totalRefund (chi phí hợp lý, cần chứng từ). */
    private BigDecimal lossAmount;

    /** Giá trị hàng trả lại chưa thuế = Σ preTaxAmount của các dòng. */
    private BigDecimal totalPreTaxRefund;
    /** Thuế GTGT ĐẦU VÀO đã khấu trừ nay phải giảm trừ (0 nếu Nhóm 2) = Return.totalVATRefund. */
    private BigDecimal totalVATRefund;
    /** Nhóm 3/4 mới có thuế đầu vào để đảo — dùng để chú thích đúng ngữ cảnh trên màn hình. */
    private boolean deductionGroup;

    private List<ReturnPurchaseDetailItemResponse> items;
}
