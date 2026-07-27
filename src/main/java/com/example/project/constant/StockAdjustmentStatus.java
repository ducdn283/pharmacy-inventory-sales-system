package com.example.project.constant;

import java.util.List;

/**
 * Central definition of the valid {@code Stockadjustment.status} values.
 *
 * <p>The column is a plain {@code varchar(50)} (there is no {@code Status} table for stock
 * adjustments — confirmed by {@code Pharmacy Database Description.docx}). These constants exist so
 * the vocabulary is written in exactly one place and never drifts between the service, controller
 * and templates.</p>
 *
 * <p><strong>Không còn bước duyệt (BA chốt 2026-07-27).</strong> Bảng phân quyền màn hình
 * ({@code Nghiệp vụ.xlsx}) ghi rõ <em>"Chỉ Owner tạo"</em> phiếu điều chỉnh kho, mà Owner chính là
 * người chịu trách nhiệm cuối — tự duyệt phiếu của chính mình không còn ý nghĩa. Vì vậy docx bản
 * 19:14 ngày 27/07 đã rút trạng thái từ 5 xuống 3: {@code Nháp / Hoàn thành / đã hủy}. Ba trạng thái
 * cũ {@code Chờ duyệt / Từ chối / Duyệt} đã bị xóa hẳn.</p>
 *
 * <p>Workflow:
 * <ul>
 *   <li>{@link #DRAFT} — bản đang soạn, chưa tác động gì tới tồn kho.</li>
 *   <li>{@link #COMPLETED} — phiếu đã thực hiện. Đây là bước DUY NHẤT tồn kho thật sự đổi:
 *       {@code batch.storageQuantity} cộng (IN) hoặc trừ (OUT) theo từng dòng. (Màn kiểm kê là bước
 *       chỉ đọc, không đụng tồn.)</li>
 *   <li>{@link #CANCELLED} — hủy phiếu lập sai. Nếu phiếu đã {@code Hoàn thành} thì việc hủy sẽ
 *       <strong>đảo ngược</strong> đúng phần tồn kho đã cộng/trừ (cùng cơ chế với hủy phiếu nhập —
 *       xem {@code PurchaseinvoiceService.cancelPurchaseInvoice}).</li>
 * </ul>
 */
public final class StockAdjustmentStatus {

    public static final String DRAFT = "Nháp";
    public static final String COMPLETED = "Hoàn thành";
    public static final String CANCELLED = "Đã hủy";

    /** All valid statuses, in workflow order (for the filter dropdown). */
    public static final List<String> ALL = List.of(DRAFT, COMPLETED, CANCELLED);

    private StockAdjustmentStatus() {
    }
}
