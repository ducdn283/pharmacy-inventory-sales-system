package com.example.project.config;

import com.example.project.context.CurrentUserContext;
import com.example.project.service.ShiftreportService;
import com.example.project.service.SidebarMenuService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link SidebarInterceptor} so navigation data is available to all HTML views, and
 * {@link PendingShiftInterceptor} which forces an unclosed shift from a previous day to be closed
 * before anything else. Static assets are excluded so neither runs for CSS/JS/images.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SidebarMenuService sidebarMenuService;
    private final CurrentUserContext currentUserContext;
    private final ShiftreportService shiftreportService;

    public WebConfig(SidebarMenuService sidebarMenuService,
                     CurrentUserContext currentUserContext,
                     ShiftreportService shiftreportService) {
        this.sidebarMenuService = sidebarMenuService;
        this.currentUserContext = currentUserContext;
        this.shiftreportService = shiftreportService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Chặn trước khi làm gì khác: phải đứng trước SidebarInterceptor để không phí công dựng menu
        // cho một request sắp bị redirect.
        registry.addInterceptor(new PendingShiftInterceptor(shiftreportService, currentUserContext))
                .addPathPatterns("/**")
                .excludePathPatterns("/assets/**", "/error");

        registry.addInterceptor(new SidebarInterceptor(sidebarMenuService, currentUserContext))
                .addPathPatterns("/**")
                .excludePathPatterns("/assets/**", "/error");
    }
}
