package com.example.project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryNotificationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    InventoryNotificationScheduler.class
            );

    private final InventoryNotificationService
            inventoryNotificationService;

    public InventoryNotificationScheduler(
            InventoryNotificationService
                    inventoryNotificationService
    ) {
        this.inventoryNotificationService =
                inventoryNotificationService;
    }

    /**
     * Quét tồn kho và hạn sử dụng ngay sau khi
     * ứng dụng khởi động hoàn tất.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        runSafely();
    }

    /**
     * Lịch quét được đọc từ application.properties.
     *
     * Mặc định:
     * 0 phút 0 giây, cứ mỗi 5 phút chạy một lần.
     */
    @Scheduled(
            cron = "${app.notification.inventory-scan-cron}",
            zone = "Asia/Ho_Chi_Minh"
    )
    public void scanPeriodically() {
        runSafely();
    }

    private void runSafely() {
        try {
            inventoryNotificationService
                    .scanInventoryAlerts();

            log.debug(
                    "Đã hoàn tất quét thông báo "
                            + "tồn kho và hạn sử dụng"
            );
        } catch (Exception exception) {
            log.error(
                    "Không thể quét thông báo "
                            + "tồn kho và hạn sử dụng",
                    exception
            );
        }
    }
}