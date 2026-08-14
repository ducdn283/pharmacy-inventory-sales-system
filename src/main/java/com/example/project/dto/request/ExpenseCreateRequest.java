package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Form của màn "tạo phiếu chi". {@code applicantID} luôn là người dùng hiện tại;
 * {@code customerID}/{@code supplierID} được suy ra server-side từ chứng từ gắn kèm (nếu có).
 * {@code shiftReportID} và {@code accountID} không được form này gửi lên.
 */
@Getter
@Setter
public class ExpenseCreateRequest {

    /** Một trong các giá trị của {@link com.example.project.constant.ExpenseType}. */
    private String expenseType;

    /**
     * Phiếu trả hàng của khách mà phiếu chi này hoàn tiền. Bắt buộc khi {@code expenseType} là
     * {@link com.example.project.constant.ExpenseType#RETURN_REFUND_PAYOUT}, bỏ qua ở loại khác.
     * Khi có, server luôn ghi đè {@link #amount} bằng đúng số tiền hoàn của phiếu trả — số client
     * gửi lên không được tin.
     */
    private Integer returnId;

    /**
     * Phiếu nhập mà phiếu chi này thanh toán. Chỉ đọc với các loại trong
     * {@link com.example.project.constant.ExpenseType#PURCHASE_LINKABLE}, và không bắt buộc dù
     * thuộc loại đó — trả NCC mà không gắn hóa đơn cụ thể vẫn hợp lệ. Khi có, {@link #amount} vẫn
     * do người dùng chọn (trả một phần là bình thường) nhưng không được vượt phần còn nợ của phiếu.
     */
    private Integer purchaseId;

    /** yyyy-MM-dd from the date input; defaults to today when blank. */
    private String date;

    private String reason;

    /**
     * Số tiền chi lần này — <strong>không phải</strong> tổng nghĩa vụ của chứng từ được gắn.
     *
     * <p>Một phiếu chi là một lần chi tiền và không sửa được: nợ 50.000 mà hôm nay trả 30.000 thì
     * phiếu này là 30.000, hôm sau trả nốt là một phiếu khác. Nghĩa vụ còn lại nằm ở chứng từ
     * (phiếu nhập / phiếu trả hàng), không nằm ở đây. Vì vậy không còn {@code fullyPaid} lẫn
     * {@code paid}: {@code paid} luôn bằng {@code amount}.</p>
     */
    private BigDecimal amount;

    private BigDecimal paidByCash;

    private BigDecimal paidByBanking;

    private String note;
}
