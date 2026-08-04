package com.example.project.constant;

import java.util.List;

public final class StockReviewStatus {

    public static final String DRAFT = "Nháp";
    public static final String PENDING = "Chờ duyệt";
    public static final String REJECTED = "Từ chối";
    public static final String APPROVED = "Đã duyệt";
    public static final String ADJUSTED = "Đã điều chỉnh";

    public static final List<String> ALL = List.of(
            DRAFT,
            PENDING,
            REJECTED,
            APPROVED,
            ADJUSTED
    );

    private StockReviewStatus() {
    }
}