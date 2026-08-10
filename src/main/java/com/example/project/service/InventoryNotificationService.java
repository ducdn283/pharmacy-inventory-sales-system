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
import org.springframework.transaction.annotation.Propagation;
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
    private static final int NEAR_EXPIRY_DAYS = 90;

    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;

    private final AccountpermissionRepository
            accountpermissionRepository;

    private final NotificationService
            notificationService;

    private final int expiringBatchDays;

    public InventoryNotificationService(
            ProductRepository productRepository,
            BatchRepository batchRepository,
            AccountpermissionRepository
                    accountpermissionRepository,
            NotificationService notificationService,
            @Value(
                    "${app.notification.expiring-batch-days:30}"
            )
            int expiringBatchDays
    ) {
        this.productRepository =
                productRepository;

        this.batchRepository =
                batchRepository;

        this.accountpermissionRepository =
                accountpermissionRepository;

        this.notificationService =
                notificationService;

        this.expiringBatchDays =
                Math.max(
                        1,
                        expiringBatchDays
                );
    }

    /**
     * Quét toàn bộ sản phẩm và lô hàng.
     *
     * Method này vẫn được scheduler sử dụng làm cơ chế
     * kiểm tra và đối soát dự phòng.
     */
    @Transactional
    public void scanInventoryAlerts() {
        scanStockAlerts();
        scanExpiryAlerts();
    }

    /**
     * Kiểm tra ngay tồn kho của một sản phẩm sau khi
     * giao dịch làm thay đổi tồn đã commit.
     *
     * REQUIRES_NEW cần thiết vì method được gọi từ
     * TransactionSynchronization.afterCommit().
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void checkProductStockAlert(
            Integer productId
    ) {
        if (productId == null) {
            return;
        }

        productRepository
                .findDetailById(productId)
                .ifPresent(product -> {
                    long quantity =
                            batchRepository
                                    .findAllByProduct(
                                            productId
                                    )
                                    .stream()
                                    .filter(batch ->
                                            Boolean.TRUE.equals(
                                                    batch.getStatus()
                                            )
                                    )
                                    .mapToLong(batch ->
                                            batch.getStorageQuantity()
                                                    == null
                                                    ? 0L
                                                    : batch.getStorageQuantity()
                                    )
                                    .sum();

                    evaluateProductStock(
                            product,
                            quantity
                    );
                });
    }

    /**
     * Kiểm tra ngay hạn dùng của một lô hàng sau khi
     * lô được tạo hoặc cập nhật.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void checkBatchExpiryAlert(
            Integer batchId
    ) {
        if (batchId == null) {
            return;
        }

        batchRepository
                .findById(batchId)
                .ifPresent(batch ->
                        evaluateBatchExpiry(
                                batch,
                                LocalDate.now(
                                        VN_ZONE
                                )
                        )
                );
    }

    // =========================================================
    // LOW STOCK / OUT OF STOCK
    // =========================================================

    /**
     * Quét tồn kho của toàn bộ sản phẩm.
     */
    private void scanStockAlerts() {
        Map<Integer, Long> stockByProduct =
                new HashMap<>();

        for (Object[] row
                : batchRepository
                .sumActiveStorageGroupedByProduct()) {

            if (row == null
                    || row.length < 2
                    || row[0] == null) {
                continue;
            }

            Integer productId =
                    ((Number) row[0])
                            .intValue();

            long quantity =
                    row[1] == null
                            ? 0L
                            : ((Number) row[1])
                            .longValue();

            stockByProduct.put(
                    productId,
                    quantity
            );
        }

        for (Product product
                : productRepository
                .findAllWithRelations()) {

            if (product.getProductID()
                    == null) {
                continue;
            }

            Integer productId =
                    product.getProductID();

            long quantity =
                    stockByProduct
                            .getOrDefault(
                                    productId,
                                    0L
                            );

            evaluateProductStock(
                    product,
                    quantity
            );
        }
    }

    /**
     * Đánh giá trạng thái tồn kho của một sản phẩm.
     *
     * quantity <= 0:
     * - Đóng LOW_STOCK.
     * - Tạo OUT_OF_STOCK.
     *
     * 0 < quantity <= minStock:
     * - Đóng OUT_OF_STOCK.
     * - Tạo LOW_STOCK.
     *
     * quantity > minStock:
     * - Đóng toàn bộ cảnh báo tồn kho cũ.
     */
    private void evaluateProductStock(
            Product product,
            long quantity
    ) {
        if (product == null
                || product.getProductID()
                == null) {
            return;
        }

        Integer productId =
                product.getProductID();

        int minStock =
                product.getMinStock() == null
                        ? 0
                        : product.getMinStock();

        /*
         * Nếu sản phẩm đã ngừng hoạt động thì không
         * tiếp tục phát cảnh báo tồn kho.
         */
        if (!Boolean.TRUE.equals(
                product.getStatus()
        )) {
            resolveStockAlerts(
                    productId
            );

            return;
        }

        /*
         * Hết hàng.
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

            return;
        }

        /*
         * Sắp hết hàng.
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

            return;
        }

        /*
         * Tồn kho đã trở lại bình thường.
         */
        resolveStockAlerts(
                productId
        );
    }

    // =========================================================
    // EXPIRING / EXPIRED BATCH
    // =========================================================

    /**
     * Quét hạn dùng của toàn bộ lô hàng.
     */
    private void scanExpiryAlerts() {
        LocalDate today =
                LocalDate.now(
                        VN_ZONE
                );

        for (Batch batch
                : batchRepository
                .findAllWithProductForNotifications()) {

            evaluateBatchExpiry(
                    batch,
                    today
            );
        }
    }

    /**
     * Đánh giá hạn dùng của một lô hàng.
     */
    /**
     * Đánh giá hạn dùng của một lô hàng.
     *
     * Trên 90 ngày:
     * - Không có cảnh báo.
     *
     * Từ 31 đến 90 ngày:
     * - NEAR_EXPIRY_BATCH.
     *
     * Từ 1 đến 30 ngày:
     * - EXPIRING_BATCH.
     *
     * Hết hạn hôm nay hoặc đã quá hạn:
     * - EXPIRED_BATCH.
     */
    private void evaluateBatchExpiry(
            Batch batch,
            LocalDate today
    ) {
        if (batch == null
                || batch.getId() == null) {
            return;
        }

        boolean eligible =
                Boolean.TRUE.equals(
                        batch.getStatus()
                )
                        && batch.getStorageQuantity()
                        != null
                        && batch.getStorageQuantity()
                        > 0
                        && batch.getExpirationDate()
                        != null
                        && batch.getProductID()
                        != null
                        && Boolean.TRUE.equals(
                        batch.getProductID()
                                .getStatus()
                );

        /*
         * Không cảnh báo đối với:
         *
         * - Lô ngừng hoạt động.
         * - Lô không còn hàng.
         * - Lô không có hạn dùng.
         * - Sản phẩm đã ngừng hoạt động.
         */
        if (!eligible) {
            resolveBatchAlerts(
                    batch.getId()
            );

            return;
        }

        LocalDate expiryDate =
                batch.getExpirationDate();

        /*
         * Mức 1: đã hết hạn.
         *
         * Bao gồm:
         * - expirationDate bằng hôm nay.
         * - expirationDate trước hôm nay.
         */
        if (!expiryDate.isAfter(today)) {
            notificationService
                    .resolveByTypeAndReference(
                            NotificationType
                                    .NEAR_EXPIRY_BATCH,
                            NotificationReferenceType.BATCH,
                            batch.getId()
                    );

            notificationService
                    .resolveByTypeAndReference(
                            NotificationType
                                    .EXPIRING_BATCH,
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

            return;
        }

        long remainingDays =
                ChronoUnit.DAYS.between(
                        today,
                        expiryDate
                );

        /*
         * Mức 2: còn từ 1 đến 30 ngày.
         */
        if (remainingDays
                <= expiringBatchDays) {

            notificationService
                    .resolveByTypeAndReference(
                            NotificationType
                                    .NEAR_EXPIRY_BATCH,
                            NotificationReferenceType.BATCH,
                            batch.getId()
                    );

            notificationService
                    .resolveByTypeAndReference(
                            NotificationType
                                    .EXPIRED_BATCH,
                            NotificationReferenceType.BATCH,
                            batch.getId()
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

            return;
        }

        /*
         * Mức 3: còn từ 31 đến 90 ngày.
         */
        if (remainingDays
                <= NEAR_EXPIRY_DAYS) {

            notificationService
                    .resolveByTypeAndReference(
                            NotificationType
                                    .EXPIRING_BATCH,
                            NotificationReferenceType.BATCH,
                            batch.getId()
                    );

            notificationService
                    .resolveByTypeAndReference(
                            NotificationType
                                    .EXPIRED_BATCH,
                            NotificationReferenceType.BATCH,
                            batch.getId()
                    );

            sendBatchAlert(
                    batch,
                    NotificationType.NEAR_EXPIRY_BATCH,
                    "Lô hàng cận hạn",
                    batchLabel(batch)
                            + " sẽ hết hạn sau "
                            + remainingDays
                            + " ngày và còn "
                            + batch.getStorageQuantity()
                            + " đơn vị cơ sở trong kho.",
                    NotificationSeverity.WARNING
            );

            return;
        }

        /*
         * Còn trên 90 ngày: đóng mọi cảnh báo hạn dùng cũ.
         */
        resolveBatchAlerts(
                batch.getId()
        );
    }

    /**
     * Gửi cảnh báo sản phẩm cho Owner và Pharmacist.
     */
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

    /**
     * Gửi cảnh báo lô hàng cho Owner và Pharmacist.
     */
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
                "/owner/products/"
                        + productId,
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
                "/pharmacist/products/"
                        + productId,
                notificationType
                        + "_BATCH_"
                        + batch.getId()
                        + "_PHARMACIST"
        );
    }

    /**
     * Gửi cảnh báo đến tất cả tài khoản đang hoạt động
     * thuộc vai trò tương ứng.
     */
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
                        .findActiveAccountsByRole(
                                role
                        );

        for (Account receiver : receivers) {
            notificationService
                    .createIfMissing(
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

    /**
     * Đóng cả LOW_STOCK và OUT_OF_STOCK của sản phẩm.
     */
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

    /**
     * Đóng cả EXPIRING_BATCH và EXPIRED_BATCH của lô.
     */
    /**
     * Đóng toàn bộ cảnh báo hạn dùng của một lô.
     */
    private void resolveBatchAlerts(
            Integer batchId
    ) {
        notificationService
                .resolveByTypeAndReference(
                        NotificationType
                                .NEAR_EXPIRY_BATCH,
                        NotificationReferenceType.BATCH,
                        batchId
                );

        notificationService
                .resolveByTypeAndReference(
                        NotificationType
                                .EXPIRING_BATCH,
                        NotificationReferenceType.BATCH,
                        batchId
                );

        notificationService
                .resolveByTypeAndReference(
                        NotificationType
                                .EXPIRED_BATCH,
                        NotificationReferenceType.BATCH,
                        batchId
                );
    }

    private String productLabel(
            Product product
    ) {
        String code =
                product.getCode() == null
                        || product.getCode()
                        .isBlank()
                        ? ""
                        : " ("
                        + product.getCode()
                        .trim()
                        + ")";

        return text(
                product.getName(),
                "Sản phẩm"
        ) + code;
    }

    private String batchLabel(
            Batch batch
    ) {
        String productName =
                batch.getProductID()
                        == null
                        ? "Sản phẩm"
                        : text(
                        batch.getProductID()
                                .getName(),
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
        return value == null
                || value.isBlank()
                ? fallback
                : value.trim();
    }
}