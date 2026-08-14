package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * "Nếu bán 1 đơn vị cơ bản với giá hiện tại thì thuế ra sao" — tính cho <strong>một đơn vị cơ
 * bản</strong>, theo nhóm doanh thu hiện tại của nhà thuốc
 * ({@code TaxperiodsnapshotService.currentRevenueGroup()}). Công thức phải khớp với
 * {@code TaxperiodsnapshotService.computePeriod()} để không mâu thuẫn với số liệu kỳ thuế.
 *
 * <table>
 *   <tr><th>Nhóm</th><th>GTGT</th><th>TNCN</th></tr>
 *   <tr><td>1 — miễn thuế</td><td>—</td><td>—</td></tr>
 *   <tr><td>2/3 — trực tiếp</td><td>{@code giá bán × 1%}</td><td>{@code giá bán × 0,5%}</td></tr>
 * </table>
 *
 * <p>Panel này luôn hiển thị công thức TNCN theo doanh thu (chưa hỗ trợ phương án TNCN theo lợi
 * nhuận của nhóm 2, {@code Financialsetting.taxCalculationMethod}). Thuế suất GTGT riêng của sản
 * phẩm không ảnh hưởng tới số thuế ở mọi nhóm — GTGT tính trực tiếp theo % doanh thu.</p>
 */
@Getter
@AllArgsConstructor
public class PriceSettingTaxProjectionResponse {

    private Integer group;
    private String groupLabel;
    /** Nhóm 1 — không kê khai gì cả. */
    private boolean exempt;
    /** Luôn {@code false} — không nhóm nào còn khấu trừ GTGT đầu vào/đầu ra nữa. */
    private boolean deduction;
    /** Không miễn thuế — tính trực tiếp theo % doanh thu, áp dụng cho cả nhóm 2 và 3. */
    private boolean direct;

    /** Thuế suất GTGT riêng của sản phẩm ({@code 8.00}), lấy từ override hoặc từ loại hàng. */
    private BigDecimal productVatRatePercent;
    /** Nguồn của thuế suất trên, để hiển thị "vì sao lại là số này". */
    private String productVatRateSource;
    /** Thuế suất riêng của sản phẩm có ảnh hưởng tới số thuế bên dưới không — false ở mọi nhóm. */
    private boolean productVatApplies;

    /**
     * Có xác định được giá vốn không (sản phẩm có ít nhất một lô còn tồn). Nếu false thì mọi số
     * liên quan tới giá vốn giữ {@code null} và màn hình hiện {@code —} — không mặc định về 0 vì
     * sẽ hiểu nhầm cả giá bán là lợi nhuận.
     */
    private boolean costKnown;

    /** Giá bán đơn vị cơ bản dùng làm doanh thu (GROSS). */
    private BigDecimal sellPricePerBase;
    /** Giá nhập trung bình các lô còn tồn dùng làm giá vốn (GROSS); {@code null} nếu hết tồn kho. */
    private BigDecimal importPricePerBase;

    private BigDecimal outputVat;
    private BigDecimal inputVat;
    /** {@code outputVat − inputVat}. Âm nghĩa là được chuyển tiếp kỳ sau, không phải được hoàn. */
    private BigDecimal vatPayable;

    private BigDecimal incomeTaxBase;
    private BigDecimal incomeTaxRatePercent;
    private BigDecimal incomeTax;

    /** {@code vatPayable + incomeTax} — tổng thuế phải nộp cho một đơn vị. */
    private BigDecimal totalTax;

    /** {@code sellPrice − importPrice}, trước thuế. Âm nếu giá nhập cao hơn giá bán. */
    private BigDecimal grossMargin;
    /** {@code grossMargin − totalTax}. Lợi nhuận thực tế trên một đơn vị. */
    private BigDecimal netProfit;

    /** Câu mô tả công thức đã áp dụng, tiếng Việt. */
    private String formula;
    /** Lưu ý thêm bằng tiếng Việt, hoặc {@code null} nếu số liệu đã chính xác. */
    private String caveat;
}
