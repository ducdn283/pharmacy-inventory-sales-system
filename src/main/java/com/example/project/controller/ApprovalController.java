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
 * Owner-only unified Approve List: aggregates Return, StockAdjustment, StockCount and ShiftReport
 * items (see {@link ApprovalService}) — PENDING ones plus a short recent window of resolved ones so
 * a just-handled row stays visible instead of disappearing. Read-only view — each row's Duyệt/Từ
 * chối posts straight to that module's own endpoint (with redirectTo=/owner/approvals so the user
 * lands back here); the checkboxes instead post to this controller's own bulk-approve endpoint.
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
