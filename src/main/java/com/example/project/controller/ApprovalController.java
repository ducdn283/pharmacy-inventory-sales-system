package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.service.ApprovalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Danh sách Duyệt hợp nhất, Owner-only: gộp phiếu Trả hàng, Phiếu nhập, Rà soát kho, Báo cáo ca và
 * Phiếu chi (xem {@link ApprovalService}) — gồm những phiếu đang PENDING cộng một cửa sổ ngắn các
 * phiếu vừa xử lý xong, để dòng vừa duyệt/từ chối không biến mất ngay lập tức. Bản thân màn này chỉ
 * đọc — nút Duyệt/Từ chối ở từng dòng POST thẳng tới endpoint gốc của đúng module đó (kèm
 * redirectTo=/owner/approvals để quay lại đây); riêng các checkbox thì POST tới endpoint duyệt hàng
 * loạt của chính controller này.
 */
@Controller
public class ApprovalController {

    private final ApprovalService approvalService;
    private final CurrentUserContext currentUserContext;

    public ApprovalController(ApprovalService approvalService, CurrentUserContext currentUserContext) {
        this.approvalService = approvalService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/owner/approvals")
    public String list(@RequestParam(name = "type", required = false) String type, Model model) {
        model.addAttribute("items", approvalService.list(type));
        model.addAttribute("stats", approvalService.getStats());
        model.addAttribute("types", approvalService.listTypes());
        model.addAttribute("filterType", type);

        return "approval/list";
    }

    @PostMapping("/owner/approvals/bulk-approve")
    public String bulkApprove(@RequestParam(name = "items", required = false) List<String> items,
                              RedirectAttributes redirectAttributes) {
        int approved = approvalService.bulkApprove(items, currentUserContext.getCurrentAccountId());
        if (approved > 0) {
            redirectAttributes.addFlashAttribute("successMessage", "Đã duyệt " + approved + " phiếu");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "Vui lòng chọn ít nhất một phiếu để duyệt");
        }

        return "redirect:/owner/approvals";
    }
}
