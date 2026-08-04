package com.example.project.config;

import com.example.project.context.CurrentUserContext;
import com.example.project.service.FinancialsettingService;
import com.example.project.service.NotificationService;
import com.example.project.service.ShiftreportService;
import com.example.project.service.SidebarMenuService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link SetupConfirmedInterceptor} (must run first — the most fundamental gate, blocks
 * every screen until the Owner finalizes Financial Setting), {@link PendingShiftInterceptor} which
 * forces an unclosed shift from a previous day to be closed, and {@link SidebarInterceptor} so
 * navigation data is available to all HTML views. Static assets are excluded so none of them run
 * for CSS/JS/images.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SidebarMenuService sidebarMenuService;
    private final CurrentUserContext currentUserContext;
    private final ShiftreportService shiftreportService;
    private final NotificationService notificationService;
    private final FinancialsettingService financialsettingService;

    public WebConfig(SidebarMenuService sidebarMenuService,
                     CurrentUserContext currentUserContext,
                     ShiftreportService shiftreportService,
                     NotificationService notificationService,
                     FinancialsettingService financialsettingService) {
        this.sidebarMenuService = sidebarMenuService;
        this.currentUserContext = currentUserContext;
        this.shiftreportService = shiftreportService;
        this.notificationService = notificationService;
        this.financialsettingService = financialsettingService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // Chặn trước TẤT CẢ mọi thứ khác — kể cả PendingShiftInterceptor — vì chưa thiết lập xong thì
        // không có ca nào để mở/chốt cả.
        registry.addInterceptor(
                        new SetupConfirmedInterceptor(
                                financialsettingService,
                                currentUserContext
                        )
                )
                .addPathPatterns("/**")
                .excludePathPatterns("/assets/**", "/error");

        // Chặn trước khi làm gì khác: phải đứng trước SidebarInterceptor
        // để không phí công dựng menu cho một request sắp bị redirect.
        registry.addInterceptor(
                        new PendingShiftInterceptor(
                                shiftreportService,
                                currentUserContext
                        )
                )
                .addPathPatterns("/**")
                .excludePathPatterns("/assets/**", "/error");

        registry.addInterceptor(
                        new SidebarInterceptor(
                                sidebarMenuService,
                                currentUserContext,
                                notificationService
                        )
                )
                .addPathPatterns("/**")
                .excludePathPatterns("/assets/**", "/error");
    }
}