package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Body POST bù trừ công nợ thủ công ({@code /owner/debts/offset}).
 * {@link #receivableLines} và {@link #payableLines} phải cộng bằng nhau — xem
 * {@link com.example.project.service.DebtOffsetService#applyOffset}.
 */
@Getter
@Setter
public class DebtOffsetRequest {

    /** {@code CUSTOMER} hoặc {@code SUPPLIER}. */
    private String partyType;
    /** Id khách hàng / NCC; {@code 0} cho khách lẻ. */
    private Integer entityId;
    /** Chứng từ cho nợ cần cấn (id hóa đơn hoặc id phiếu trả NCC). */
    private List<DebtOffsetLineRequest> receivableLines = new ArrayList<>();
    /** Chứng từ nợ cần cấn (id phiếu trả KH hoặc id phiếu nhập). */
    private List<DebtOffsetLineRequest> payableLines = new ArrayList<>();
    /** Ghi chú tùy chọn trên phiếu thu/chi bù trừ. */
    private String note;
}
