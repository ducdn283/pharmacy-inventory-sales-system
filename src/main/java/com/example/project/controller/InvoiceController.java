package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.InvoiceCreateRequest;
import com.example.project.dto.response.InvoiceDetailPageResponse;
import com.example.project.dto.response.InvoiceLineResponse;
import com.example.project.dto.response.InvoiceListItemResponse;
import com.example.project.dto.response.InvoiceResponse;
import com.example.project.service.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.List;

@Controller
public class InvoiceController {
    private final InvoiceService invoiceService;
    private final CurrentUserContext currentUserContext;

    public InvoiceController(InvoiceService invoiceService, CurrentUserContext currentUserContext) {
        this.invoiceService = invoiceService;
        this.currentUserContext = currentUserContext;
    }

    //chuyển dữ liệu từ form (String) sang kiểu dữ liệu Java.
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
        binder.registerCustomEditor(Integer.class, new CustomNumberEditor(Integer.class, true));
    }

    @GetMapping({"/owner/invoices", "/pharmacist/invoices", "/accountant/invoices"})
    public String invoiceList(@RequestParam(name = "search", required = false) String search,
                              @RequestParam(name = "fromDate", required = false) String fromDate,
                              @RequestParam(name = "toDate", required = false) String toDate,
                              @RequestParam(name = "paymentType", required = false) String paymentType,
                              @RequestParam(name = "status", required = false) String status,
                              @RequestParam(name = "sellerId", required = false) Integer sellerId,
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

        Page<InvoiceListItemResponse> invoicePage = invoiceService.list(
                search, fromDate, toDate, paymentType, status, sellerId, PageRequest.of(page, size));
        String basePath = resolveBasePath(request);

        model.addAttribute("invoices", invoicePage.getContent());
        model.addAttribute("totalInvoices", invoiceService.countAll());
        model.addAttribute("todayInvoices", invoiceService.countToday());
        model.addAttribute("todayRevenue", invoiceService.sumTodayRevenue());
        model.addAttribute("debtInvoices", invoiceService.countDebt());
        model.addAttribute("debtTotal", invoiceService.sumDebtTotal());
        model.addAttribute("returnedInvoices", invoiceService.countReturned());
        model.addAttribute("statuses", invoiceService.listStatuses());
        model.addAttribute("sellers", invoiceService.listSellers());
        model.addAttribute("paymentTypeLabels", invoiceService.paymentTypeLabels());
        model.addAttribute("search", search);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterPaymentType", paymentType);
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterSellerId", sellerId);
        model.addAttribute("currentPage", invoicePage.getNumber());
        model.addAttribute("totalPages", invoicePage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", invoicePage.getTotalElements());
        model.addAttribute("pageTitle", "Danh sách hóa đơn");
        model.addAttribute("basePath", basePath);
        return "invoice/invoice-list";
    }

    @GetMapping(value = {"/owner/invoices/{invoiceId}/lines",
            "/pharmacist/invoices/{invoiceId}/lines",
            "/accountant/invoices/{invoiceId}/lines"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<InvoiceLineResponse> invoiceLines(@PathVariable Integer invoiceId) {
        return invoiceService.loadLines(invoiceId);
    }

    @GetMapping({"/owner/invoices/{invoiceId}",
            "/pharmacist/invoices/{invoiceId}",
            "/accountant/invoices/{invoiceId}"})
    public String invoiceDetail(@PathVariable Integer invoiceId,
                                HttpServletRequest request,
                                Model model) {
        InvoiceDetailPageResponse detail = invoiceService.getDetail(invoiceId);
        model.addAttribute("detail", detail);
        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("returnBasePath", resolveReturnBasePath(request));
        model.addAttribute("pageTitle", "Chi tiết hóa đơn " + detail.getInvoiceCode());
        return "invoice/invoice-detail";
    }

    @PostMapping({"/owner/invoices/{invoiceId}/sign", "/accountant/invoices/{invoiceId}/sign"})
    public String signInvoice(@PathVariable Integer invoiceId,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            invoiceService.sign(invoiceId);
            redirectAttributes.addFlashAttribute("success", "Đã ký hóa đơn thành công");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + invoiceId;
    }

    @PostMapping({"/owner/invoices/sign-bulk", "/accountant/invoices/sign-bulk"})
    public String signInvoicesBulk(@RequestParam(name = "invoiceIds", required = false) List<Integer> invoiceIds,
                                   @RequestParam(name = "search", required = false) String search,
                                   @RequestParam(name = "fromDate", required = false) String fromDate,
                                   @RequestParam(name = "toDate", required = false) String toDate,
                                   @RequestParam(name = "paymentType", required = false) String paymentType,
                                   @RequestParam(name = "status", required = false) String status,
                                   @RequestParam(name = "sellerId", required = false) Integer sellerId,
                                   @RequestParam(name = "page", defaultValue = "0") int page,
                                   @RequestParam(name = "size", defaultValue = "5") int size,
                                   HttpServletRequest request,
                                   RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            int signed = invoiceService.signMany(invoiceIds);
            redirectAttributes.addFlashAttribute("success", "Đã ký " + signed + " hóa đơn thành công");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + listRedirectUrl(basePath, search, fromDate, toDate, paymentType, status, sellerId, page, size);
    }

    @GetMapping({"/owner/selling", "/pharmacist/selling"})
    public String sellingPage(HttpServletRequest request, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new InvoiceCreateRequest());
        }
        addSellingPageData(request, model);
        return "invoice/create-invoice";
    }

    @PostMapping({"/owner/selling", "/pharmacist/selling"})
    public String createSale(@ModelAttribute("form") InvoiceCreateRequest form,
                             HttpServletRequest request,
                             RedirectAttributes redirectAttributes,
                             Model model) {
        try {
            invoiceService.createSaleInvoice(
                    form, currentUserContext.getCurrentAccountId(), !currentUserContext.isPharmacist());
            redirectAttributes.addFlashAttribute("success", "Tạo hóa đơn bán hàng thành công");
            return "redirect:" + invoiceListBasePath(request);
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addSellingPageData(request, model);
            return "invoice/create-invoice";
        }
    }

    private void addSellingPageData(HttpServletRequest request, Model model) {
        model.addAttribute("products", invoiceService.listSellableProducts());
        model.addAttribute("customers", invoiceService.listCustomers());
        model.addAttribute("sellerName", currentUserContext.getCurrentAccountName());
        model.addAttribute("debtAllowed", !currentUserContext.isPharmacist());
        model.addAttribute("basePath", resolveSellingBasePath(request));
        model.addAttribute("invoicesPath", invoiceListBasePath(request));
    }

    private String resolveSellingBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/pharmacist/selling")
                ? "/pharmacist/selling" : "/owner/selling";
    }

    private String invoiceListBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/pharmacist")
                ? "/pharmacist/invoices" : "/owner/invoices";
    }

    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/pharmacist/invoices")) {
            return "/pharmacist/invoices";
        }
        if (uri.startsWith("/accountant/invoices")) {
            return "/accountant/invoices";
        }

        return "/owner/invoices";
    }

    private String resolveReturnBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/pharmacist/invoices")) {
            return "/pharmacist/returns";
        }
        if (uri.startsWith("/owner/invoices")) {
            return "/owner/returns";
        }

        return null;
    }

    private String listRedirectUrl(String basePath,
                                   String search,
                                   String fromDate,
                                   String toDate,
                                   String paymentType,
                                   String status,
                                   Integer sellerId,
                                   int page,
                                   int size) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(basePath);
        if (search != null && !search.isBlank()) {
            builder.queryParam("search", search.trim());
        }
        if (fromDate != null && !fromDate.isBlank()) {
            builder.queryParam("fromDate", fromDate);
        }
        if (toDate != null && !toDate.isBlank()) {
            builder.queryParam("toDate", toDate);
        }
        if (paymentType != null && !paymentType.isBlank()) {
            builder.queryParam("paymentType", paymentType);
        }
        if (status != null && !status.isBlank()) {
            builder.queryParam("status", status);
        }
        if (sellerId != null) {
            builder.queryParam("sellerId", sellerId);
        }
        if (page > 0) {
            builder.queryParam("page", page);
        }
        if (size != 5) {
            builder.queryParam("size", size);
        }
        return builder.build().toUriString();
    }
}
