package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Thông tin hóa đơn nhập được hiển thị trong modal
 * khi người dùng chọn thêm sản phẩm theo hóa đơn.
 */
@Getter
@AllArgsConstructor
public class PurchaseInvoiceBarcodeOptionResponse {

    private final Integer purchaseId;

    private final String purchaseInvoiceCode;

    private final String dateDisplay;

    private final String supplierName;

    private final String status;
}