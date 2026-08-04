package com.example.project.constant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class StockReviewType {

    public static final String COUNT = "COUNT";
    public static final String DATE = "DATE";
    public static final String CONDITION = "CONDITION";

    public static final List<String> ALL = List.of(
            COUNT,
            DATE,
            CONDITION
    );

    private static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(COUNT, "Kiểm đếm số lượng");
        labels.put(DATE, "Rà soát hạn dùng");
        labels.put(CONDITION, "Rà soát tình trạng");
        LABELS = Collections.unmodifiableMap(labels);
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return COUNT;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String value) {
        return ALL.contains(normalize(value));
    }

    public static String label(String value) {
        return LABELS.getOrDefault(
                normalize(value),
                value == null ? "Không xác định" : value
        );
    }

    public static Map<String, String> labels() {
        return LABELS;
    }

    private StockReviewType() {
    }
}