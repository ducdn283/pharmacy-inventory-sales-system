package com.example.project.service;

import com.example.project.constant.NotificationCategory;
import com.example.project.constant.NotificationSeverity;
import com.example.project.constant.NotificationStatus;
import com.example.project.dto.response.NotificationResponse;
import com.example.project.dto.response.NotificationStatsResponse;
import com.example.project.entity.Account;
import com.example.project.entity.Notification;
import com.example.project.repository.NotificationRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /*
     * Dùng setter injection để không thay đổi
     * constructor hiện tại.
     *
     * Các Unit Test đang sử dụng:
     *
     * new NotificationService(notificationRepository)
     *
     * sẽ không bị lỗi constructor.
     */
    private NotificationRealtimeService
            notificationRealtimeService;

    public NotificationService(
            NotificationRepository notificationRepository
    ) {
        this.notificationRepository =
                notificationRepository;
    }

    @Autowired
    public void setNotificationRealtimeService(
            NotificationRealtimeService
                    notificationRealtimeService
    ) {
        this.notificationRealtimeService =
                notificationRealtimeService;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> search(
            Integer accountId,
            String keyword,
            String category,
            String severity,
            String status,
            boolean unreadOnly,
            Pageable pageable
    ) {
        validateAccountId(accountId);

        return notificationRepository
                .searchForAccount(
                        accountId,
                        blankToNull(keyword),
                        blankToNull(category),
                        blankToNull(severity),
                        blankToNull(status),
                        unreadOnly,
                        pageable
                )
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> latest(
            Integer accountId
    ) {
        validateAccountId(accountId);

        return notificationRepository
                .findTop5ActiveForAccount(
                        accountId
                )
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(
            Integer accountId
    ) {
        validateAccountId(accountId);

        /*
         * Chỉ đếm các thông báo được phép hiển thị.
         * Các thông báo ký hóa đơn cũ đã bị loại
         * tại NotificationRepository.
         */
        return notificationRepository
                .countVisibleUnreadForAccount(
                        accountId
                );
    }

    @Transactional(readOnly = true)
    public NotificationStatsResponse getStats(
            Integer accountId
    ) {
        validateAccountId(accountId);

        List<Notification> active =
                notificationRepository
                        .findActiveForAccount(
                                accountId
                        );

        long unread =
                active.stream()
                        .filter(notification ->
                                !Boolean.TRUE.equals(
                                        notification.getIsRead()
                                )
                        )
                        .count();

        long urgent =
                active.stream()
                        .filter(notification ->
                                NotificationSeverity.URGENT.equals(
                                        notification.getSeverity()
                                )
                        )
                        .filter(this::isNotClosed)
                        .count();

        long actionRequired =
                active.stream()
                        .filter(this::isActionRequired)
                        .count();

        return new NotificationStatsResponse(
                active.size(),
                unread,
                actionRequired,
                urgent
        );
    }

    @Transactional(readOnly = true)
    public Optional<NotificationResponse> findForAccount(
            Integer accountId,
            Integer notificationId
    ) {
        validateAccountId(accountId);

        if (notificationId == null) {
            return Optional.empty();
        }

        return notificationRepository
                .findByIdAndAccountId(
                        notificationId,
                        accountId
                )
                .map(NotificationResponse::from);
    }

    @Transactional
    public void markAsRead(
            Integer accountId,
            Integer notificationId
    ) {
        validateAccountId(accountId);

        Notification notification =
                notificationRepository
                        .findByIdAndAccountId(
                                notificationId,
                                accountId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy thông báo"
                                )
                        );

        if (!Boolean.TRUE.equals(
                notification.getIsRead()
        )) {
            notification.setIsRead(true);

            notification.setReadAt(
                    LocalDateTime.now()
            );
        }

        if (NotificationStatus.UNREAD.equals(
                notification.getStatus()
        )) {
            notification.setStatus(
                    NotificationStatus.READ
            );
        }

        notificationRepository.save(
                notification
        );

        notifyAfterCommit(accountId);
    }

    /**
     * Mở thông báo thuộc đúng tài khoản và
     * tự động đánh dấu thông báo là đã đọc.
     *
     * Dữ liệu sau khi cập nhật được trả về để
     * giao diện thay đổi nội dung mà không phải
     * tải lại toàn bộ trang.
     */
    @Transactional
    public NotificationResponse openAndMarkAsRead(
            Integer accountId,
            Integer notificationId
    ) {
        validateAccountId(accountId);

        Notification notification =
                notificationRepository
                        .findByIdAndAccountId(
                                notificationId,
                                accountId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy thông báo"
                                )
                        );

        if (!Boolean.TRUE.equals(
                notification.getIsRead()
        )) {
            notification.setIsRead(true);

            notification.setReadAt(
                    LocalDateTime.now()
            );
        }

        if (NotificationStatus.UNREAD.equals(
                notification.getStatus()
        )) {
            notification.setStatus(
                    NotificationStatus.READ
            );
        }

        Notification saved =
                notificationRepository.save(
                        notification
                );

        notifyAfterCommit(accountId);

        return NotificationResponse.from(saved);
    }

    @Transactional
    public void markAllAsRead(
            Integer accountId
    ) {
        validateAccountId(accountId);

        LocalDateTime now =
                LocalDateTime.now();

        List<Notification> notifications =
                notificationRepository
                        .findActiveForAccount(
                                accountId
                        );

        for (Notification notification
                : notifications) {

            if (!Boolean.TRUE.equals(
                    notification.getIsRead()
            )) {
                notification.setIsRead(true);
                notification.setReadAt(now);
            }

            if (NotificationStatus.UNREAD.equals(
                    notification.getStatus()
            )) {
                notification.setStatus(
                        NotificationStatus.READ
                );
            }
        }

        notificationRepository.saveAll(
                notifications
        );

        notifyAfterCommit(accountId);
    }

    @Transactional
    public void dismiss(
            Integer accountId,
            Integer notificationId
    ) {
        validateAccountId(accountId);

        Notification notification =
                notificationRepository
                        .findByIdAndAccountId(
                                notificationId,
                                accountId
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy thông báo"
                                )
                        );

        notification.setStatus(
                NotificationStatus.DISMISSED
        );

        notification.setIsRead(true);

        if (notification.getReadAt() == null) {
            notification.setReadAt(
                    LocalDateTime.now()
            );
        }

        notification.setIsActive(false);

        notificationRepository.save(
                notification
        );

        notifyAfterCommit(accountId);
    }
    @Transactional
    public Notification createIfMissing(
            Account receiver,
            String targetRole,
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
        if (receiver == null
                || receiver.getId() == null) {
            throw new IllegalArgumentException(
                    "Tài khoản nhận thông báo không hợp lệ"
            );
        }

        /*
         * Nếu cảnh báo cùng dedupeKey vẫn đang hoạt động,
         * không tạo thêm một thông báo trùng lặp.
         */
        if (dedupeKey != null
                && !dedupeKey.isBlank()
                && notificationRepository
                .existsByDedupeKeyAndIsActiveTrue(
                        dedupeKey
                )) {
            return notificationRepository
                    .findFirstByDedupeKeyAndIsActiveTrue(
                            dedupeKey
                    )
                    .orElse(null);
        }

        Notification notification =
                new Notification();

        notification.setAccountID(receiver);

        notification.setTitle(
                nonBlank(
                        title,
                        "Thông báo"
                )
        );

        notification.setMessage(
                nonBlank(
                        message,
                        "Bạn có một thông báo mới"
                )
        );

        notification.setNotificationType(
                nonBlank(
                        notificationType,
                        "SYSTEM"
                )
        );

        notification.setCategory(
                nonBlank(
                        category,
                        NotificationCategory.HE_THONG
                )
        );

        notification.setSeverity(
                nonBlank(
                        severity,
                        NotificationSeverity.INFO
                )
        );

        notification.setStatus(
                NotificationStatus.UNREAD
        );

        notification.setTargetRole(
                nonBlank(
                        targetRole,
                        "SYSTEM"
                )
        );

        notification.setReferenceType(
                referenceType
        );

        notification.setReferenceId(
                referenceId
        );

        notification.setActionUrl(
                actionUrl
        );

        notification.setIsRead(false);
        notification.setReadAt(null);
        notification.setResolvedAt(null);
        notification.setExpiresAt(null);

        notification.setDedupeKey(
                blankToNull(dedupeKey)
        );

        notification.setIsActive(true);

        notification.setCreatedAt(
                Instant.now()
        );

        Notification saved =
                notificationRepository.save(
                        notification
                );

        /*
         * Chỉ gửi sự kiện realtime sau khi transaction
         * tạo thông báo đã commit thành công.
         */
        notifyAfterCommit(
                receiver.getId()
        );

        return saved;
    }

    @Transactional
    public void resolveByDedupeKey(
            String dedupeKey
    ) {
        if (dedupeKey == null
                || dedupeKey.isBlank()) {
            return;
        }

        notificationRepository
                .findFirstByDedupeKeyAndIsActiveTrue(
                        dedupeKey
                )
                .ifPresent(notification -> {
                    notification.setStatus(
                            NotificationStatus.RESOLVED
                    );

                    notification.setResolvedAt(
                            LocalDateTime.now()
                    );

                    notification.setIsActive(false);

                    notificationRepository.save(
                            notification
                    );

                    Integer accountId =
                            notification.getAccountID()
                                    == null
                                    ? null
                                    : notification
                                    .getAccountID()
                                    .getId();

                    notifyAfterCommit(accountId);
                });
    }

    @Transactional
    public void resolveByTypeAndReference(
            String notificationType,
            String referenceType,
            Integer referenceId
    ) {
        if (notificationType == null
                || notificationType.isBlank()
                || referenceType == null
                || referenceType.isBlank()
                || referenceId == null) {
            return;
        }

        List<Notification> notifications =
                notificationRepository
                        .findActiveByTypeAndReference(
                                notificationType,
                                referenceType,
                                referenceId
                        );

        LocalDateTime now =
                LocalDateTime.now();

        /*
         * Lưu các accountID trước khi cập nhật để
         * sau khi commit có thể gửi sự kiện realtime
         * đến đúng người nhận.
         */
        Set<Integer> affectedAccountIds =
                accountIds(notifications);

        for (Notification notification
                : notifications) {
            notification.setStatus(
                    NotificationStatus.RESOLVED
            );

            notification.setResolvedAt(now);
            notification.setIsActive(false);
        }

        notificationRepository.saveAll(
                notifications
        );

        notifyAfterCommit(
                affectedAccountIds
        );
    }

    @Transactional
    public void resolveByReference(
            String referenceType,
            Integer referenceId
    ) {
        if (referenceType == null
                || referenceType.isBlank()
                || referenceId == null) {
            return;
        }

        List<Notification> notifications =
                notificationRepository
                        .findActiveByReference(
                                referenceType,
                                referenceId
                        );

        Set<Integer> affectedAccountIds =
                accountIds(notifications);

        LocalDateTime now =
                LocalDateTime.now();

        for (Notification notification
                : notifications) {
            notification.setStatus(
                    NotificationStatus.RESOLVED
            );

            notification.setResolvedAt(now);
            notification.setIsActive(false);
        }

        notificationRepository.saveAll(
                notifications
        );

        notifyAfterCommit(
                affectedAccountIds
        );
    }
    private boolean isActionRequired(
            Notification notification
    ) {
        if (!isNotClosed(notification)) {
            return false;
        }

        String type =
                notification.getNotificationType();

        String category =
                notification.getCategory();

        /*
         * Chỉ thông báo PENDING mới được tính là
         * công việc cần phê duyệt.
         *
         * APPROVED và REJECTED chỉ là thông báo
         * kết quả, không phải việc đang chờ xử lý.
         */
        boolean pendingApproval =
                NotificationCategory.PHE_DUYET.equals(
                        category
                )
                        && type != null
                        && type.endsWith("_PENDING");

        return pendingApproval
                || NotificationCategory.KY_THUE.equals(
                category
        )
                || (
                type != null
                        && (
                        NotificationSeverity.URGENT.equals(
                                notification.getSeverity()
                        )
                                || "INVOICE_DEBT".equals(type)
                                || "PURCHASE_INVOICE_DUE".equals(
                                type
                        )
                                || "OUT_OF_STOCK".equals(type)
                                || "EXPIRED_BATCH".equals(type)
                                || "TAX_REVENUE_EXCEEDED".equals(
                                type
                        )
                )
        );
    }

    private boolean isNotClosed(
            Notification notification
    ) {
        return !NotificationStatus.RESOLVED.equals(
                notification.getStatus()
        )
                && !NotificationStatus.DISMISSED.equals(
                notification.getStatus()
        );
    }

    private void validateAccountId(
            Integer accountId
    ) {
        if (accountId == null) {
            throw new IllegalArgumentException(
                    "Người dùng chưa đăng nhập"
            );
        }
    }

    private String blankToNull(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String nonBlank(
            String value,
            String fallback
    ) {
        if (value == null
                || value.isBlank()) {
            return fallback;
        }

        return value.trim();
    }

    private Set<Integer> accountIds(
            List<Notification> notifications
    ) {
        return notifications.stream()
                .filter(notification ->
                        notification.getAccountID()
                                != null
                )
                .map(notification ->
                        notification.getAccountID()
                                .getId()
                )
                .filter(
                        java.util.Objects::nonNull
                )
                .collect(
                        Collectors.toSet()
                );
    }

    private void notifyAfterCommit(
            Integer accountId
    ) {
        /*
         * Trong Unit Test, setter realtime có thể
         * chưa được Spring gọi.
         *
         * Khi đó chỉ bỏ qua SSE, không ảnh hưởng đến
         * việc lưu hoặc cập nhật thông báo.
         */
        if (notificationRealtimeService != null) {
            notificationRealtimeService
                    .notifyAccountAfterCommit(
                            accountId
                    );
        }
    }

    private void notifyAfterCommit(
            Iterable<Integer> accountIds
    ) {
        if (notificationRealtimeService != null) {
            notificationRealtimeService
                    .notifyAccountsAfterCommit(
                            accountIds
                    );
        }
    }
}