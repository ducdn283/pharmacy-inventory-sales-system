package com.example.project.constant;

import java.util.List;

/**
 * Định nghĩa các giá trị hợp lệ của Purchaseinvoice.status (cột varchar tự do, không có bảng
 * Status riêng) — dùng chung để tránh lệch chuỗi giữa service và template.
 */
public final class PurchaseInvoiceStatus {

    public static final String DRAFT = "Nháp";
    public static final String PENDING_APPROVAL = "Chờ duyệt";
    public static final String DEBT = "Nợ";
    public static final String PARTIAL_DEBT = "Nợ một phần";
    public static final String COMPLETED = "Hoàn thành";

    /**
     * Chứng từ nội bộ, không phải hóa đơn đã xuất — không chịu ràng buộc luật cấm hủy hóa đơn.
     * Hủy chỉ dùng để sửa lỗi lập sai; lý do hủy được ghi vào {@code Purchaseinvoice.note}.
     */
    public static final String CANCELLED = "Đã hủy";

    /** Tất cả trạng thái hợp lệ, theo đúng thứ tự luồng nghiệp vụ. */
    public static final List<String> ALL =
            List.of(DRAFT, PENDING_APPROVAL, DEBT, PARTIAL_DEBT, COMPLETED, CANCELLED);

    /**
     * Kiểm tra status lưu trong DB có nằm trong danh sách hợp lệ không — vì cột là varchar tự do,
     * giá trị sửa tay trong DB có thể bất kỳ; dùng để hiển thị badge "không xác định" thay vì
     * coi như trạng thái hợp lệ.
     */
    public static boolean isKnown(String status) {
        return status != null && ALL.contains(status.trim());
    }

    private PurchaseInvoiceStatus() {
    }
}
