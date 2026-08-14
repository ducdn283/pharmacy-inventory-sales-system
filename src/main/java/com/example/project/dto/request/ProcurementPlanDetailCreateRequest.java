package com.example.project.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Một dòng sản phẩm trên form tạo / cập nhật phiếu dự trù. */
@Getter
@Setter
public class ProcurementPlanDetailCreateRequest {

    /** Id hàng hóa cần dự trù. */
    @NotNull(message = "Vui lòng chọn sản phẩm")
    private Integer productId;

    /** Số lượng dự trù theo đơn vị nhập. */
    @NotNull(message = "Số lượng dự trù không được để trống")
    @Min(value = 1, message = "Số lượng dự trù phải lớn hơn 0")
    private Integer requestedQuantity;

    /** Đơn vị nhập (VD: Hộp, Vỉ). */
    @Size(max = 20, message = "Đơn vị không được vượt quá 20 ký tự")
    private String unit;

    /** Tổng giá dự kiến cho dòng — có thể tự tính từ giá nhập × số lượng. */
    @DecimalMin(value = "0.0", message = "Giá dự kiến không được âm")
    private BigDecimal estimatedPrice;

    /** Nhà cung cấp dự kiến — bắt buộc khi hoàn thành phiếu. */
    private Integer supplierId;
}
