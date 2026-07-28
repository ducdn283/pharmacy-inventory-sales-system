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
 * <p>Ca chỉ được tạo khi phát sinh giao dịch và chỉ đóng khi người trực tự chốt, nên một ca có thể
 * kẹt ở {@code Nháp} qua đêm (mất điện, máy sập, về đột xuất). Hôm sau
 * {@code ShiftreportService.ensureOpenShiftFor} tìm thấy đúng ca Nháp đó và dùng lại, khiến mọi hóa
 * đơn/phiếu trả/phiếu thu của hôm nay bị dồn vào ca hôm qua — sai {@code shiftDate}, sai luôn số liệu
 * báo cáo ngày. Không thể tự chốt hộ vì ca đó không có số tiền thực đếm nào đáng tin: người trực đã
 * không đếm két, và két thì có thể đã bị ca sau động vào.</p>
 *
 * <p>Cách chặn: <strong>vẫn cho đăng nhập</strong>, nhưng sau đó mọi thao tác khác bị
 * đá về màn chi tiết ca cũ kèm cảnh báo bắt buộc chốt. Chặn ở tầng interceptor chứ không ở trang đích
 * sau đăng nhập, để người dùng không lách được bằng cách gõ thẳng URL khác.</p>
 *
 * <p>Chỉ chặn điều hướng trang (GET trả HTML). Các đường dưới đây luôn được đi qua, nếu không người
 * dùng sẽ bị khoá cứng không thoát ra được:</p>
 * <ul>
 *   <li>chính màn ca đó và endpoint chốt ca — đích đến của việc chặn;</li>
 *   <li>đăng xuất ({@code /logout}, {@code /logout-guard}) và các màn xác thực;</li>
 *   <li>tài nguyên tĩnh, trang lỗi, và mọi request AJAX/JSON (redirect một lời gọi fetch chỉ tạo ra
 *       lỗi khó hiểu ở phía client thay vì điều hướng trình duyệt).</li>
 * </ul>
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

    private boolean isAjax(HttpServletRequest request) {
        String requestedWith = request.getHeader("X-Requested-With");
        if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && !accept.contains("text/html") && accept.contains("application/json");
    }
}
