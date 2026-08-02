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
     * Tiền chuyển khoản chi ra trong ca (Σ {@code Expense.paidByBanking}).
     * {@code expectedClosingCash} — đối chiếu cuối ca là đếm tiền trong két, chuyển khoản không qua két.
     */
    private BigDecimal totalBankingOut;

    /**
     * {@code totalBankingIn − totalBankingOut}. Chuyển khoản KHÔNG có đối chiếu
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

    /**
     * Phần quỹ còn THIẾU so với số dự kiến = {@code |cashDiscrepancy|} khi âm, ngược lại 0.
     *
     * <p>Doanh thu và quỹ là hai con số khác nhau: doanh thu cộng từ các hóa đơn (số ghi nhận, dùng
     * cho kỳ tính thuế), còn quỹ là tiền mặt thật thu lại được của người trực khi kết ca. Bán 1.200.000
     * mà két thiếu 200.000 thì ca vẫn ghi doanh thu 1.200.000, quỹ chỉ nhận 1.000.000 — phần 200.000
     * là khoản phải thu lại của người trực, thu bằng một phiếu thu riêng.</p>
     */
    private BigDecimal cashShortage;

    /** Đã có phiếu thu gắn vào ca này chưa (khác Từ chối) — có rồi thì không tạo phiếu thứ hai. */
    private Integer shortageIncomeId;
    private String shortageIncomeCode;
    private String shortageIncomeStatus;

    /** Ca đã chốt, còn thiếu quỹ và chưa có phiếu thu nào gắn vào ⇒ hiện nút tạo phiếu thu. */
    private boolean canCollectShortage;
}
