package com.example.project.view;

import lombok.Getter;

import java.util.List;

/**
 * Toàn bộ trạng thái màn hình Bảng phân quyền cho một lần render: trang tài khoản hiện tại và
 * từ khóa tìm kiếm. Mọi tính toán phân trang đã làm sẵn ở đây để template chỉ cần đọc giá trị.
 *
 * <p>{@link #pageIndex} 0-based (bằng tham số {@code page}); {@link #getPageNumber()} /
 * {@link #getTotalPages()} là 1-based để hiển thị.</p>
 */
@Getter
public class PermissionPageView {

    private final List<PermissionAccountRow> rows;
    private final int pageIndex;        // trang hiện tại, 0-based (= tham số "page")
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final String search;

    public PermissionPageView(List<PermissionAccountRow> rows,
                              int pageIndex, int size, long totalElements, int totalPages,
                              String search) {
        this.rows = rows;
        this.pageIndex = pageIndex;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.search = search;
    }

    /** Trang hiện tại (1-based), dùng cho nhãn "Trang {current} / {total}". */
    public int getPageNumber() {
        return pageIndex + 1;
    }

    /** True nếu có trang trước đó (không phải trang đầu). */
    public boolean isHasPrevious() {
        return pageIndex > 0;
    }

    /** True nếu còn trang sau (chưa phải trang cuối). */
    public boolean isHasNext() {
        return pageIndex + 1 < totalPages;
    }

    /** Trang đích cho nút "Trước" (không âm). */
    public int getPreviousPage() {
        return Math.max(0, pageIndex - 1);
    }

    /** Trang đích cho nút "Sau" (không vượt quá trang cuối). */
    public int getNextPage() {
        return totalPages == 0 ? 0 : Math.min(totalPages - 1, pageIndex + 1);
    }

    /** Chỉ số dòng đầu tiên trên trang này (1-based, 0 nếu không có dòng nào). */
    public long getFromIndex() {
        return totalElements == 0 ? 0 : (long) pageIndex * size + 1;
    }

    /** Chỉ số dòng cuối cùng trên trang này. */
    public long getToIndex() {
        return Math.min((long) (pageIndex + 1) * size, totalElements);
    }
}
