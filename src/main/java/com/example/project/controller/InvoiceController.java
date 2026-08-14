package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.CustomerRequest;
import com.example.project.dto.request.InvoiceCreateRequest;
import com.example.project.dto.response.CustomerOptionResponse;
import com.example.project.dto.response.InvoiceDetailPageResponse;
import com.example.project.dto.response.InvoiceLineResponse;
import com.example.project.dto.response.InvoicePrintPageResponse;
import com.example.project.dto.response.InvoiceListItemResponse;
import com.example.project.dto.response.InvoiceResponse;
import com.example.project.service.CustomerService;
import com.example.project.service.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Màn quản lý hóa đơn bán hàng (danh sách / chi tiết / in / bán hàng).
 * Chủ nhà thuốc: toàn quyền; Dược sĩ: bán hàng, xem danh sách/chi tiết/in; Kế toán: xem danh sách, chi tiết và in.
 */
@Controller
public class InvoiceController {
    private final InvoiceService invoiceService;
    private final CustomerService customerService;
    private final CurrentUserContext currentUserContext;

    public InvoiceController(InvoiceService invoiceService,
                             CustomerService customerService,
                             CurrentUserContext currentUserContext) {
        this.invoiceService = invoiceService;
        this.customerService = customerService;
        this.currentUserContext = currentUserContext;
    }

    /** Chuyển giá trị form ({@code String}) sang {@link BigDecimal} / {@link Integer} khi binding. */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
        binder.registerCustomEditor(Integer.class, new CustomNumberEditor(Integer.class, true));
    }

    /** Danh sách hóa đơn có phân trang, tìm kiếm theo mã và lọc theo ngày/hình thức thanh toán/trạng thái/người bán. */
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

    /** Lấy dòng hàng của hóa đơn — dùng cho hộp thoại xem nhanh trên danh sách. */
    @GetMapping(value = {"/owner/invoices/{invoiceId}/lines",
            "/pharmacist/invoices/{invoiceId}/lines",
            "/accountant/invoices/{invoiceId}/lines"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<InvoiceLineResponse> invoiceLines(@PathVariable Integer invoiceId) {
        return invoiceService.loadLines(invoiceId);
    }

    /** Trang chi tiết hóa đơn — Chủ nhà thuốc thấy thêm thông tin tài chính so với Dược sĩ/Kế toán. */
    @GetMapping({"/owner/invoices/{invoiceId}",
            "/pharmacist/invoices/{invoiceId}",
            "/accountant/invoices/{invoiceId}"})
    public String invoiceDetail(@PathVariable Integer invoiceId,
                                HttpServletRequest request,
                                Model model) {
        InvoiceDetailPageResponse detail = invoiceService.getDetail(
                invoiceId, currentUserContext.isOwner());
        model.addAttribute("detail", detail);
        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("returnBasePath", resolveReturnBasePath(request));
        model.addAttribute("pageTitle", "Chi tiết hóa đơn " + detail.getInvoiceCode());
        return "invoice/invoice-detail";
    }

    /**
     * Trang in hóa đơn — Chủ nhà thuốc, Dược sĩ và Kế toán.
     * {@code embed}: in phiếu thu nhỏ thay vì hóa đơn đầy đủ.
     */
    @GetMapping({"/owner/invoices/{invoiceId}/print",
            "/pharmacist/invoices/{invoiceId}/print",
            "/accountant/invoices/{invoiceId}/print"})
    public String printPage(@PathVariable Integer invoiceId,
                            @RequestParam(name = "embed", required = false) String embed,
                            HttpServletRequest request,
                            Model model) {
        boolean receiptPrint = embed != null;
        InvoicePrintPageResponse printData = invoiceService.getPrintPage(invoiceId, receiptPrint);
        model.addAttribute("printData", printData);
        model.addAttribute("basePath", resolveBasePath(request));
        return receiptPrint ? "invoice/print-receipt" : "invoice/print";
    }

    /** Form bán hàng — giữ {@code form} nếu redirect sau lỗi validate. */
    @GetMapping({"/owner/selling", "/pharmacist/selling"})
    public String sellingPage(HttpServletRequest request, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new InvoiceCreateRequest());
        }
        addSellingPageData(request, model);
        return "invoice/create-invoice";
    }

    /**
     * Xử lý submit bán hàng — tạo hóa đơn rồi quay về form bán.
     * {@code printAfterSave}: mở in ngay sau khi lưu thành công.
     */
    @PostMapping({"/owner/selling", "/pharmacist/selling"})
    public String createSale(@ModelAttribute("form") InvoiceCreateRequest form,
                             @RequestParam(name = "printAfterSave", defaultValue = "false") boolean printAfterSave,
                             HttpServletRequest request,
                             RedirectAttributes redirectAttributes,
                             Model model) {
        try {
            Integer invoiceId = invoiceService.createSaleInvoice(
                    form, currentUserContext.getCurrentAccountId(), !currentUserContext.isPharmacist());
            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo hóa đơn thành công");
            String redirectUrl = resolveSellingBasePath(request);
            if (printAfterSave) {
                redirectUrl += "?printId=" + invoiceId;
            }
            return "redirect:" + redirectUrl;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addSellingPageData(request, model);
            return "invoice/create-invoice";
        }
    }

    /** Tạo khách hàng nhanh từ form bán hàng — trả về dạng JSON. */
    @PostMapping(value = {"/owner/selling/customers", "/pharmacist/selling/customers"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> createCustomerFromSelling(@Valid @RequestBody CustomerRequest request,
                                                       BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            String message = bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .findFirst()
                    .orElse("Dữ liệu không hợp lệ");
            return ResponseEntity.badRequest().body(Map.of("message", message));
        }
        try {
            Integer id = customerService.create(request);
            return ResponseEntity.ok(new CustomerOptionResponse(
                    id,
                    request.getName(),
                    request.getPhoneNumber(),
                    request.getCustomerType()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    /** Nạp sản phẩm, khách hàng và cấu hình trang bán hàng. */
    private void addSellingPageData(HttpServletRequest request, Model model) {
        model.addAttribute("products", invoiceService.listSellableProducts());
        model.addAttribute("customers", invoiceService.listCustomers());
        model.addAttribute("sellerName", currentUserContext.getCurrentAccountName());
        model.addAttribute("debtAllowed", !currentUserContext.isPharmacist());
        String sellingBasePath = resolveSellingBasePath(request);
        model.addAttribute("basePath", sellingBasePath);
        model.addAttribute("customerCreateUrl", sellingBasePath + "/customers");
        model.addAttribute("invoicesPath", invoiceListBasePath(request));
        model.addAttribute("invoicePrintBasePath", invoiceListBasePath(request));
    }

    /** Đường dẫn gốc form bán hàng theo vai trò: Chủ nhà thuốc hoặc Dược sĩ. */
    private String resolveSellingBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/pharmacist/selling")
                ? "/pharmacist/selling" : "/owner/selling";
    }

    /** Đường dẫn gốc danh sách hóa đơn theo vai trò: Chủ nhà thuốc hoặc Dược sĩ. */
    private String invoiceListBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/pharmacist")
                ? "/pharmacist/invoices" : "/owner/invoices";
    }

    /** Đường dẫn gốc danh sách/chi tiết/in hóa đơn theo vai trò: Chủ nhà thuốc, Dược sĩ hoặc Kế toán. */
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

    /** Đường dẫn gốc tạo trả hàng từ chi tiết hóa đơn — null nếu Kế toán (không có quyền trả). */
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

    /** Ghép URL redirect về danh sách, giữ lại bộ lọc và phân trang hiện tại. */
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
