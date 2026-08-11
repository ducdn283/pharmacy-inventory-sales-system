package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.response.NotificationRealtimeResponse;
import com.example.project.service.NotificationRealtimeService;
import com.example.project.service.NotificationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/notifications/realtime")
public class NotificationRealtimeController {

    private final CurrentUserContext currentUserContext;
    private final NotificationService notificationService;

    private final NotificationRealtimeService
            notificationRealtimeService;

    public NotificationRealtimeController(
            CurrentUserContext currentUserContext,
            NotificationService notificationService,
            NotificationRealtimeService
                    notificationRealtimeService
    ) {
        this.currentUserContext = currentUserContext;
        this.notificationService = notificationService;
        this.notificationRealtimeService =
                notificationRealtimeService;
    }

    /**
     * Mở kết nối SSE cho tài khoản đang đăng nhập.
     *
     * Trình duyệt sẽ giữ kết nối này để nhận sự kiện khi có
     * notification mới hoặc trạng thái notification thay đổi.
     */
    @GetMapping(
            value = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream() {
        return notificationRealtimeService.subscribe(
                requireAccountId()
        );
    }

    /**
     * Trả dữ liệu mới nhất dùng để cập nhật topbar:
     *
     * - Tổng số notification chưa đọc.
     * - Năm notification đang hoạt động gần nhất.
     */
    @GetMapping("/summary")
    public NotificationRealtimeResponse summary() {
        Integer accountId = requireAccountId();

        return new NotificationRealtimeResponse(
                notificationService.unreadCount(accountId),
                notificationService.latest(accountId)
        );
    }

    private Integer requireAccountId() {
        Integer accountId =
                currentUserContext.getCurrentAccountId();

        if (accountId == null) {
            throw new IllegalArgumentException(
                    "Người dùng chưa đăng nhập"
            );
        }

        return accountId;
    }
}