package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Dữ liệu hóa đơn nhập dùng trong chức năng
 * "Thêm theo hóa đơn nhập" của modal in barcode.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseInvoiceBarcodeOptionResponse {

    /**
     * Khóa chính của hóa đơn nhập.
     */
    private Integer purchaseId;

    /**
     * Mã hóa đơn nhập.
     */
    private String purchaseInvoiceCode;

    /**
     * Ngày tạo hóa đơn đã được định dạng để hiển thị.
     */
    private String dateDisplay;

    /**
     * Tên nhà cung cấp.
     */
    private String supplierName;

    /**
     * Trạng thái hiện tại của hóa đơn nhập.
     */
    private String status;
}