package com.example.project.constant;

import java.util.List;

/**
 * Central definition of the valid {@code Purchaseinvoice.status} values, per
 * {@code docs/context/Pharmacy-Database-Description.docx}. The column is a plain
 * {@code varchar(50)} (no {@code Status} table, see CLAUDE.md) — these constants exist so the
 * vocabulary is written in exactly one place and never drifts between the service and templates.
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

    /** All valid statuses, in workflow order. */
    public static final List<String> ALL =
            List.of(DRAFT, PENDING_APPROVAL, DEBT, PARTIAL_DEBT, COMPLETED, CANCELLED);

    /**
     * Whether a stored {@code Purchaseinvoice.status} is one this app actually knows about. The
     * column is a free-form {@code varchar(50)}, so a value edited straight into the DB (or written
     * by an older/other tool) can be anything at all — screens use this to render such a row as
     * visibly unrecognised instead of dressing it up as a valid status.
     */
    public static boolean isKnown(String status) {
        return status != null && ALL.contains(status.trim());
    }

    private PurchaseInvoiceStatus() {
    }
}
