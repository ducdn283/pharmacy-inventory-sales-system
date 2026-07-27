package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ShiftReportDetailPageResponse {

    private Integer id;

    private String shiftReportCode;

    private Integer cashierId;

    private String cashierName;

    private String shiftDateDisplay;

    private String shiftType;

    private String startTimeDisplay;

    private String endTimeDisplay;

    private BigDecimal openingCash;

    private Integer totalInvoices;

    private BigDecimal totalRevenue;

    private Integer totalReturns;

    private BigDecimal totalReturnAmount;

    private BigDecimal totalDebtCollected;

    private BigDecimal totalCashIn;

    private BigDecimal totalBankingIn;

    private BigDecimal totalCashOut;

    /**
     * Tiền chuyển khoản chi ra trong ca (Σ {@code Expense.paidByBanking}). Cố tình KHÔNG tham gia
     * {@code expectedClosingCash} — đối chiếu cuối ca là đếm tiền trong két, chuyển khoản không qua két.
     */
    private BigDecimal totalBankingOut;

    /**
     * {@code totalBankingIn − totalBankingOut}. Thuần thông tin: chuyển khoản KHÔNG có đối chiếu
     * kiểu tiền mặt (không có số đầu ca / thực đếm / chênh lệch) vì ngân hàng đã ghi nhận sẵn và
     * người trực ca không đếm được số dư tài khoản lúc giao ca. Không tham gia
     * {@code expectedClosingCash}. Không lưu DB — suy ra khi hiển thị.
     */
    private BigDecimal totalBankingNet;

    private BigDecimal expectedClosingCash;

    private BigDecimal actualClosingCash;

    private BigDecimal cashDiscrepancy;

    private String noteDiscrepancy;

    private String status;

    private String statusCssClass;

    private String approvedAtDisplay;

    private String note;

    private boolean canClose;
}
