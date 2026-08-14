package com.example.project.dto.response;

import java.util.List;

/**
 * Các khối phân tích mở rộng dùng chung cho
 * dashboard Owner và Accountant.
 *
 * Sử dụng DTO riêng để không làm thay đổi các
 * constructor cũ của AccountantDashboardResponse.
 */
public class DashboardAnalyticsResponse {

    private final boolean ownerView;

    private final List<AccountantDashboardResponse.MetricCard>
            salesMetrics;

    private final List<AccountantDashboardResponse.MetricCard>
            purchaseMetrics;

    private final List<AccountantDashboardResponse.MetricCard>
            taxMetrics;

    private final DashboardView.DashboardChart
            netProfitChart;

    private final DashboardView.DashboardChart
            productCategoryRevenueChart;

    private final DashboardView.DashboardChart
            monthlyPurchaseChart;

    private final DashboardView.DashboardChart
            supplierPurchaseChart;

    private final List<DetailRow>
            topSellingProducts;

    private final List<DetailRow>
            pendingPurchases;

    private final List<DetailRow>
            incompletePurchases;

    private final List<DetailRow>
            mostImportedProducts;

    private final List<DetailRow>
            bestSupplierPrices;

    private final List<DetailRow>
            purchasePriceHistory;

    private final String productKeyword;

    public DashboardAnalyticsResponse(
            boolean ownerView,

            List<AccountantDashboardResponse.MetricCard>
                    salesMetrics,

            List<AccountantDashboardResponse.MetricCard>
                    purchaseMetrics,

            List<AccountantDashboardResponse.MetricCard>
                    taxMetrics,

            DashboardView.DashboardChart
                    netProfitChart,

            DashboardView.DashboardChart
                    productCategoryRevenueChart,

            DashboardView.DashboardChart
                    monthlyPurchaseChart,

            DashboardView.DashboardChart
                    supplierPurchaseChart,

            List<DetailRow> topSellingProducts,

            List<DetailRow> pendingPurchases,

            List<DetailRow> incompletePurchases,

            List<DetailRow> mostImportedProducts,

            List<DetailRow> bestSupplierPrices,

            List<DetailRow> purchasePriceHistory,

            String productKeyword
    ) {
        this.ownerView = ownerView;

        this.salesMetrics =
                safeList(salesMetrics);

        this.purchaseMetrics =
                safeList(purchaseMetrics);

        this.taxMetrics =
                safeList(taxMetrics);

        this.netProfitChart =
                netProfitChart;

        this.productCategoryRevenueChart =
                productCategoryRevenueChart;

        this.monthlyPurchaseChart =
                monthlyPurchaseChart;

        this.supplierPurchaseChart =
                supplierPurchaseChart;

        this.topSellingProducts =
                safeList(topSellingProducts);

        this.pendingPurchases =
                safeList(pendingPurchases);

        this.incompletePurchases =
                safeList(incompletePurchases);

        this.mostImportedProducts =
                safeList(mostImportedProducts);

        this.bestSupplierPrices =
                safeList(bestSupplierPrices);

        this.purchasePriceHistory =
                safeList(purchasePriceHistory);

        this.productKeyword =
                productKeyword == null
                        ? ""
                        : productKeyword;
    }

    private static <T> List<T> safeList(
            List<T> values
    ) {
        return values == null
                ? List.of()
                : List.copyOf(values);
    }

    public boolean isOwnerView() {
        return ownerView;
    }

    public List<AccountantDashboardResponse.MetricCard>
    getSalesMetrics() {
        return salesMetrics;
    }

    public List<AccountantDashboardResponse.MetricCard>
    getPurchaseMetrics() {
        return purchaseMetrics;
    }

    public List<AccountantDashboardResponse.MetricCard>
    getTaxMetrics() {
        return taxMetrics;
    }

    public DashboardView.DashboardChart
    getNetProfitChart() {
        return netProfitChart;
    }

    public DashboardView.DashboardChart
    getProductCategoryRevenueChart() {
        return productCategoryRevenueChart;
    }

    public DashboardView.DashboardChart
    getMonthlyPurchaseChart() {
        return monthlyPurchaseChart;
    }

    public DashboardView.DashboardChart
    getSupplierPurchaseChart() {
        return supplierPurchaseChart;
    }

    public List<DetailRow>
    getTopSellingProducts() {
        return topSellingProducts;
    }

    public List<DetailRow>
    getPendingPurchases() {
        return pendingPurchases;
    }

    public List<DetailRow>
    getIncompletePurchases() {
        return incompletePurchases;
    }

    public List<DetailRow>
    getMostImportedProducts() {
        return mostImportedProducts;
    }

    public List<DetailRow>
    getBestSupplierPrices() {
        return bestSupplierPrices;
    }

    public List<DetailRow>
    getPurchasePriceHistory() {
        return purchasePriceHistory;
    }

    public String getProductKeyword() {
        return productKeyword;
    }

    public static class DetailRow {

        private final String primaryText;
        private final String secondaryText;
        private final String value;
        private final String helper;
        private final String url;
        private final String tone;

        public DetailRow(
                String primaryText,
                String secondaryText,
                String value,
                String helper,
                String url,
                String tone
        ) {
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.value = value;
            this.helper = helper;
            this.url = url;
            this.tone = tone;
        }

        public String getPrimaryText() {
            return primaryText;
        }

        public String getSecondaryText() {
            return secondaryText;
        }

        public String getValue() {
            return value;
        }

        public String getHelper() {
            return helper;
        }

        public String getUrl() {
            return url;
        }

        public String getTone() {
            return tone;
        }
    }
}