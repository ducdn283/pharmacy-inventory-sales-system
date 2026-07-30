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
     * Request KHÔNG phải điều hướng trang thì không được chặn: đá một lời gọi {@code fetch}/XHR sang
     * trang khác chỉ làm client nhận HTML thay vì JSON, hoặc tệ hơn là tự nạp lại cả trang.
     *
     * <p>Mốc tin cậy nhất là {@code Sec-Fetch-Dest}: trình duyệt gửi {@code document} cho mọi điều
     * hướng thật (gõ URL, bấm link, submit form) và giá trị khác cho fetch/XHR/ảnh/script. Dựa vào
     * header này thay vì {@code X-Requested-With} vì <strong>{@code fetch()} không tự gửi
     * {@code X-Requested-With}</strong> — đúng cái bẫy đã làm topbar tự nạp lại trang: nó gọi
     * {@code fetch('/api/session/check')} không kèm header nào, {@code Accept} mặc định là
     * {@code *&#47;*} nên hai điều kiện cũ đều không khớp.</p>
     *
     * <p>Client cũ / curl không gửi {@code Sec-Fetch-Dest} thì rơi về 2 cách nhận diện cũ.</p>
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
