package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.StockCountCreateRequest;
import com.example.project.dto.response.StockCountDetailPageResponse;
import com.example.project.dto.response.StockCountListItemResponse;
import com.example.project.dto.response.StockCountPrintPageResponse;
import com.example.project.dto.response.StockCountVoucherPrintPageResponse;
import com.example.project.service.StockcountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StockcountController {

    private static final String OWNER_BASE = "/owner/stock-counts";
    private static final String PHARMACIST_BASE = "/pharmacist/stock-counts";

    private final StockcountService stockcountService;
    private final CurrentUserContext currentUserContext;

    public StockcountController(StockcountService stockcountService,
                                CurrentUserContext currentUserContext) {
        this.stockcountService = stockcountService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping({
            OWNER_BASE,
            PHARMACIST_BASE
    })
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "fromDate", required = false) String fromDate,
                       @RequestParam(name = "toDate", required = false) String toDate,
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

        Page<StockCountListItemResponse> countPage = stockcountService.search(
                keyword,
                fromDate,
                toDate,
                status,
                PageRequest.of(page, size)
        );

        model.addAttribute("countPage", countPage);
        model.addAttribute("counts", countPage.getContent());
        model.addAttribute("stats", stockcountService.getStats());
        model.addAttribute("statuses", stockcountService.listStatuses());

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterStatus", status);

        model.addAttribute("currentPage", countPage.getNumber());
        model.addAttribute("totalPages", countPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", countPage.getTotalElements());

        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-count/list";
    }

    @GetMapping({
            OWNER_BASE + "/create",
            PHARMACIST_BASE + "/create"
    })
    public String createPage(@RequestParam(name = "keyword", required = false) String keyword,
                             HttpServletRequest request,
                             Model model) {
        StockCountCreateRequest form = stockcountService.buildDefaultForm();

        model.addAttribute("form", form);
        model.addAttribute("candidates", stockcountService.listCountableBatches(keyword));
        model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
        model.addAttribute("keyword", keyword);
        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-count/create";
    }

    @PostMapping({
            OWNER_BASE + "/create",
            PHARMACIST_BASE + "/create"
    })
    public String create(@ModelAttribute("form") StockCountCreateRequest form,
                         @RequestParam(name = "action", required = false) String action,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        String basePath = resolveBasePath(request);
        boolean asDraft = "draft".equals(action);

        try {
            Integer stockCountId = stockcountService.create(
                    form,
                    currentUserContext.getCurrentAccountId(),
                    currentUserContext.isOwner(),
                    asDraft
            );

            String message;

            if (asDraft) {
                message = "Đã lưu nháp phiếu kiểm kê";
            } else if (currentUserContext.isOwner()) {
                message = "Tạo phiếu kiểm kê thành công và đã tự động duyệt";
            } else {
                message = "Đã gửi phiếu kiểm kê cho chủ nhà thuốc duyệt";
            }

            redirectAttributes.addFlashAttribute("successMessage", message);

            return "redirect:" + basePath + "/" + stockCountId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("form", form);
            model.addAttribute("candidates", stockcountService.listCountableBatches(null));
            model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
            model.addAttribute("keyword", null);
            model.addAttribute("basePath", basePath);

            return "stock-count/create";
        }
    }

    @GetMapping({
            OWNER_BASE + "/print",
            PHARMACIST_BASE + "/print"
    })
    public String printPage(HttpServletRequest request,
                            Model model) {
        StockCountPrintPageResponse printData =
                stockcountService.getPrintPage(currentUserContext.getCurrentAccountName());

        model.addAttribute("printData", printData);
        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-count/print";
    }

    @GetMapping({
            OWNER_BASE + "/{stockCountId}/print",
            PHARMACIST_BASE + "/{stockCountId}/print"
    })
    public String printVoucher(@PathVariable Integer stockCountId,
                               HttpServletRequest request,
                               Model model) {
        StockCountVoucherPrintPageResponse printData =
                stockcountService.getVoucherPrintPage(stockCountId);

        model.addAttribute("printData", printData);
        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-count/print-voucher";
    }

    @GetMapping({
            OWNER_BASE + "/{stockCountId}",
            PHARMACIST_BASE + "/{stockCountId}"
    })
    public String detail(@PathVariable Integer stockCountId,
                         HttpServletRequest request,
                         Model model) {
        StockCountDetailPageResponse detail = stockcountService.getDetail(stockCountId);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("isOwner", currentUserContext.isOwner());

        return "stock-count/detail";
    }

    @PostMapping({
            OWNER_BASE + "/{stockCountId}/submit",
            PHARMACIST_BASE + "/{stockCountId}/submit"
    })
    public String submit(@PathVariable Integer stockCountId,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);

        try {
            stockcountService.submit(
                    stockCountId,
                    currentUserContext.getCurrentAccountId(),
                    currentUserContext.isOwner()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    currentUserContext.isOwner()
                            ? "Đã duyệt phiếu kiểm kê"
                            : "Đã gửi phiếu kiểm kê cho chủ nhà thuốc duyệt"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + basePath + "/" + stockCountId;
    }

    @PostMapping(OWNER_BASE + "/{stockCountId}/approve")
    public String approve(@PathVariable Integer stockCountId,
                          @RequestParam(name = "redirectTo", required = false) String redirectTo,
                          RedirectAttributes redirectAttributes) {
        try {
            stockcountService.approve(stockCountId, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã duyệt phiếu kiểm kê");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : OWNER_BASE + "/" + stockCountId);
    }

    @PostMapping(OWNER_BASE + "/{stockCountId}/reject")
    public String reject(@PathVariable Integer stockCountId,
                         @RequestParam(name = "redirectTo", required = false) String redirectTo,
                         RedirectAttributes redirectAttributes) {
        try {
            stockcountService.reject(stockCountId, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối phiếu kiểm kê");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + (redirectTo != null && !redirectTo.isBlank() ? redirectTo : OWNER_BASE + "/" + stockCountId);
    }

    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith(OWNER_BASE)) {
            return OWNER_BASE;
        }

        return PHARMACIST_BASE;
    }
}