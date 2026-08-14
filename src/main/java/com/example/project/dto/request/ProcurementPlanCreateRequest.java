package com.example.project.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Form tạo / cập nhật phiếu dự trù mua hàng — Owner gửi từ màn quản lý dự trù. */
@Getter
@Setter
public class ProcurementPlanCreateRequest {

    /** Ghi chú trên phiếu dự trù. */
    private String note;

    /** Trạng thái: {@code Đang thực hiện} hoặc {@code Đã hoàn thành}. */
    private String status;

    /** Danh sách dòng sản phẩm cần dự trù — ít nhất một dòng. */
    @Valid
    @NotEmpty(message = "Dự trù mua hàng phải có ít nhất một sản phẩm")
    private List<ProcurementPlanDetailCreateRequest> details = new ArrayList<>();
}
