package com.example.project.service;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationReferenceType;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationType;
import com.example.project.constant.RoleConstants;
import com.example.project.entity.Account;
import com.example.project.entity.Batch;
import com.example.project.entity.Product;
import com.example.project.repository.AccountpermissionRepository;
import com.example.project.repository.BatchRepository;
import com.example.project.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryNotificationService {

    private static final ZoneId VN_ZONE =
            ZoneId.of("Asia/Ho_Chi_Minh");

    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;

    private final AccountpermissionRepository
            accountpermissionRepository;

    private final NotificationService notificationService;

    private final int expiringBatchDays;

    public InventoryNotificationService(
            ProductRepository productRepository,
            BatchRepository batchRepository,
            AccountpermissionRepository accountpermissionRepository,
            NotificationService notificationService,
            @Value("${app.notification.expiring-batch-days:30}")
            int expiringBatchDays
    ) {
        this.productRepository = productRepository;
        this.batchRepository = batchRepository;
        this.accountpermissionRepository =
                accountpermissionRepository;
        this.notificationService = notificationService;

        this.expiringBatchDays =
                Math.max(1, expiringBatchDays);
    }

    @Transactional
    public void scanInventoryAlerts() {
        scanStockAlerts();
        scanExpiryAlerts();
    }

    // =========================================================
    // LOW STOCK / OUT OF STOCK
    // =========================================================

    private void scanStockAlerts() {
        Map<Integer, Long> stockByProduct =
                new HashMap<>();

        for (Object[] row :
                batchRepository
                        .sumActiveStorageGroupedByProduct()) {

            if (row == null
                    || row.length < 2
                    || row[0] == null) {
                continue;
            }

            Integer productId =
                    ((Number) row[0]).intValue();

            long quantity =
                    row[1] == null
                            ? 0L
                            : ((Number) row[1]).longValue();

            stockByProduct.put(
                    productId,
                    quantity
            );
        }

        for (Product product :
                productRepository.findAllWithRelations()) {

            if (product.getProductID() == null) {
                continue;
            }

            Integer productId =
                    product.getProductID();

            long quantity =
                    stockByProduct.getOrDefault(
                            productId,
                            0L
                    );

            int minStock =
                    product.getMinStock() == null
                            ? 0
                            : product.getMinStock();

            /*
             * Sản phẩm ngừng hoạt động:
             * đóng mọi cảnh báo tồn kho cũ.
             */
            if (!Boolean.TRUE.equals(
                    product.getStatus()
            )) {
                resolveStockAlerts(productId);
                continue;
            }

            /*
             * Tồn kho bằng 0 hoặc âm:
             * tạo cảnh báo khẩn cấp hết hàng.
             */
            if (quantity <= 0) {
                notificationService
                        .resolveByTypeAndReference(
                                NotificationType.LOW_STOCK,
                                NotificationReferenceType.PRODUCT,
                                productId
                        );

                sendProductAlert(
                        product,
                        NotificationType.OUT_OF_STOCK,
                        "Sản phẩm đã hết hàng",
                        productLabel(product)
                                + " hiện đã hết hàng. "
                                + "Vui lòng kiểm tra và lập "
                                + "kế hoạch nhập hàng.",
                        NotificationSeverity.URGENT
                );

                continue;
            }

            /*
             * Tồn hiện tại <= minStock:
             * tạo cảnh báo sắp hết hàng.
             */
            if (minStock > 0
                    && quantity <= minStock) {

                notificationService
                        .resolveByTypeAndReference(
                                NotificationType.OUT_OF_STOCK,
                                NotificationReferenceType.PRODUCT,
                                productId
                        );

                sendProductAlert(
                        product,
                        NotificationType.LOW_STOCK,
                        "Sản phẩm sắp hết hàng",
                        productLabel(product)
                                + " chỉ còn "
                                + quantity
                                + " đơn vị cơ sở, bằng hoặc dưới "
                                + "mức tồn tối thiểu "
                                + minStock
                                + ".",
                        NotificationSeverity.WARNING
                );

                continue;
            }

            /*
             * Tồn kho đã bình thường trở lại.
             */
            resolveStockAlerts(productId);
        }
    }

    // =========================================================
    // EXPIRING / EXPIRED BATCH
    // =========================================================

    private void scanExpiryAlerts() {
        LocalDate today =
                LocalDate.now(VN_ZONE);

        LocalDate warningDate =
                today.plusDays(expiringBatchDays);

        for (Batch batch :
                batchRepository
                        .findAllWithProductForNotifications()) {

            if (batch.getId() == null) {
                continue;
            }

            boolean eligible =
                    Boolean.TRUE.equals(batch.getStatus())
                            && batch.getStorageQuantity() != null
                            && batch.getStorageQuantity() > 0
                            && batch.getExpirationDate() != null
                            && batch.getProductID() != null
                            && Boolean.TRUE.equals(
                            batch.getProductID().getStatus()
                    );

            /*
             * Lô không hoạt động, đã hết kho,
             * không có hạn dùng hoặc sản phẩm ngừng hoạt động.
             */
            if (!eligible) {
                resolveBatchAlerts(batch.getId());
                continue;
            }

            LocalDate expiryDate =
                    batch.getExpirationDate();

            /*
             * Hết hạn hôm nay hoặc đã quá hạn.
             */
            if (!expiryDate.isAfter(today)) {
                notificationService
                        .resolveByTypeAndReference(
                                NotificationType.EXPIRING_BATCH,
                                NotificationReferenceType.BATCH,
                                batch.getId()
                        );

                long overdueDays =
                        ChronoUnit.DAYS.between(
                                expiryDate,
                                today
                        );

                String timing =
                        overdueDays == 0
                                ? "hết hạn hôm nay"
                                : "đã quá hạn "
                                + overdueDays
                                + " ngày";

                sendBatchAlert(
                        batch,
                        NotificationType.EXPIRED_BATCH,
                        "Lô hàng đã hết hạn",
                        batchLabel(batch)
                                + " "
                                + timing
                                + " và còn "
                                + batch.getStorageQuantity()
                                + " đơn vị cơ sở trong kho.",
                        NotificationSeverity.URGENT
                );

                continue;
            }

            /*
             * Còn trong khoảng cảnh báo, mặc định là 30 ngày.
             */
            if (!expiryDate.isAfter(warningDate)) {
                notificationService
                        .resolveByTypeAndReference(
                                NotificationType.EXPIRED_BATCH,
                                NotificationReferenceType.BATCH,
                                batch.getId()
                        );

                long remainingDays =
                        ChronoUnit.DAYS.between(
                                today,
                                expiryDate
                        );

                sendBatchAlert(
                        batch,
                        NotificationType.EXPIRING_BATCH,
                        "Lô hàng sắp hết hạn",
                        batchLabel(batch)
                                + " sẽ hết hạn sau "
                                + remainingDays
                                + " ngày và còn "
                                + batch.getStorageQuantity()
                                + " đơn vị cơ sở trong kho.",
                        NotificationSeverity.WARNING
                );

                continue;
            }

            /*
             * Lô còn xa hạn sử dụng:
             * đóng cảnh báo cũ nếu có.
             */
            resolveBatchAlerts(batch.getId());
        }
    }

    private void sendProductAlert(
            Product product,
            String notificationType,
            String title,
            String message,
            String severity
    ) {
        sendToRole(
                RoleConstants.OWNER,
                title,
                message,
                notificationType,
                NotificationCategory.HANG_HOA,
                severity,
                NotificationReferenceType.PRODUCT,
                product.getProductID(),
                "/owner/products/"
                        + product.getProductID(),
                notificationType
                        + "_PRODUCT_"
                        + product.getProductID()
                        + "_OWNER"
        );

        sendToRole(
                RoleConstants.PHARMACIST,
                title,
                message,
                notificationType,
                NotificationCategory.HANG_HOA,
                severity,
                NotificationReferenceType.PRODUCT,
                product.getProductID(),
                "/pharmacist/products/"
                        + product.getProductID(),
                notificationType
                        + "_PRODUCT_"
                        + product.getProductID()
                        + "_PHARMACIST"
        );
    }

    private void sendBatchAlert(
            Batch batch,
            String notificationType,
            String title,
            String message,
            String severity
    ) {
        Integer productId =
                batch.getProductID() == null
                        ? null
                        : batch.getProductID()
                        .getProductID();

        if (productId == null) {
            return;
        }

        sendToRole(
                RoleConstants.OWNER,
                title,
                message,
                notificationType,
                NotificationCategory.KHO,
                severity,
                NotificationReferenceType.BATCH,
                batch.getId(),
                "/owner/products/" + productId,
                notificationType
                        + "_BATCH_"
                        + batch.getId()
                        + "_OWNER"
        );

        sendToRole(
                RoleConstants.PHARMACIST,
                title,
                message,
                notificationType,
                NotificationCategory.KHO,
                severity,
                NotificationReferenceType.BATCH,
                batch.getId(),
                "/pharmacist/products/" + productId,
                notificationType
                        + "_BATCH_"
                        + batch.getId()
                        + "_PHARMACIST"
        );
    }

    private void sendToRole(
            String role,
            String title,
            String message,
            String notificationType,
            String category,
            String severity,
            String referenceType,
            Integer referenceId,
            String actionUrl,
            String dedupeKey
    ) {
        List<Account> receivers =
                accountpermissionRepository
                        .findActiveAccountsByRole(role);

        for (Account receiver : receivers) {
            notificationService.createIfMissing(
                    receiver,
                    role,
                    title,
                    message,
                    notificationType,
                    category,
                    severity,
                    referenceType,
                    referenceId,
                    actionUrl,
                    dedupeKey
                            + "_ACCOUNT_"
                            + receiver.getId()
            );
        }
    }

    private void resolveStockAlerts(
            Integer productId
    ) {
        notificationService
                .resolveByTypeAndReference(
                        NotificationType.LOW_STOCK,
                        NotificationReferenceType.PRODUCT,
                        productId
                );

        notificationService
                .resolveByTypeAndReference(
                        NotificationType.OUT_OF_STOCK,
                        NotificationReferenceType.PRODUCT,
                        productId
                );
    }

    private void resolveBatchAlerts(
            Integer batchId
    ) {
        notificationService
                .resolveByTypeAndReference(
                        NotificationType.EXPIRING_BATCH,
                        NotificationReferenceType.BATCH,
                        batchId
                );

        notificationService
                .resolveByTypeAndReference(
                        NotificationType.EXPIRED_BATCH,
                        NotificationReferenceType.BATCH,
                        batchId
                );
    }

    private String productLabel(Product product) {
        String code =
                product.getCode() == null
                        || product.getCode().isBlank()
                        ? ""
                        : " ("
                        + product.getCode().trim()
                        + ")";

        return text(
                product.getName(),
                "Sản phẩm"
        ) + code;
    }

    private String batchLabel(Batch batch) {
        String productName =
                batch.getProductID() == null
                        ? "Sản phẩm"
                        : text(
                        batch.getProductID().getName(),
                        "Sản phẩm"
                );

        String batchCode =
                text(
                        batch.getBatchCode(),
                        "Lô #" + batch.getId()
                );

        return "Lô "
                + batchCode
                + " của "
                + productName;
    }

    private String text(
            String value,
            String fallback
    ) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }
}