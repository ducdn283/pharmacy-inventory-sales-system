package com.example.project.dto.response;

import com.example.project.entity.Procurementplan;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

/** Dữ liệu phiếu dự trù trả về màn danh sách / form xem-sửa Owner/Accountant. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementplanResponse {
    /** Id phiếu dự trù. */
    private Integer id;
    /** Mã hiển thị — dạng {@code DT-xxxxxx}. */
    private String procurementCode;
    /** Ngày lập / cập nhật phiếu. */
    private LocalDateTime date;
    /** Trạng thái: {@code Đang thực hiện} hoặc {@code Đã hoàn thành}. */
    private String status;
    /** Ghi chú trên phiếu. */
    private String note;
    /** Thời điểm tạo phiếu. */
    private LocalDateTime createdAt;

    /** Ánh xạ entity {@link Procurementplan} sang DTO hiển thị. */
    public static ProcurementplanResponse from(Procurementplan procurementplan) {
        return new ProcurementplanResponse(
                procurementplan.getId(),
                procurementplan.getProcurementCode(),
                procurementplan.getDate(),
                procurementplan.getStatus(),
                procurementplan.getNote(),
                procurementplan.getCreatedAt()
        );
    }
}
