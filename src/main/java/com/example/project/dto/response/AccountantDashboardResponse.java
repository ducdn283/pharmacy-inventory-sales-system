package com.example.project.dto.response;

import java.util.List;

public class AccountantDashboardResponse {

    private final String greetingName;
    private final String subtitle;
    private final List<QuickAction> quickActions;
    private final List<MetricCard> metrics;
    private final DashboardView.DashboardChart weeklyChart;
    private final List<AlertItem> alerts;
    private final List<ActivityRow> recentActivities;

    public AccountantDashboardResponse(
            String greetingName,
            String subtitle,
            List<QuickAction> quickActions,
            List<MetricCard> metrics,
            DashboardView.DashboardChart weeklyChart,
            List<AlertItem> alerts,
            List<ActivityRow> recentActivities
    ) {
        this.greetingName = greetingName;
        this.subtitle = subtitle;
        this.quickActions = quickActions;
        this.metrics = metrics;
        this.weeklyChart = weeklyChart;
        this.alerts = alerts;
        this.recentActivities = recentActivities;
    }

    public String getGreetingName() {
        return greetingName;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public List<QuickAction> getQuickActions() {
        return quickActions;
    }

    public List<MetricCard> getMetrics() {
        return metrics;
    }

    public DashboardView.DashboardChart getWeeklyChart() {
        return weeklyChart;
    }

    public List<AlertItem> getAlerts() {
        return alerts;
    }

    public List<ActivityRow> getRecentActivities() {
        return recentActivities;
    }

    public static class QuickAction {

        private final String label;
        private final String url;
        private final boolean primary;

        public QuickAction(
                String label,
                String url,
                boolean primary
        ) {
            this.label = label;
            this.url = url;
            this.primary = primary;
        }

        public String getLabel() {
            return label;
        }

        public String getUrl() {
            return url;
        }

        public boolean isPrimary() {
            return primary;
        }
    }

    public static class MetricCard {

        private final String title;
        private final String value;
        private final String helper;
        private final String icon;
        private final String tone;
        private final String url;

        public MetricCard(
                String title,
                String value,
                String helper,
                String icon,
                String tone,
                String url
        ) {
            this.title = title;
            this.value = value;
            this.helper = helper;
            this.icon = icon;
            this.tone = tone;
            this.url = url;
        }

        public String getTitle() {
            return title;
        }

        public String getValue() {
            return value;
        }

        public String getHelper() {
            return helper;
        }

        public String getIcon() {
            return icon;
        }

        public String getTone() {
            return tone;
        }

        public String getUrl() {
            return url;
        }
    }

    public static class AlertItem {

        private final String message;
        private final String tone;
        private final String url;

        public AlertItem(
                String message,
                String tone,
                String url
        ) {
            this.message = message;
            this.tone = tone;
            this.url = url;
        }

        public String getMessage() {
            return message;
        }

        public String getTone() {
            return tone;
        }

        public String getUrl() {
            return url;
        }
    }

    public static class ActivityRow {

        private final String code;
        private final String type;
        private final String time;
        private final String relatedParty;
        private final String amount;
        private final String paymentMethod;
        private final String status;
        private final String tone;
        private final String url;
        private final long sortEpoch;

        public ActivityRow(
                String code,
                String type,
                String time,
                String relatedParty,
                String amount,
                String paymentMethod,
                String status,
                String tone,
                String url,
                long sortEpoch
        ) {
            this.code = code;
            this.type = type;
            this.time = time;
            this.relatedParty = relatedParty;
            this.amount = amount;
            this.paymentMethod = paymentMethod;
            this.status = status;
            this.tone = tone;
            this.url = url;
            this.sortEpoch = sortEpoch;
        }

        public String getCode() {
            return code;
        }

        public String getType() {
            return type;
        }

        public String getTime() {
            return time;
        }

        public String getRelatedParty() {
            return relatedParty;
        }

        public String getAmount() {
            return amount;
        }

        public String getPaymentMethod() {
            return paymentMethod;
        }

        public String getStatus() {
            return status;
        }

        public String getTone() {
            return tone;
        }

        public String getUrl() {
            return url;
        }

        public long getSortEpoch() {
            return sortEpoch;
        }
    }
}