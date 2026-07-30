package com.example.project.controller;

import com.example.project.constant.ExpenseType;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.DebtOffsetRequest;
import com.example.project.dto.response.DebtListItemResponse;
import com.example.project.dto.response.DebtSummaryResponse;
import com.example.project.dto.response.IncomeTypeOptionResponse;
import com.example.project.dto.response.PayableDetailResponse;
import com.example.project.dto.response.ReceivableDetailResponse;
import com.example.project.service.DebtOffsetService;
import com.example.project.service.DebtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * Debt ("Công nợ") list for Owner and Accountant. Receivable (cho nợ) comes from customer debt
 * invoices and approved supplier-return offset debt; payable (nợ) from customer return refunds and
 * purchase-invoice debt.
 */
@Controller
public class DebtController {

    private static final String OWNER_BASE = "/owner/debts";
    private static final String ACCOUNTANT_BASE = "/accountant/debts";

    private final DebtService debtService;
    private final DebtOffsetService debtOffsetService;
    private final CurrentUserContext currentUserContext;

    public DebtController(DebtService debtService,
                          DebtOffsetService debtOffsetService,
                          CurrentUserContext currentUserContext) {
        this.debtService = debtService;
        this.debtOffsetService = debtOffsetService;
        this.currentUserContext = currentUserContext;
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
        binder.registerCustomEditor(Integer.class, new CustomNumberEditor(Integer.class, true));
    }

    @GetMapping({OWNER_BASE, ACCOUNTANT_BASE})
    public String list(@RequestParam(name = "search", required = false) String search,
                       @RequestParam(name = "partyType", required = false) String partyType,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size", defaultValue = "10") int size,
                       HttpServletRequest request,
                       Model model) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 10;
        }

        Page<DebtListItemResponse> debtPage = debtService.list(search, partyType, PageRequest.of(page, size));
        DebtSummaryResponse summary = debtService.summarize();
        String basePath = resolveBasePath(request);

        model.addAttribute("debts", debtPage.getContent());
        model.addAttribute("summary", summary);
        model.addAttribute("partyTypeLabels", debtService.partyTypeLabels());
        model.addAttribute("search", search);
        model.addAttribute("filterPartyType", partyType);
        model.addAttribute("currentPage", debtPage.getNumber());
        model.addAttribute("totalPages", debtPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", debtPage.getTotalElements());
        model.addAttribute("basePath", basePath);
        model.addAttribute("pageTitle", "Danh sách công nợ");
        return "debt/debt-list";
    }

    @GetMapping({OWNER_BASE + "/receivable/{partyType}/{entityId}",
            ACCOUNTANT_BASE + "/receivable/{partyType}/{entityId}"})
    public String receivableDetail(@PathVariable String partyType,
                                   @PathVariable Integer entityId,
                                   HttpServletRequest request,
                                   Model model) {
        ReceivableDetailResponse detail = debtService.getReceivableDetail(partyType, entityId);
        String basePath = resolveBasePath(request);
        String incomeBasePath = resolveIncomeBasePath(request);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", basePath);
        model.addAttribute("incomeBasePath", incomeBasePath);
        model.addAttribute("customerIncomeType", IncomeTypeOptionResponse.CUSTOMER);
        model.addAttribute("supplierIncomeType", IncomeTypeOptionResponse.SUPPLIER);
        model.addAttribute("pageTitle", "Chi tiết cho nợ — " + detail.getName());
        return "debt/receivable-detail";
    }

    @GetMapping({OWNER_BASE + "/payable/{partyType}/{entityId}",
            ACCOUNTANT_BASE + "/payable/{partyType}/{entityId}"})
    public String payableDetail(@PathVariable String partyType,
                                @PathVariable Integer entityId,
                                HttpServletRequest request,
                                Model model) {
        PayableDetailResponse detail = debtService.getPayableDetail(partyType, entityId);
        String basePath = resolveBasePath(request);
        String expenseBasePath = resolveExpenseBasePath(request);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", basePath);
        model.addAttribute("expenseBasePath", expenseBasePath);
        model.addAttribute("returnRefundExpenseType", ExpenseType.RETURN_REFUND_PAYOUT);
        // Tiền hàng tách khỏi "Chi phí vận hành" 2026-07-30: nút "Chi trả" phải chọn sẵn loại gắn
        // được phiếu nhập, nếu không màn tạo phiếu chi sẽ ẩn luôn ô chọn phiếu nhập.
        model.addAttribute("goodsPaymentExpenseType", ExpenseType.GOODS_PAYMENT);
        model.addAttribute("pageTitle", "Chi tiết nợ — " + detail.getName());
        return "debt/payable-detail";
    }

    @GetMapping({OWNER_BASE + "/offset/{partyType}/{entityId}",
            ACCOUNTANT_BASE + "/offset/{partyType}/{entityId}"})
    public String offsetPage(@PathVariable String partyType,
                             @PathVariable Integer entityId,
                             HttpServletRequest request,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("page", debtOffsetService.getOffsetPage(partyType, entityId));
            model.addAttribute("basePath", resolveBasePath(request));
            model.addAttribute("pageTitle", "Bù trừ công nợ");
            DebtOffsetRequest form = new DebtOffsetRequest();
            form.setPartyType(partyType);
            form.setEntityId(entityId);
            model.addAttribute("form", form);
            return "debt/debt-offset";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + resolveBasePath(request);
        }
    }

    @PostMapping({OWNER_BASE + "/offset", ACCOUNTANT_BASE + "/offset"})
    public String applyOffset(@ModelAttribute("form") DebtOffsetRequest form,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            debtOffsetService.applyOffset(form, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Bù trừ công nợ thành công");
            return "redirect:" + basePath;
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            redirectAttributes.addFlashAttribute("form", form);
            return "redirect:" + basePath + "/offset/" + form.getPartyType() + "/" + form.getEntityId();
        }
    }

    private String resolveBasePath(HttpServletRequest request) {
        if (request.getRequestURI().startsWith(ACCOUNTANT_BASE)) {
            return ACCOUNTANT_BASE;
        }
        return OWNER_BASE;
    }

    private String resolveIncomeBasePath(HttpServletRequest request) {
        if (request.getRequestURI().startsWith(ACCOUNTANT_BASE)) {
            return "/accountant/incomes";
        }
        return "/owner/incomes";
    }

    private String resolveExpenseBasePath(HttpServletRequest request) {
        if (request.getRequestURI().startsWith(ACCOUNTANT_BASE)) {
            return "/accountant/expenses";
        }
        return "/owner/expenses";
    }
}
