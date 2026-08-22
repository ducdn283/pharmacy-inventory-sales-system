package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.ReturnPurchaseCreateRequest;
import com.example.project.dto.response.ReturnPurchaseLineResponse;
import com.example.project.dto.response.ReturnPurchaseListItemResponse;
import com.example.project.dto.response.SlipCreateOutcome;
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
 * Các màn trả hàng nhà cung cấp (danh sách / chi tiết / tạo / duyệt / từ chối) dưới {@code /owner/**}.
 *
 * <p>Owner-only: theo bảng phân quyền, Dược sĩ không có quyền gì ở phía nhà cung cấp, nên không có
 * biến thể {@code /pharmacist} và không có bước bàn giao "Chờ duyệt". Quyền truy cập ở tầng route đã
 * được SecurityConfig chặn sẵn ({@code /owner/**} → OWNER).</p>
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
        // Số tiền NCC chấp nhận hoàn do Owner gõ vào; màn tạo tự điền sẵn giá trị hàng đang chọn
        // (hoàn đủ) ngay trên trình duyệt, nên không có gì để điền sẵn từ server.
        model.addAttribute("form", new ReturnPurchaseCreateRequest());
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
            SlipCreateOutcome outcome = returnPurchaseService.createReturn(
                    form, currentUserContext.getCurrentAccountId(), asDraft);

            // Phiếu trùng: lần bấm này KHÔNG tạo gì thêm, nên không được báo "thành công" — đưa
            // người dùng tới đúng phiếu đã lưu kèm cảnh báo. Xem SlipCreateOutcome.
            if (outcome.duplicate()) {
                redirectAttributes.addFlashAttribute("warningMessage", outcome.message());
                return "redirect:" + BASE + "/" + outcome.id();
            }

            redirectAttributes.addFlashAttribute("successMessage", asDraft
                    ? "Đã lưu nháp phiếu trả hàng nhà cung cấp"
                    : "Tạo phiếu trả hàng nhà cung cấp thành công (đã duyệt, tồn kho đã cập nhật)");
            return "redirect:" + BASE + "/" + outcome.id();
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
