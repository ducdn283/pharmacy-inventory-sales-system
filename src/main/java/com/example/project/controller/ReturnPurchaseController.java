package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.ReturnPurchaseCreateRequest;
import com.example.project.dto.response.ReturnPurchaseLineResponse;
import com.example.project.dto.response.ReturnPurchaseListItemResponse;
import com.example.project.service.ReturnPurchaseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Supplier-return screens (list / detail / create / approve / reject) under {@code /owner/**}.
 *
 * <p>Owner-only: per the permission matrix the Pharmacist has no rights on the supplier side, so
 * there is no {@code /pharmacist} variant and no "Chờ duyệt" hand-off. Route-level access is already
 * enforced by SecurityConfig ({@code /owner/**} → OWNER).</p>
 */
@Controller
@RequestMapping("/owner/return-purchases")
public class ReturnPurchaseController {

    private static final String BASE = "/owner/return-purchases";

    private final ReturnPurchaseService returnPurchaseService;
    private final CurrentUserContext currentUserContext;

    public ReturnPurchaseController(ReturnPurchaseService returnPurchaseService,
                                    CurrentUserContext currentUserContext) {
        this.returnPurchaseService = returnPurchaseService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "fromDate", required = false) String fromDate,
                       @RequestParam(name = "toDate", required = false) String toDate,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size", defaultValue = "5") int size,
                       Model model) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 5;
        }

        Page<ReturnPurchaseListItemResponse> returnPage =
                returnPurchaseService.search(keyword, fromDate, toDate, status, PageRequest.of(page, size));

        model.addAttribute("returnPage", returnPage);
        model.addAttribute("returns", returnPage.getContent());
        model.addAttribute("stats", returnPurchaseService.getStats());

        model.addAttribute("statuses", returnPurchaseService.listStatuses());

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterStatus", status);

        model.addAttribute("currentPage", returnPage.getNumber());
        model.addAttribute("totalPages", returnPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", returnPage.getTotalElements());

        // Tắt bù trừ thì 2 cột cấn trừ luôn bằng 0 ⇒ ẩn hẳn cột thay vì để một cột toàn số 0.
        model.addAttribute("autoOffsetDebt", returnPurchaseService.isAutoOffsetDebt());
        model.addAttribute("basePath", BASE);
        return "return-purchase/list";
    }

    @GetMapping("/create")
    public String createPage(Model model) {
        ReturnPurchaseCreateRequest form = new ReturnPurchaseCreateRequest();
        // Điền sẵn tỷ lệ NCC chấp nhận hoàn theo thiết lập tài chính; Owner chỉnh được cho từng phiếu.
        form.setRefundRate(returnPurchaseService.getDefaultRefundRate());
        model.addAttribute("form", form);
        addCreateFormOptions(model);
        return "return-purchase/create";
    }

    /** JSON: the still-returnable lines of a chosen purchase, for the create screen. */
    @GetMapping(value = "/purchases/{purchaseId}/lines", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<ReturnPurchaseLineResponse> purchaseLines(@PathVariable Integer purchaseId) {
        return returnPurchaseService.loadPurchaseLines(purchaseId);
    }

    @PostMapping("/create")
    public String create(@ModelAttribute("form") ReturnPurchaseCreateRequest form,
                         @RequestParam(name = "action", required = false) String action,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        boolean asDraft = "draft".equals(action);
        try {
            Integer returnId = returnPurchaseService.createReturn(
                    form, currentUserContext.getCurrentAccountId(), asDraft);
            redirectAttributes.addFlashAttribute("successMessage", asDraft
                    ? "Đã lưu nháp phiếu trả hàng nhà cung cấp"
                    : "Tạo phiếu trả hàng nhà cung cấp thành công (đã duyệt, tồn kho đã cập nhật)");
            return "redirect:" + BASE + "/" + returnId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("form", form);
            addCreateFormOptions(model);
            return "return-purchase/create";
        }
    }

    /** Dữ liệu dùng chung cho màn tạo (lần đầu và khi render lại sau lỗi validate). */
    private void addCreateFormOptions(Model model) {
        model.addAttribute("returnablePurchases", returnPurchaseService.listReturnablePurchases(null));
        model.addAttribute("creatorName", currentUserContext.getCurrentAccountName());
        // Chỉ Nhóm 3/4 mới có thuế GTGT đầu vào đã khấu trừ để đảo lại khi trả hàng.
        model.addAttribute("deductionGroup", returnPurchaseService.isDeductionGroup());
        // Nhóm 1 cũng không có thuế để đảo, nhưng vì miễn thuế — chú thích phải đúng nhóm.
        model.addAttribute("taxExempt", returnPurchaseService.isTaxExempt());
        model.addAttribute("defaultRefundRate", returnPurchaseService.getDefaultRefundRate());
        model.addAttribute("autoOffsetDebt", returnPurchaseService.isAutoOffsetDebt());
        model.addAttribute("basePath", BASE);
    }

    @GetMapping("/{returnId}")
    public String detail(@PathVariable Integer returnId, Model model,
                         RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("detail", returnPurchaseService.getDetail(returnId));
        } catch (IllegalArgumentException exception) {
            // Gõ tay id không tồn tại: về danh sách kèm thông báo thay vì rơi vào trang lỗi chung.
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + BASE;
        }
        model.addAttribute("basePath", BASE);
        return "return-purchase/detail";
    }

    @PostMapping("/{returnId}/approve")
    public String approve(@PathVariable Integer returnId, RedirectAttributes redirectAttributes) {
        try {
            returnPurchaseService.approve(returnId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã duyệt phiếu trả hàng nhà cung cấp (tồn kho đã cập nhật)");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + BASE + "/" + returnId;
    }

    @PostMapping("/{returnId}/reject")
    public String reject(@PathVariable Integer returnId, RedirectAttributes redirectAttributes) {
        try {
            returnPurchaseService.reject(returnId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối phiếu trả hàng nhà cung cấp");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + BASE + "/" + returnId;
    }
}
