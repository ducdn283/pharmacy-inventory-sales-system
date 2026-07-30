package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

public final class DashboardView {

    private DashboardView() {
    }

    @Getter
    @AllArgsConstructor
    public static class MetricCard {
        private String title;
        private String value;
        private String helper;
        private String icon;
        private String tone;
        private String url;
    }

    @Getter
    @AllArgsConstructor
    public static class QuickAction {
        private String label;
        private String url;
        private boolean primary;
    }

    @Getter
    @AllArgsConstructor
    public static class TodoItem {
        private String title;
        private String description;
        private String badge;
        private String tone;
        private String url;
    }

    @Getter
    @AllArgsConstructor
    public static class RecentInvoice {
        private String code;
        private String time;
        private String customerName;
        private String totalDisplay;
        private String status;
        private String tone;
        private String url;
    }

    @Getter
    @AllArgsConstructor
    public static class PerformanceRow {
        private String name;
        private String revenueDisplay;
        private long invoiceCount;
        private String debtDisplay;
        private String status;
        private String tone;
    }

    @Getter
    @AllArgsConstructor
    public static class ChartSeries {
        private String name;
        private List<BigDecimal> data;
    }

    @Getter
    @AllArgsConstructor
    public static class DashboardChart {
        private String title;
        private String type;
        private List<String> labels;
        private List<ChartSeries> series;
    }

    @Getter
    @AllArgsConstructor
    public static class RoleDashboard {
        private String roleCode;
        private String pageTitle;
        private String greetingName;
        private String subtitle;

        private List<QuickAction> quickActions;
        private List<MetricCard> metrics;

        private List<DashboardChart> charts;

        private String todoTitle;
        private List<TodoItem> todoItems;

        private String recentInvoiceTitle;
        private List<RecentInvoice> recentInvoices;

        private String performanceTitle;
        private List<PerformanceRow> performanceRows;

        private String approvalTitle;
        private List<ApprovalItemResponse> approvalItems;
    }
}