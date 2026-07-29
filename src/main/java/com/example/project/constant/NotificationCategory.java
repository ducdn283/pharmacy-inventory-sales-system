package com.example.project.constant;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NotificationCategory {

    public static final String HANG_HOA = "HANG_HOA";
    public static final String KHO = "KHO";
    public static final String PHE_DUYET = "PHE_DUYET";
    public static final String TAI_CHINH = "TAI_CHINH";
    public static final String CONG_NO = "CONG_NO";
    public static final String HOA_DON = "HOA_DON";
    public static final String PHIEU_NHAP = "PHIEU_NHAP";
    public static final String BAO_CAO_CA = "BAO_CAO_CA";
    public static final String KY_THUE = "KY_THUE";
    public static final String HE_THONG = "HE_THONG";

    private NotificationCategory() {
    }

    public static Map<String, String> vietnameseLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(HANG_HOA, "Hàng hóa");
        labels.put(KHO, "Kho");
        labels.put(PHE_DUYET, "Phê duyệt");
        labels.put(TAI_CHINH, "Tài chính");
        labels.put(CONG_NO, "Công nợ");
        labels.put(HOA_DON, "Hóa đơn");
        labels.put(PHIEU_NHAP, "Phiếu nhập");
        labels.put(BAO_CAO_CA, "Báo cáo ca");
        labels.put(KY_THUE, "Kỳ thuế");
        labels.put(HE_THONG, "Hệ thống");
        return labels;
    }

    public static String vietnameseName(String category) {
        if (category == null || category.isBlank()) {
            return "";
        }

        return vietnameseLabels().getOrDefault(category, category);
    }
}