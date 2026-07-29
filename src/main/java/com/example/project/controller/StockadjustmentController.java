package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.StockAdjustmentCreateRequest;
import com.example.project.dto.response.StockAdjustmentCountLineResponse;
import com.example.project.dto.response.StockAdjustmentDetailPageResponse;
import com.example.project.dto.response.StockAdjustmentListItemResponse;
import com.example.project.service.StockadjustmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Stock Adjustment screens (list / detail / create / complete / cancel).
 *
 * <p><strong>Owner-only.</strong> Bảng phân quyền màn hình ({@code Nghiệp vụ.xlsx}, BA 2026-07-27)
 * ghi <em>"Chỉ Owner tạo"</em> phiếu điều chỉnh kho — Dược sĩ và Kế toán không có ô quyền nào ở
 * nghiệp vụ này. Vì chỉ có một người vừa lập vừa chịu trách nhiệm, luồng duyệt chéo bị bỏ: phiếu đi
 * thẳng {@code Nháp → Hoàn thành}, lập sai thì {@code Hủy} để đảo ngược. Route-level access đã được
 * SecurityConfig chặn sẵn ({@code /owner/**} → OWNER).</p>
 */
@Controller
public class StockadjustmentController {

    private static final String OWNER_BASE = "/owner/stock-adjustments";

    private final StockadjustmentService stockadjustmentService;
    private final CurrentUserContext currentUserContext;

    public StockadjustmentController(StockadjustmentService stockadjustmentService,
                                     CurrentUserContext currentUserContext) {
        this.stockadjustmentService = stockadjustmentService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping(OWNER_BASE)
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "fromDate", required = false) String fromDate,
                       @RequestParam(name = "toDate", required = false) String toDate,
                       @RequestParam(name = "adjustmentType", required = false) String adjustmentType,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size", defaultValue = "5") int size,
                       HttpServletRequest request,
                       Model model) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 5;
        }

        Page<StockAdjustmentListItemResponse> adjustmentPage =
                stockadjustmentService.search(keyword, fromDate, toDate, adjustmentType, status,
                        PageRequest.of(page, size));

        model.addAttribute("adjustmentPage", adjustmentPage);
        model.addAttribute("adjustments", adjustmentPage.getContent());
        model.addAttribute("stats", stockadjustmentService.getStats());

        model.addAttribute("statuses", stockadjustmentService.listStatuses());
        model.addAttribute("adjustmentTypeLabels", stockadjustmentService.adjustmentTypeLabels());

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterAdjustmentType", adjustmentType);
        model.addAttribute("filterStatus", status);

        model.addAttribute("currentPage", adjustmentPage.getNumber());
        model.addAttribute("totalPages", adjustmentPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", adjustmentPage.getTotalElements());

        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-adjustment/list";
    }

    @GetMapping(OWNER_BASE + "/create")
    public String createPage(@RequestParam(name = "keyword", required = false) String keyword,
                             HttpServletRequest request,
                             Model model) {
        model.addAttribute("form", new StockAdjustmentCreateRequest());
        model.addAttribute("candidates", stockadjustmentService.listAvailableBatches(keyword));
        model.addAttribute("creatableTypeLabels", stockadjustmentService.creatableTypeLabels());
        model.addAttribute("approvedStockCounts", stockadjustmentService.listApprovedStockCounts());
        // Mã dự kiến, chỉ để xem — mã thật sinh lúc lưu (xem previewNextCode).
        model.addAttribute("nextCode", stockadjustmentService.previewNextCode());
        model.addAttribute("keyword", keyword);
        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-adjustment/create";
    }

    /** JSON: the prospective COUNT lines of an approved stock count, for the create screen's dropdown. */
    @GetMapping(value = OWNER_BASE + "/stock-counts/{stockCountId}/lines",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<StockAdjustmentCountLineResponse> stockCountLines(@PathVariable Integer stockCountId) {
        return stockadjustmentService.loadStockCountLines(stockCountId);
    }

    @PostMapping(OWNER_BASE + "/create")
    public String create(@ModelAttribute("form") StockAdjustmentCreateRequest form,
                         @RequestParam(name = "action", required = false) String action,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        String basePath = resolveBasePath(request);
        boolean asDraft = "draft".equals(action);
        try {
            Integer adjustmentId = stockadjustmentService.createAdjustment(form, asDraft);

            redirectAttributes.addFlashAttribute("successMessage", asDraft
                    ? "Đã lưu nháp phiếu điều chỉnh kho"
                    : "Đã tạo và thực hiện phiếu điều chỉnh kho (tồn kho đã cập nhật)");
            return "redirect:" + basePath + "/" + adjustmentId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("form", form);
            model.addAttribute("candidates", stockadjustmentService.listAvailableBatches(null));
            model.addAttribute("creatableTypeLabels", stockadjustmentService.creatableTypeLabels());
            model.addAttribute("approvedStockCounts", stockadjustmentService.listApprovedStockCounts());
                model.addAttribute("nextCode", stockadjustmentService.previewNextCode());
            model.addAttribute("keyword", null);
            model.addAttribute("basePath", basePath);
            return "stock-adjustment/create";
        }
    }

    /** Nháp → Hoàn thành: áp tồn kho ngay (không còn bước gửi duyệt). */
    @PostMapping(OWNER_BASE + "/{adjustmentId}/complete")
    public String complete(@PathVariable Integer adjustmentId,
                           RedirectAttributes redirectAttributes) {
        try {
            stockadjustmentService.complete(adjustmentId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã thực hiện phiếu điều chỉnh kho (tồn kho đã cập nhật)");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + OWNER_BASE + "/" + adjustmentId;
    }

    @GetMapping(OWNER_BASE + "/{adjustmentId}")
    public String detail(@PathVariable Integer adjustmentId,
                         HttpServletRequest request,
                         Model model) {
        StockAdjustmentDetailPageResponse detail = stockadjustmentService.getDetail(adjustmentId);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-adjustment/detail";
    }

    /** Hủy phiếu lập sai — phiếu đã Hoàn thành thì tồn kho được đảo ngược (xem service). */
    @PostMapping(OWNER_BASE + "/{adjustmentId}/cancel")
    public String cancel(@PathVariable Integer adjustmentId,
                         // Lý do HỦY — tên khác "reason" của form tạo để không ai nhầm 2 trường này.
                         @RequestParam(name = "cancelReason", required = false) String reason,
                         RedirectAttributes redirectAttributes) {
        try {
            stockadjustmentService.cancel(adjustmentId, reason);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã hủy phiếu điều chỉnh kho (tồn kho đã được đảo lại)");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + OWNER_BASE + "/" + adjustmentId;
    }

    /** Giữ nguyên chữ ký cũ để template không phải đổi: nay chỉ còn duy nhất base của Owner. */
    private String resolveBasePath(HttpServletRequest request) {
        return OWNER_BASE;
    }
}
