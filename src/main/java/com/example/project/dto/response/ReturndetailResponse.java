package com.example.project.dto.response;

import com.example.project.entity.Returndetail;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReturndetailResponse {
    private Integer id;
    private Integer returnId;
    private Integer invoiceDetailId;
    private Integer purchaseDetailId;
    private Integer productId;
    private Integer productUnitId;
    private Integer batchId;
    private Integer returnQty;
    private Integer baseQtyRestored;
    private BigDecimal unitSellPrice;
    private BigDecimal lineRefund;
    private Boolean restockable;
    private BigDecimal originalLineValue;

    public static ReturndetailResponse from(Returndetail returndetail) {
        return new ReturndetailResponse(
                returndetail.getId(),
                returndetail.getReturnID() != null ? returndetail.getReturnID().getId() : null,
                returndetail.getInvoiceDetailID() != null ? returndetail.getInvoiceDetailID().getId() : null,
                returndetail.getPurchaseDetailID() != null ? returndetail.getPurchaseDetailID().getId() : null,
                returndetail.getProductID() != null ? returndetail.getProductID().getProductID() : null,
                returndetail.getProductUnitID() != null ? returndetail.getProductUnitID().getId() : null,
                returndetail.getBatchID() != null ? returndetail.getBatchID().getId() : null,
                returndetail.getReturnQty(),
                returndetail.getBaseQtyRestored(),
                returndetail.getUnitSellPrice(),
                returndetail.getLineRefund(),
                returndetail.getRestockable(),
                returndetail.getOriginalLineValue()
        );
    }
}