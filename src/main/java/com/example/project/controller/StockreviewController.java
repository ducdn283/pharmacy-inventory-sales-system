package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.StockReviewCreateRequest;
import com.example.project.dto.response.StockReviewBatchCandidateResponse;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class StockreviewController {

    private static final String OWNER_BASE =
            "/owner/stock-reviews";

    private static final String PHARMACIST_BASE =
            "/pharmacist/stock-reviews";

    /*
     * Giữ URL cũ làm alias để bookmark và thông báo cũ
     * không bị lỗi 404.
     */
    private static final String OWNER_LEGACY_BASE =
            "/owner/stock-counts";

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

        model.addAttribute(
                "reviewPage",
                reviewPage
        );

        model.addAttribute(
                "reviews",
                reviewPage.getContent()
        );

        model.addAttribute(
                "stats",
                stockreviewService.getStats()
        );

        model.addAttribute(
                "statuses",
                stockreviewService.listStatuses()
        );

        /*
         * Trang danh sách vẫn giữ đầy đủ type để các phiếu
         * CONDITION cũ có thể tiếp tục hiển thị.
         */
        model.addAttribute(
                "typeLabels",
                stockreviewService.typeLabels()
        );

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterType", type);
        model.addAttribute("filterStatus", status);

        model.addAttribute(
                "currentPage",
                reviewPage.getNumber()
        );

        model.addAttribute(
                "totalPages",
                reviewPage.getTotalPages()
        );

        model.addAttribute(
                "pageSize",
                size
        );

        model.addAttribute(
                "totalItems",
                reviewPage.getTotalElements()
        );

        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );

        return "stock-review/list";
    }

    /*
     * Mở màn hình tạo phiếu mới.
     */
    @GetMapping({
            OWNER_BASE + "/create",
            PHARMACIST_BASE + "/create",
            OWNER_LEGACY_BASE + "/create",
            PHARMACIST_LEGACY_BASE + "/create"
    })
    public String createPage(
            HttpServletRequest request,
            Model model
    ) {
        StockReviewCreateRequest form =
                stockreviewService.buildDefaultForm();

        populateCreateModel(
                model,
                form,
                request
        );

        model.addAttribute(
                "editMode",
                false
        );

        model.addAttribute(
                "pageTitle",
                "Tạo phiếu rà soát kho"
        );

        model.addAttribute(
                "formAction",
                resolveBasePath(request) + "/create"
        );

        model.addAttribute(
                "initialCandidates",
                List.of()
        );

        model.addAttribute(
                "initialItems",
                List.of()
        );

        return "stock-review/create";
    }

    /*
     * API tìm lô hàng theo từ khóa, loại hàng và vị trí.
     */
    @GetMapping({
            OWNER_BASE + "/candidates",
            PHARMACIST_BASE + "/candidates",
            OWNER_LEGACY_BASE + "/candidates",
            PHARMACIST_LEGACY_BASE + "/candidates"
    })
    @ResponseBody
    public List<StockReviewBatchCandidateResponse> candidates(
            @RequestParam(name = "keyword", required = false)
            String keyword,

            @RequestParam(name = "typeId", required = false)
            Integer typeId,

            @RequestParam(name = "position", required = false)
            String position
    ) {
        return stockreviewService.listReviewableBatches(
                keyword,
                typeId,
                position
        );
    }

    /*
     * Tạo phiếu mới.
     *
     * Khi action=draft, các trường thực tế được phép để trống.
     * Khi action=submit, backend yêu cầu nhập đầy đủ.
     */
    @PostMapping({
            OWNER_BASE + "/create",
            PHARMACIST_BASE + "/create",
            OWNER_LEGACY_BASE + "/create",
            PHARMACIST_LEGACY_BASE + "/create"
    })
    public String create(
            @ModelAttribute("form")
            StockReviewCreateRequest form,

            @RequestParam(name = "action", required = false)
            String action,

            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        String basePath =
                resolveBasePath(request);

        boolean asDraft =
                "draft".equalsIgnoreCase(action);

        try {
            Integer stockReviewId =
                    stockreviewService.create(
                            form,
                            currentUserContext
                                    .getCurrentAccountId(),
                            currentUserContext.isOwner(),
                            asDraft
                    );

            String message;

            if (asDraft) {
                message =
                        "Đã lưu nháp phiếu rà soát kho";
            } else if (currentUserContext.isOwner()) {
                message =
                        "Tạo phiếu rà soát kho thành công "
                                + "và đã tự động duyệt";
            } else {
                message =
                        "Đã gửi phiếu rà soát kho "
                                + "cho chủ nhà thuốc duyệt";
            }

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

            populateCreateModel(
                    model,
                    form,
                    request
            );

            model.addAttribute(
                    "editMode",
                    false
            );

            model.addAttribute(
                    "pageTitle",
                    "Tạo phiếu rà soát kho"
            );

            model.addAttribute(
                    "formAction",
                    basePath + "/create"
            );

            model.addAttribute(
                    "initialCandidates",
                    stockreviewService
                            .findCandidatesForForm(form)
            );

            model.addAttribute(
                    "initialItems",
                    form.getItems() == null
                            ? List.of()
                            : form.getItems()
            );

            return "stock-review/create";
        }
    }

    /*
     * Mở màn hình chỉnh sửa phiếu nháp.
     */
    @GetMapping({
            OWNER_BASE + "/{stockReviewId}/edit",
            PHARMACIST_BASE + "/{stockReviewId}/edit",
            OWNER_LEGACY_BASE + "/{stockReviewId}/edit",
            PHARMACIST_LEGACY_BASE + "/{stockReviewId}/edit"
    })
    public String editPage(
            @PathVariable Integer stockReviewId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        String basePath =
                resolveBasePath(request);

        try {
            StockReviewCreateRequest form =
                    stockreviewService.getDraftForm(
                            stockReviewId,
                            currentUserContext
                                    .getCurrentAccountId(),
                            currentUserContext.isOwner()
                    );

            populateCreateModel(
                    model,
                    form,
                    request
            );

            model.addAttribute(
                    "editMode",
                    true
            );

            model.addAttribute(
                    "pageTitle",
                    "Chỉnh sửa phiếu rà soát kho"
            );

            model.addAttribute(
                    "stockReviewId",
                    stockReviewId
            );

            model.addAttribute(
                    "formAction",
                    basePath
                            + "/"
                            + stockReviewId
                            + "/edit"
            );

            model.addAttribute(
                    "initialCandidates",
                    stockreviewService
                            .findCandidatesForForm(form)
            );

            model.addAttribute(
                    "initialItems",
                    form.getItems()
            );

            return "stock-review/create";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:"
                    + basePath
                    + "/"
                    + stockReviewId;
        }
    }

    /*
     * Lưu lại nội dung phiếu nháp.
     *
     * action=draft:
     * - Cho phép còn trường trống.
     * - Trạng thái vẫn là DRAFT.
     *
     * action=submit:
     * - Bắt buộc nhập đầy đủ.
     * - Owner chuyển APPROVED.
     * - Dược sĩ chuyển PENDING.
     */
    @PostMapping({
            OWNER_BASE + "/{stockReviewId}/edit",
            PHARMACIST_BASE + "/{stockReviewId}/edit",
            OWNER_LEGACY_BASE + "/{stockReviewId}/edit",
            PHARMACIST_LEGACY_BASE + "/{stockReviewId}/edit"
    })
    public String updateDraft(
            @PathVariable Integer stockReviewId,

            @ModelAttribute("form")
            StockReviewCreateRequest form,

            @RequestParam(name = "action", required = false)
            String action,

            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        String basePath =
                resolveBasePath(request);

        boolean keepDraft =
                "draft".equalsIgnoreCase(action);

        try {
            stockreviewService.updateDraft(
                    stockReviewId,
                    form,
                    currentUserContext
                            .getCurrentAccountId(),
                    currentUserContext.isOwner(),
                    keepDraft
            );

            String message;

            if (keepDraft) {
                message =
                        "Đã cập nhật phiếu rà soát kho nháp";
            } else if (currentUserContext.isOwner()) {
                message =
                        "Đã cập nhật và duyệt phiếu rà soát kho";
            } else {
                message =
                        "Đã cập nhật và gửi phiếu rà soát kho "
                                + "cho chủ nhà thuốc duyệt";
            }

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

            populateCreateModel(
                    model,
                    form,
                    request
            );

            model.addAttribute(
                    "editMode",
                    true
            );

            model.addAttribute(
                    "pageTitle",
                    "Chỉnh sửa phiếu rà soát kho"
            );

            model.addAttribute(
                    "stockReviewId",
                    stockReviewId
            );

            model.addAttribute(
                    "formAction",
                    basePath
                            + "/"
                            + stockReviewId
                            + "/edit"
            );

            model.addAttribute(
                    "initialCandidates",
                    stockreviewService
                            .findCandidatesForForm(form)
            );

            model.addAttribute(
                    "initialItems",
                    form.getItems() == null
                            ? List.of()
                            : form.getItems()
            );

            return "stock-review/create";
        }
    }

    /*
     * In biểu mẫu trống.
     *
     * Route vẫn được giữ để URL cũ không lỗi, nhưng nút truy cập
     * từ màn danh sách sẽ được xóa.
     */
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
                        currentUserContext
                                .getCurrentAccountName(),
                        type
                );

        model.addAttribute(
                "printData",
                printData
        );

        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );

        return "stock-review/print";
    }

    /*
     * In một phiếu rà soát đã được lưu.
     */
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
                stockreviewService
                        .getVoucherPrintPage(
                                stockReviewId
                        );

        model.addAttribute(
                "printData",
                printData
        );

        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );

        return "stock-review/print-voucher";
    }

    /*
     * Chi tiết phiếu.
     */
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
                stockreviewService.getDetail(
                        stockReviewId
                );

        model.addAttribute(
                "detail",
                detail
        );

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

    /*
     * Gửi trực tiếp từ màn chi tiết.
     *
     * Service phải kiểm tra phiếu đã nhập đủ dữ liệu hay chưa.
     */
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
        String basePath =
                resolveBasePath(request);

        try {
            stockreviewService.submit(
                    stockReviewId,
                    currentUserContext
                            .getCurrentAccountId(),
                    currentUserContext.isOwner()
            );

            String message =
                    currentUserContext.isOwner()
                            ? "Đã duyệt phiếu rà soát kho"
                            : "Đã gửi phiếu rà soát kho "
                            + "cho chủ nhà thuốc duyệt";

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    message
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
                    currentUserContext
                            .getCurrentAccountId()
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

        String target =
                redirectTo != null
                        && !redirectTo.isBlank()
                        ? redirectTo
                        : OWNER_BASE
                        + "/"
                        + stockReviewId;

        return "redirect:" + target;
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
                    currentUserContext
                            .getCurrentAccountId()
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

        String target =
                redirectTo != null
                        && !redirectTo.isBlank()
                        ? redirectTo
                        : OWNER_BASE
                        + "/"
                        + stockReviewId;

        return "redirect:" + target;
    }

    private void populateCreateModel(
            Model model,
            StockReviewCreateRequest form,
            HttpServletRequest request
    ) {
        model.addAttribute(
                "form",
                form
        );

        model.addAttribute(
                "typeLabels",
                stockreviewService
                        .creatableTypeLabels()
        );

        model.addAttribute(
                "productTypes",
                stockreviewService
                        .productTypeOptions()
        );

        model.addAttribute(
                "positions",
                stockreviewService
                        .positionOptions()
        );

        model.addAttribute(
                "creatorName",
                currentUserContext
                        .getCurrentAccountName()
        );

        model.addAttribute(
                "basePath",
                resolveBasePath(request)
        );
    }

    private String resolveBasePath(
            HttpServletRequest request
    ) {
        return request.getRequestURI()
                .startsWith("/owner/")
                ? OWNER_BASE
                : PHARMACIST_BASE;
    }
}