package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Một dòng chứng từ trong {@link DebtOffsetRequest} — {@code documentId} + số tiền bù trừ. */
@Getter
@Setter
public class DebtOffsetLineRequest {

    /** Khóa chính chứng từ gốc (hóa đơn, phiếu trả hoặc phiếu nhập). */
    private Integer documentId;
    /** Số tiền bù trừ trên chứng từ này. */
    private BigDecimal amount;
}
