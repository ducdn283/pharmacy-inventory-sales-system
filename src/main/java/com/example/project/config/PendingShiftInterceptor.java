package com.example.project.config;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.entity.Shiftreport;
import com.example.project.service.ShiftreportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * Bắt người dùng chốt ca còn dở từ ngày trước rồi mới cho làm việc tiếp.
 *
 * <p>Ca có thể kẹt ở {@code Nháp} qua đêm (lý do bất đắc dĩ). Hôm sau
 * {@code ShiftreportService.ensureOpenShiftFor} dùng lại đúng ca đó, khiến mọi giao dịch hôm nay bị
 * dồn vào ca hôm qua — sai {@code shiftDate} lẫn số liệu. Không tự chốt hộ được vì ca đó không có số
 * tiền thực đếm nào đáng tin.</p>
 *
 * <p><strong>Vẫn cho đăng nhập</strong>, nhưng mọi thao tác khác bị đá về màn ca cũ kèm cảnh báo.
 * Chặn ở interceptor để không lách được bằng cách gõ thẳng URL. Chỉ chặn điều hướng trang — xem
 * {@link #isAlwaysAllowed} cho các đường luôn phải thông.</p>
 */
public class PendingShiftInterceptor implements HandlerInterceptor {

    private final ShiftreportService shiftreportService;
    private final CurrentUserContext currentUserContext;

    public PendingShiftInterceptor(ShiftreportService shiftreportService,
                                   CurrentUserContext currentUserContext) {
        this.shiftreportService = shiftreportService;
        this.currentUserContext = currentUserContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!currentUserContext.isAuthenticated()) {
            return true;
        }
        Integer accountId = currentUserContext.getCurrentAccountId();
        String role = currentUserContext.getCurrentRole();
        if (accountId == null || !RoleConstants.isValid(role)) {
            return true;
        }
        if (isAlwaysAllowed(request)) {
            return true;
        }

        Optional<Shiftreport> stale = shiftreportService.findStaleDraftShift(accountId);
        if (stale.isEmpty()) {
            return true;
        }

        String target = "/" + RoleConstants.urlPrefix(role) + "/shift-reports/" + stale.get().getId();
        if (request.getRequestURI().startsWith(target)) {
            return true;
        }

        response.sendRedirect(request.getContextPath() + target);
        return false;
    }

    /** Đường đi luôn phải thông, nếu không người dùng bị kẹt không chốt được ca lẫn không thoát được. */
    private boolean isAlwaysAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/assets/") || uri.startsWith("/error")) {
            return true;
        }
        // Endpoint JSON: không bao giờ là điều hướng trang. Riêng /api/session/check bị topbar gọi
        // 30 giây một lần — chặn nó chính là nguyên nhân trang tự nạp lại giữa lúc người dùng đang gõ
        // số chốt ca (topbar thấy response bị redirect nên gọi window.location.replace).
        if (uri.startsWith("/api/")) {
            return true;
        }
        if (uri.equals("/logout") || uri.equals("/logout-guard")
                || uri.equals("/signin") || uri.equals("/403")
                || uri.startsWith("/forgot-password") || uri.startsWith("/reset-password")) {
            return true;
        }
        // Màn chốt ca của MỌI role: chặn xong vẫn phải vào được để chốt.
        if (uri.contains("/shift-reports")) {
            return true;
        }
        // Redirect một lời gọi fetch/XHR chỉ làm client nhận HTML thay vì JSON rồi báo lỗi khó hiểu.
        return isAjax(request);
    }

    /**
     * Nhận diện request KHÔNG phải điều hướng trang, dựa vào {@code Sec-Fetch-Dest}: trình duyệt gửi
     * {@code document} cho mọi điều hướng thật và giá trị khác cho fetch/XHR/ảnh/script.
     *
     * <p>Dùng header này chứ không phải {@code X-Requested-With} vì <strong>{@code fetch()} không tự
     * gửi {@code X-Requested-With}</strong>, và {@code Accept} mặc định của nó là {@code *&#47;*} —
     * đúng cái bẫy đã làm topbar tự nạp lại trang giữa lúc đang gõ số chốt ca. Client cũ / curl không
     * gửi {@code Sec-Fetch-Dest} thì rơi về 2 cách nhận diện cũ.</p>
     */
    private boolean isAjax(HttpServletRequest request) {
        String fetchDest = request.getHeader("Sec-Fetch-Dest");
        if (fetchDest != null && !fetchDest.isBlank()) {
            return !"document".equalsIgnoreCase(fetchDest);
        }
        String requestedWith = request.getHeader("X-Requested-With");
        if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && !accept.contains("text/html") && accept.contains("application/json");
    }
}
