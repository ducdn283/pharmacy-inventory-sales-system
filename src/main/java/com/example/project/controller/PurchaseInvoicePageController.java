package com.example.project.controller;

import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.PurchaseInvoiceCreateRequest;
import com.example.project.dto.request.PurchaseInvoiceDetailCreateRequest;
import com.example.project.dto.response.PurchaseInvoiceDetailPageResponse;
import com.example.project.dto.response.PurchaseInvoiceListItemResponse;
import com.example.project.dto.response.PurchaseInvoicePrintPageResponse;
import com.example.project.dto.response.ProductOptionResponse;
import com.example.project.dto.response.SupplierOptionResponse;
import com.example.project.dto.response.ProcurementPlanDetailOptionResponse;
import com.example.project.service.PurchaseinvoiceService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class PurchaseInvoicePageController {

    private final PurchaseinvoiceService purchaseinvoiceService;
    private final CurrentUserContext currentUserContext;

    public PurchaseInvoicePageController(PurchaseinvoiceService purchaseinvoiceService,
                                         CurrentUserContext currentUserContext) {
        this.purchaseinvoiceService = purchaseinvoiceService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping({
            "/owner/purchase-invoices",
            "/accountant/purchase-invoices",
            "/pharmacist/purchase-invoices"
    })
    public String listPurchaseInvoices(@RequestParam(name = "codeQuery", required = false) String codeQuery,
                                       @RequestParam(name = "supplierQuery", required = false) String supplierQuery,
                                       @RequestParam(name = "productQuery", required = false) String productQuery,
                                       @RequestParam(name = "fromDate", required = false) String fromDate,
                                       @RequestParam(name = "toDate", required = false) String toDate,
                                       @RequestParam(name = "paymentStatus", required = false) String paymentStatus,
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

        Page<PurchaseInvoiceListItemResponse> invoicePage =
                purchaseinvoiceService.searchPurchaseInvoices(
                        codeQuery,
                        supplierQuery,
                        productQuery,
                        fromDate,
                        toDate,
                        paymentStatus,
                        PageRequest.of(page, size)
                );

        model.addAttribute("invoicePage", invoicePage);
        model.addAttribute("purchaseInvoices", invoicePage.getContent());
        model.addAttribute("stats", purchaseinvoiceService.getStats());

        model.addAttribute("paymentStatuses", purchaseinvoiceService.listPaymentStatuses());

        model.addAttribute("codeQuery", codeQuery);
        model.addAttribute("supplierQuery", supplierQuery);
        model.addAttribute("productQuery", productQuery);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterPaymentStatus", paymentStatus);

        model.addAttribute("currentPage", invoicePage.getNumber());
        model.addAttribute("totalPages", invoicePage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", invoicePage.getTotalElements());

        model.addAttribute("basePath", resolveBasePath(request));

        return "purchase-invoice/list";
    }

    @GetMapping({
            "/owner/purchase-invoices/create",
            "/pharmacist/purchase-invoices/create"
    })
    public String createPage(HttpServletRequest request, Model model) {
        PurchaseInvoiceCreateRequest form = new PurchaseInvoiceCreateRequest();

        PurchaseInvoiceDetailCreateRequest firstItem = new PurchaseInvoiceDetailCreateRequest();
        form.getDetails().add(firstItem);

        model.addAttribute("form", form);
        addCreatePageData(request, model);

        return "purchase-invoice/create";
    }

    @PostMapping({
            "/owner/purchase-invoices/create",
            "/pharmacist/purchase-invoices/create"
    })
    public String createPurchaseInvoice(@Valid @ModelAttribute("form") PurchaseInvoiceCreateRequest form,
                                        BindingResult bindingResult,
                                        HttpServletRequest request,
                                        Model model,
                                        RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addCreatePageData(request, model);
            return "purchase-invoice/create";
        }

        try {
            Integer purchaseId = purchaseinvoiceService.createPurchaseInvoice(
                    form,
                    currentUserContext.getCurrentAccountId()
            );

            redirectAttributes.addFlashAttribute("successMessage", "Tạo phiếu nhập thành công");
            return "redirect:" + resolveBasePath(request) + "/" + purchaseId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addCreatePageData(request, model);
            return "purchase-invoice/create";
        }
    }

    @GetMapping({
            "/owner/purchase-invoices/{purchaseId}",
            "/accountant/purchase-invoices/{purchaseId}",
            "/pharmacist/purchase-invoices/{purchaseId}"
    })
    public String detailPage(@PathVariable Integer purchaseId,
                             HttpServletRequest request,
                             Model model) {
        PurchaseInvoiceDetailPageResponse detail = purchaseinvoiceService.getDetail(purchaseId);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", resolveBasePath(request));

        return "purchase-invoice/detail";
    }

    @PostMapping({
            "/owner/purchase-invoices/{purchaseId}/cancel",
            "/pharmacist/purchase-invoices/{purchaseId}/cancel"
    })
    public String cancelPurchaseInvoice(@PathVariable Integer purchaseId,
                                        @RequestParam(name = "reason", required = false) String reason,
                                        HttpServletRequest request,
                                        RedirectAttributes redirectAttributes) {
        try {
            purchaseinvoiceService.cancelPurchaseInvoice(purchaseId, reason);
            redirectAttributes.addFlashAttribute("successMessage", "Đã hủy phiếu nhập");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + resolveBasePath(request) + "/" + purchaseId;
    }

    @GetMapping({
            "/owner/purchase-invoices/{purchaseId}/print",
            "/accountant/purchase-invoices/{purchaseId}/print",
            "/pharmacist/purchase-invoices/{purchaseId}/print"
    })
    public String printPage(@PathVariable Integer purchaseId,
                            HttpServletRequest request,
                            Model model) {
        PurchaseInvoicePrintPageResponse printData = purchaseinvoiceService.getPrintPage(purchaseId);

        model.addAttribute("printData", printData);
        model.addAttribute("basePath", resolveBasePath(request));

        return "purchase-invoice/print";
    }

    @GetMapping({
            "/owner/purchase-invoices/procurement-plan-details",
            "/pharmacist/purchase-invoices/procurement-plan-details"
    })
    @ResponseBody
    public List<ProcurementPlanDetailOptionResponse> getProcurementPlanDetails(
            @RequestParam(name = "procurementId") Integer procurementId,
            @RequestParam(name = "supplierId") Integer supplierId) {
        return purchaseinvoiceService.getProcurementPlanDetailsForSupplier(procurementId, supplierId);
    }

    private void addCreatePageData(HttpServletRequest request, Model model) {
        List<SupplierOptionResponse> suppliers = purchaseinvoiceService.listSupplierOptions();
        model.addAttribute("suppliers", suppliers);
        model.addAttribute("supplierNameById", toSupplierNameById(suppliers));

        model.addAttribute("procurementPlans", purchaseinvoiceService.listProcurementPlans());

        List<ProductOptionResponse> products = purchaseinvoiceService.listProducts();
        model.addAttribute("products", products);
        model.addAttribute("productNameById", toProductNameById(products));

        model.addAttribute("costPriceBySupplierAndProduct", purchaseinvoiceService.buildCostPriceBySupplierAndProduct());
        model.addAttribute("vatRateByProduct", purchaseinvoiceService.getVatRateByProduct());
        model.addAttribute("importUnitByProduct", purchaseinvoiceService.getImportUnitNameByProduct());
        model.addAttribute("sellPriceByProduct", purchaseinvoiceService.getSellPriceByProduct());
        model.addAttribute("basePath", resolveBasePath(request));
    }

    /**
     * Plain (id -> name) lookup for re-hydrating the create form's product-search field on a
     * validation-error re-render — the search input isn't itself a bound field, so its display text
     * has to be looked up from the bound productId. A Thymeleaf selection expression
     * (`products.?[productID == detail.productId]`) crashes here because its predicate can't see
     * the outer `th:each` variable, so a plain map lookup is used instead.
     */
    private Map<Integer, String> toProductNameById(List<ProductOptionResponse> products) {
        return products.stream()
                .collect(Collectors.toMap(ProductOptionResponse::getProductID, ProductOptionResponse::getName));
    }

    /** Same re-hydration need as {@link #toProductNameById}, but for the supplier search field. */
    private Map<Integer, String> toSupplierNameById(List<SupplierOptionResponse> suppliers) {
        return suppliers.stream()
                .collect(Collectors.toMap(SupplierOptionResponse::getId, SupplierOptionResponse::getName));
    }

    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/owner/purchase-invoices")) {
            return "/owner/purchase-invoices";
        }
        if (uri.startsWith("/pharmacist/purchase-invoices")) {
            return "/pharmacist/purchase-invoices";
        }

        return "/accountant/purchase-invoices";
    }
}