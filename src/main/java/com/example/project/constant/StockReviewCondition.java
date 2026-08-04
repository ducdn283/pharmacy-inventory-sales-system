package com.example.project.constant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class StockReviewCondition {

    public static final String GOOD = "GOOD";
    public static final String DAMAGED = "DAMAGED";
    public static final String SPOILED = "SPOILED";
    public static final String OTHER = "OTHER";

    public static final List<String> ALL = List.of(
            GOOD,
            DAMAGED,
            SPOILED,
            OTHER
    );

    private static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(GOOD, "Tốt");
        labels.put(DAMAGED, "Hư hỏng");
        labels.put(SPOILED, "Biến chất");
        labels.put(OTHER, "Khác");
        LABELS = Collections.unmodifiableMap(labels);
    }

    public static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String value) {
        return ALL.contains(normalize(value));
    }

    public static String label(String value) {
        String normalized = normalize(value);
        return LABELS.getOrDefault(
                normalized,
                normalized.isBlank() ? "Chưa ghi nhận" : value
        );
    }

    public static Map<String, String> labels() {
        return LABELS;
    }

    private StockReviewCondition() {
    }
}