package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class InvoicePrintLineResponse {

    private String productCode;
    private String productName;
    private String unitName;
    private Integer quantity;
    private BigDecimal unitSellPrice;
    private BigDecimal lineSubtotal;
    private BigDecimal vatRate;
    private BigDecimal preTaxAmount;
    private BigDecimal vatAmount;
}
