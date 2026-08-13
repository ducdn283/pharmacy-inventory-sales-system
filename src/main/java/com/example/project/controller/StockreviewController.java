package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.StockReviewCreateRequest;
import com.example.project.dto.response.StockReviewDetailPageResponse;
import com.example.project.dto.response.StockReviewListItemResponse;
import com.example.project.dto.response.StockReviewPrintPageResponse;
import com.example.project.dto.response.StockReviewVoucherPrintPageResponse;
import com.example.project.service.StockreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StockreviewController {

    private static final String OWNER_BASE = "/owner/stock-reviews";
    private static final String PHARMACIST_BASE = "/pharmacist/stock-reviews";

    /*
     * Giữ URL cũ làm alias để bookmark và thông báo đã tồn tại
     * không bị lỗi 404.
     */
    private static final String OWNER_LEGACY_BASE = "/owner/stock-counts";
    private static final String PHARMACIST_LEGACY_BASE =
            "/pharmacist/stock-counts";

    private final StockreviewService stockreviewService;
    private final CurrentUserContext currentUserContext;

    public StockreviewController(
            StockreviewService stockreviewService,
            CurrentUserContext currentUserContext
    ) {
        this.stockreviewService = stockreviewService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping({
            OWNER_BASE,
            PHARMACIST_BASE,
            OWNER_LEGACY_BASE,
            PHARMACIST_LEGACY_BASE
    })
    public String list(
            @RequestParam(name = "keyword", required = false)
            String keyword,
            @RequestParam(name = "fromDate", required = false)
            String fromDate,
            @RequestParam(name = "toDate", required = false)
            String toDate,
            @RequestParam(name = "type", required = false)
            String type,
            @RequestParam(name = "status", required = false)
            String status,
            @RequestParam(name = "page", defaultValue = "0")
            int page,
            @RequestParam(name = "size", defaultValue = "5")
            int size,
            HttpServletRequest request,
            Model model
    ) {
        page = Math.max(page, 0);
        size = size <= 0 ? 5 : size;

        Page<StockReviewListItemResponse> reviewPage =
                stockreviewService.search(
                        keyword,
                        fromDate,
                        toDate,
                        type,
                        status,
                        PageRequest.of(page, size)
                );

        model.addAttribute("reviewPage", reviewPage);
        model.addAttribute("reviews", reviewPage.getContent());
        model.addAttribute("stats", stockreviewService.getStats());
        model.addAttribute("statuses", stockreviewService.listStatuses());
        model.addAttribute("typeLabels", stockreviewService.typeLabels());
        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterType", type);
        model.addAttribute("filterStatus", status);
        model.addAttribute("currentPage", reviewPage.getNumber());
        model.addAttribute("totalPages", reviewPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", reviewPage.getTotalElements());
        model.addAttribute("basePath", resolveBasePath(request));

        return "stock-review/list";
    }

    @GetMapping({
            OWNER_BASE + "/create",
            PHARMACIST_BASE + "/create",
            OWNER_LEGACY_BASE + "/create",
            PHARMACIST_LEGACY_BASE + "/create"
    })
    public String createPage(
            @RequestParam(name = "keyword", required = false)
            String keyword,
            HttpServletRequest request,
            Model model
    ) {
        populateCreateModel(
                model,
                stockreviewService.buildDefaultForm(),
                keyword,
                request
        );
        return "stock-review/create";
    }

    @PostMapping({
            OWNER_BASE + "/create",
            PHARMACIST_BASE + "/create",
            OWNER_LEGACY_BASE + "/create",
            PHARMACIST_LEGACY_BASE + "/create"
    })
    public String create(
            @ModelAttribute("form") StockReviewCreateRequest form,
            @RequestParam(name = "action", required = false)
            String action,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        String basePath = resolveBasePath(request);
        boolean asDraft = "draft".equals(action);

        try {
            Integer stockReviewId = stockreviewService.create(
                    form,
                    currentUserContext.getCurrentAccountId(),
                    currentUserContext.isOwner(),
                    asDraft
            );

            String message = asDraft
                    ? "Đã lưu nháp phiếu rà soát kho"
                    : currentUserContext.isOwner()
                    ? "Tạo phiếu rà soát kho thành công và đã tự động duyệt"
                    : "Đã gửi phiếu rà soát kho cho chủ nhà thuốc duyệt";

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    message
            );

            return "redirect:"
                    + basePath
                    + "/"
                    + stockReviewId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
            populateCreateModel(model, form, null, request);
            return "stock-review/create";
        }
    }

    @GetMapping({
            OWNER_BASE + "/print",
            PHARMACIST_BASE + "/print",
            OWNER_LEGACY_BASE + "/print",
            PHARMACIST_LEGACY_BASE + "/print"
    })
    public String printPage(
            @RequestParam(name = "type", defaultValue = "COUNT")
            String type,
            HttpServletRequest request,
            Model model
    ) {
        StockReviewPrintPageResponse printData =
                stockreviewService.getPrintPage(
                        currentUserContext.getCurrentAccountName(),
                        type
                );

        model.addAttribute("printData", printData);
        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );

        return "stock-review/print";
    }

    @GetMapping({
            OWNER_BASE + "/{stockReviewId}/print",
            PHARMACIST_BASE + "/{stockReviewId}/print",
            OWNER_LEGACY_BASE + "/{stockReviewId}/print",
            PHARMACIST_LEGACY_BASE + "/{stockReviewId}/print"
    })
    public String printVoucher(
            @PathVariable Integer stockReviewId,
            HttpServletRequest request,
            Model model
    ) {
        StockReviewVoucherPrintPageResponse printData =
                stockreviewService.getVoucherPrintPage(stockReviewId);

        model.addAttribute("printData", printData);
        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );

        return "stock-review/print-voucher";
    }

    @GetMapping({
            OWNER_BASE + "/{stockReviewId}",
            PHARMACIST_BASE + "/{stockReviewId}",
            OWNER_LEGACY_BASE + "/{stockReviewId}",
            PHARMACIST_LEGACY_BASE + "/{stockReviewId}"
    })
    public String detail(
            @PathVariable Integer stockReviewId,
            HttpServletRequest request,
            Model model
    ) {
        StockReviewDetailPageResponse detail =
                stockreviewService.getDetail(stockReviewId);

        model.addAttribute("detail", detail);
        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );
        model.addAttribute(
                "isOwner",
                currentUserContext.isOwner()
        );

        return "stock-review/detail";
    }

    @PostMapping({
            OWNER_BASE + "/{stockReviewId}/submit",
            PHARMACIST_BASE + "/{stockReviewId}/submit",
            OWNER_LEGACY_BASE + "/{stockReviewId}/submit",
            PHARMACIST_LEGACY_BASE + "/{stockReviewId}/submit"
    })
    public String submit(
            @PathVariable Integer stockReviewId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        String basePath = resolveBasePath(request);

        try {
            stockreviewService.submit(
                    stockReviewId,
                    currentUserContext.getCurrentAccountId(),
                    currentUserContext.isOwner()
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    currentUserContext.isOwner()
                            ? "Đã duyệt phiếu rà soát kho"
                            : "Đã gửi phiếu rà soát kho cho chủ nhà thuốc duyệt"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:"
                + basePath
                + "/"
                + stockReviewId;
    }

    @PostMapping({
            OWNER_BASE + "/{stockReviewId}/approve",
            OWNER_LEGACY_BASE + "/{stockReviewId}/approve"
    })
    public String approve(
            @PathVariable Integer stockReviewId,
            @RequestParam(name = "redirectTo", required = false)
            String redirectTo,
            RedirectAttributes redirectAttributes
    ) {
        try {
            stockreviewService.approve(
                    stockReviewId,
                    currentUserContext.getCurrentAccountId()
            );
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã duyệt phiếu rà soát kho"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:"
                + (
                redirectTo != null && !redirectTo.isBlank()
                        ? redirectTo
                        : OWNER_BASE + "/" + stockReviewId
        );
    }

    @PostMapping({
            OWNER_BASE + "/{stockReviewId}/reject",
            OWNER_LEGACY_BASE + "/{stockReviewId}/reject"
    })
    public String reject(
            @PathVariable Integer stockReviewId,
            @RequestParam(name = "redirectTo", required = false)
            String redirectTo,
            RedirectAttributes redirectAttributes
    ) {
        try {
            stockreviewService.reject(
                    stockReviewId,
                    currentUserContext.getCurrentAccountId()
            );
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã từ chối phiếu rà soát kho"
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:"
                + (
                redirectTo != null && !redirectTo.isBlank()
                        ? redirectTo
                        : OWNER_BASE + "/" + stockReviewId
        );
    }

    private void populateCreateModel(
            Model model,
            StockReviewCreateRequest form,
            String keyword,
            HttpServletRequest request
    ) {
        model.addAttribute("form", form);

        /*
         * Tìm kiếm trong bảng được xử lý phía client để chỉ số
         * items[] luôn khớp đúng lô hàng.
         */
        model.addAttribute(
                "candidates",
                stockreviewService.listReviewableBatches(null)
        );
        model.addAttribute(
                "typeLabels",
                stockreviewService.typeLabels()
        );
        model.addAttribute(
                "conditionLabels",
                stockreviewService.conditionLabels()
        );
        model.addAttribute(
                "creatorName",
                currentUserContext.getCurrentAccountName()
        );
        model.addAttribute("keyword", keyword);
        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );
    }

    private String resolveBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/owner/")
                ? OWNER_BASE
                : PHARMACIST_BASE;
    }
}