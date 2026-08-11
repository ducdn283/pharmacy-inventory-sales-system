package com.example.project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class NotificationRealtimeService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationRealtimeService.class);

    private static final long CONNECTION_TIMEOUT_MS =
            30L * 60L * 1000L;

    private final Map<
            Integer,
            CopyOnWriteArrayList<SseEmitter>
            > emitters = new ConcurrentHashMap<>();

    /**
     * Mở một kết nối SSE cho tài khoản hiện tại.
     *
     * Một tài khoản có thể đăng nhập trên nhiều tab hoặc nhiều trình duyệt,
     * vì vậy mỗi accountID có thể sở hữu nhiều SseEmitter.
     */
    public SseEmitter subscribe(Integer accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException(
                    "Người dùng chưa đăng nhập"
            );
        }

        SseEmitter emitter =
                new SseEmitter(CONNECTION_TIMEOUT_MS);

        emitters.computeIfAbsent(
                accountId,
                ignored -> new CopyOnWriteArrayList<>()
        ).add(emitter);

        Runnable cleanup =
                () -> remove(accountId, emitter);

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        /*
         * Gửi sự kiện đầu tiên ngay sau khi kết nối.
         * Việc này giúp trình duyệt biết rằng kết nối SSE đã thành công.
         */
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("connected")
                            .data("connected")
            );
        } catch (IOException exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }

        return emitter;
    }

    /**
     * Đẩy yêu cầu cập nhật notification sau khi transaction hiện tại
     * đã commit thành công.
     *
     * Không gửi trước khi commit vì giao dịch có thể bị rollback.
     */
    public void notifyAccountAfterCommit(
            Integer accountId
    ) {
        if (accountId == null) {
            return;
        }

        if (TransactionSynchronizationManager
                .isActualTransactionActive()
                && TransactionSynchronizationManager
                .isSynchronizationActive()) {

            TransactionSynchronizationManager
                    .registerSynchronization(
                            new TransactionSynchronization() {

                                @Override
                                public void afterCommit() {
                                    notifyAccount(accountId);
                                }
                            }
                    );

            return;
        }

        /*
         * Nếu method được gọi ngoài transaction thì gửi luôn.
         */
        notifyAccount(accountId);
    }

    /**
     * Gửi sự kiện cho nhiều tài khoản sau khi transaction commit.
     */
    public void notifyAccountsAfterCommit(
            Iterable<Integer> accountIds
    ) {
        if (accountIds == null) {
            return;
        }

        for (Integer accountId : accountIds) {
            notifyAccountAfterCommit(accountId);
        }
    }

    /**
     * Gửi sự kiện refresh đến tất cả tab đang mở của tài khoản.
     */
    private void notifyAccount(Integer accountId) {
        List<SseEmitter> accountEmitters =
                emitters.get(accountId);

        if (accountEmitters == null
                || accountEmitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : accountEmitters) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("notification-refresh")
                                .data("refresh")
                );
            } catch (IOException
                     | IllegalStateException exception) {

                /*
                 * Tab đã đóng hoặc kết nối đã hết hạn.
                 */
                remove(accountId, emitter);

                /*
                 * emitter.complete() có thể tự ném IllegalStateException nếu
                 * AsyncContext bên dưới đã ở trạng thái lỗi từ trước (ví dụ
                 * trình duyệt vừa đóng tab đúng lúc này) — gọi từ luồng xử lý
                 * request nghiệp vụ hiện tại (không phải luồng gốc của kết nối
                 * SSE) bị Tomcat coi là "non-container thread" và chặn lại.
                 * Đây chỉ là dọn dẹp một kết nối đã chết, không liên quan gì
                 * đến nghiệp vụ vừa commit thành công (duyệt phiếu, tạo hóa
                 * đơn, ...) nên tuyệt đối không được để lỗi này thoát ra ngoài
                 * afterCommit() — nếu không, request nghiệp vụ đã commit xong
                 * vẫn trả về lỗi 500 cho người dùng dù thao tác đã thành công.
                 */
                try {
                    emitter.complete();
                } catch (RuntimeException cleanupException) {
                    log.debug(
                            "Không thể đóng SSE emitter đã chết cho accountId={}",
                            accountId,
                            cleanupException
                    );
                }
            }
        }
    }

    /**
     * Xóa kết nối đã đóng khỏi bộ nhớ.
     */
    private void remove(
            Integer accountId,
            SseEmitter emitter
    ) {
        CopyOnWriteArrayList<SseEmitter>
                accountEmitters = emitters.get(accountId);

        if (accountEmitters == null) {
            return;
        }

        accountEmitters.remove(emitter);

        if (accountEmitters.isEmpty()) {
            emitters.remove(
                    accountId,
                    accountEmitters
            );
        }
    }
}