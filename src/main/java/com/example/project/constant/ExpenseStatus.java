package com.example.project.constant;

import java.util.List;

/**
 * Danh sách hợp lệ của {@code Expense.status}. Cột là {@code varchar(50)} thuần (không có bảng
 * Status riêng), các hằng số này là nơi duy nhất định nghĩa từ vựng, tránh lệch giữa service và
 * template.
 *
 * <p><strong>Quy trình 2 giai đoạn: duyệt rồi mới thanh toán thật.</strong></p>
 * <ul>
 *   <li>{@link #DRAFT} — phiếu nháp, người tạo chưa gửi đi.</li>
 *   <li>{@link #PENDING} — người tạo không phải Chủ nhà thuốc đã gửi, chờ Chủ nhà thuốc duyệt.
 *       Riêng phiếu của Dược sĩ dưới ngưỡng {@code ExpenseType#PHARMACIST_AUTO_APPROVE_LIMIT} bỏ
 *       qua trạng thái này, đi thẳng sang {@link #AWAITING_PAYMENT}.</li>
 *   <li>{@link #REJECTED} — Chủ nhà thuốc từ chối phiếu đang chờ duyệt.</li>
 *   <li>{@link #AWAITING_PAYMENT} — đã được duyệt (tự duyệt hoặc Chủ nhà thuốc duyệt) nhưng tiền
 *       CHƯA thực sự rời quỹ. Không sửa được, nhưng vẫn hủy được vì chưa có gì phải đảo ngược.</li>
 *   <li>{@link #COMPLETED} — Chủ nhà thuốc đã xác nhận tiền thực sự được chi ra. Đây là lúc phiếu
 *       nhập/phiếu trả hàng liên kết được tất toán, quỹ bị trừ, và ca làm việc được đóng dấu (xem
 *       {@code ExpenseService.confirmPayment()}). Trạng thái cuối — không hủy được nữa.</li>
 *   <li>{@link #CANCELLED} — sửa lỗi nội bộ cho phiếu lập sai, không phải một bút toán đảo ngược
 *       thật. Chỉ hủy được từ {@link #DRAFT}/{@link #PENDING}/{@link #AWAITING_PAYMENT}, không
 *       bao giờ từ {@link #COMPLETED}.</li>
 * </ul>
 */
public final class ExpenseStatus {

    public static final String DRAFT = "Nháp";
    public static final String PENDING = "Chờ duyệt";
    public static final String REJECTED = "Từ chối";
    public static final String AWAITING_PAYMENT = "Chờ thanh toán";
    public static final String COMPLETED = "Đã hoàn thành";
    public static final String CANCELLED = "Đã hủy";

    /** Toàn bộ trạng thái hợp lệ, theo thứ tự quy trình (dùng cho dropdown lọc). */
    public static final List<String> ALL =
            List.of(DRAFT, PENDING, REJECTED, AWAITING_PAYMENT, COMPLETED, CANCELLED);

    private ExpenseStatus() {
    }
}
