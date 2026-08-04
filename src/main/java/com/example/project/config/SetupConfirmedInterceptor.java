package com.example.project.config;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.service.FinancialsettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Bắt MỌI role phải chờ Owner "Chốt thiết lập ban đầu" ({@code Financialsetting.setupConfirmed})
 * trước khi dùng bất kỳ màn hình nào khác — kể cả Pharmacist/Accountant, vì gần như mọi nghiệp vụ
 * (bán hàng, kỳ thuế, chi/thu...) đều phụ thuộc vào nhóm doanh thu và hai quỹ mà bước thiết lập này
 * chốt lại. Chỉ Owner mới thực sự sửa được màn Thiết lập tài chính
 * ({@link com.example.project.controller.FinancialSettingPageController#view}, {@code editable}) —
 * Pharmacist/Accountant bị đá tới bản xem chỉ-đọc của chính họ và chỉ có thể chờ.
 *
 * <p>Khác {@link PendingShiftInterceptor} ở chỗ KHÔNG cho lối thoát nào ngoài chính màn Thiết lập
 * tài chính — đây là chặn cứng theo đúng yêu cầu, không có ngoại lệ như {@code /shift-reports}.</p>
 */
public class SetupConfirmedInterceptor implements HandlerInterceptor {

    private final FinancialsettingService financialsettingService;
    private final CurrentUserContext currentUserContext;

    public SetupConfirmedInterceptor(FinancialsettingService financialsettingService,
                                     CurrentUserContext currentUserContext) {
        this.financialsettingService = financialsettingService;
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
        if (financialsettingService.isSetupConfirmed()) {
            return true;
        }

        String target = "/" + RoleConstants.urlPrefix(role) + "/financial-setting";
        if (request.getRequestURI().startsWith(target)) {
            return true;
        }

        response.sendRedirect(request.getContextPath() + target);
        return false;
    }

    /** Đường đi luôn phải thông, nếu không người dùng bị kẹt không đăng xuất/thiết lập được. */
    private boolean isAlwaysAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/assets/") || uri.startsWith("/error")) {
            return true;
        }
        // Endpoint JSON: không bao giờ là điều hướng trang thật, redirect chỉ làm client nhận HTML
        // thay vì JSON rồi báo lỗi khó hiểu (topbar gọi /api/... định kỳ).
        if (uri.startsWith("/api/")) {
            return true;
        }
        if (uri.equals("/logout") || uri.equals("/logout-guard")
                || uri.equals("/signin") || uri.equals("/403")
                || uri.startsWith("/forgot-password") || uri.startsWith("/reset-password")) {
            return true;
        }
        // Màn Thiết lập tài chính của MỌI role: chặn xong vẫn phải vào được để xem/chốt.
        if (uri.contains("/financial-setting")) {
            return true;
        }
        return isAjax(request);
    }

    /**
     * Nhận diện request KHÔNG phải điều hướng trang, dựa vào {@code Sec-Fetch-Dest} — cùng cơ chế
     * {@link PendingShiftInterceptor#isAjax}, xem javadoc ở đó để hiểu lý do dùng header này.
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
