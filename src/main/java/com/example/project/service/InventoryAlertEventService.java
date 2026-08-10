package com.example.project.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class InventoryAlertEventService {

    private final InventoryNotificationService
            inventoryNotificationService;

    public InventoryAlertEventService(
            InventoryNotificationService
                    inventoryNotificationService
    ) {
        this.inventoryNotificationService =
                inventoryNotificationService;
    }

    /**
     * Đăng ký kiểm tra sản phẩm và lô hàng sau khi transaction
     * nghiệp vụ hiện tại commit thành công.
     *
     * Ví dụ:
     *
     * - Bán hàng làm giảm tồn.
     * - Nhập hàng làm tăng tồn.
     * - Khách trả hàng tạo lô mới.
     * - Trả hàng nhà cung cấp làm giảm tồn.
     */
    public void checkAfterCommit(
            Iterable<Integer> productIds,
            Iterable<Integer> batchIds
    ) {
        Set<Integer> products =
                copyIds(productIds);

        Set<Integer> batches =
                copyIds(batchIds);

        if (products.isEmpty()
                && batches.isEmpty()) {
            return;
        }

        Runnable check = () -> {
            products.forEach(
                    inventoryNotificationService
                            ::checkProductStockAlert
            );

            batches.forEach(
                    inventoryNotificationService
                            ::checkBatchExpiryAlert
            );
        };

        if (TransactionSynchronizationManager
                .isActualTransactionActive()
                && TransactionSynchronizationManager
                .isSynchronizationActive()) {

            TransactionSynchronizationManager
                    .registerSynchronization(
                            new TransactionSynchronization() {

                                @Override
                                public void afterCommit() {
                                    check.run();
                                }
                            }
                    );

            return;
        }

        /*
         * Nếu đang chạy ngoài transaction thì kiểm tra ngay.
         */
        check.run();
    }

    /**
     * Chỉ kiểm tra lại tồn của một sản phẩm.
     */
    public void checkProductAfterCommit(
            Integer productId
    ) {
        checkAfterCommit(
                productId == null
                        ? Set.of()
                        : Set.of(productId),
                Set.of()
        );
    }

    /**
     * Kiểm tra lại tồn sản phẩm và hạn dùng của lô hàng.
     */
    public void checkBatchAfterCommit(
            Integer productId,
            Integer batchId
    ) {
        checkAfterCommit(
                productId == null
                        ? Set.of()
                        : Set.of(productId),

                batchId == null
                        ? Set.of()
                        : Set.of(batchId)
        );
    }

    /**
     * Loại bỏ ID null và ID trùng lặp.
     */
    private Set<Integer> copyIds(
            Iterable<Integer> values
    ) {
        Set<Integer> result =
                new LinkedHashSet<>();

        if (values == null) {
            return result;
        }

        for (Integer value : values) {
            if (value != null) {
                result.add(value);
            }
        }

        return result;
    }
}