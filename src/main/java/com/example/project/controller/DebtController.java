package com.example.project.controller;

import com.example.project.constant.ExpenseType;
import com.example.project.dto.response.DebtListItemResponse;
import com.example.project.dto.response.DebtSummaryResponse;
import com.example.project.dto.response.IncomeTypeOptionResponse;
import com.example.project.dto.response.PayableDetailResponse;
import com.example.project.dto.response.ReceivableDetailResponse;
import com.example.project.service.DebtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

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

    public DebtController(DebtService debtService) {
        this.debtService = debtService;
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
