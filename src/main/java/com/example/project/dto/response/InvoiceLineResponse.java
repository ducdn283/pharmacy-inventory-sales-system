package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One invoice line, for the quick-view modal on the sale-invoice list (JSON). */
@Getter
@AllArgsConstructor
public class InvoiceLineResponse {

    private Integer id;
    private String productName;
    private String lotNumber;
    private String expirationDate;
    private String unitName;
    private Integer quantity;
    private BigDecimal unitSellPrice;
    private BigDecimal subtotal;
    private Integer returnedQty;
    private String note;
}
