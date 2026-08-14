package com.example.project.controller;

import com.example.project.constant.RoleConstants;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.PurchaseInvoiceCreateRequest;
import com.example.project.dto.request.PurchaseInvoiceDetailCreateRequest;
import com.example.project.dto.response.PurchaseInvoiceDetailPageResponse;
import com.example.project.dto.response.PurchaseInvoiceListItemResponse;
import com.example.project.dto.response.PurchaseInvoicePrintPageResponse;
import com.example.project.dto.response.ProductOptionResponse;
import com.example.project.dto.response.SupplierOptionResponse;
import com.example.project.dto.response.ProcurementPlanDetailOptionResponse;
import com.example.project.constant.PurchaseInvoiceStatus;
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

    // Màn danh sách phiếu nhập: tìm kiếm/lọc/phân trang, kèm số liệu thống kê tổng quan.
    @GetMapping({
            "/owner/purchase-invoices",
            "/accountant/purchase-invoices",
    })
    public String listPurchaseInvoices(@RequestParam(name = "keyword", required = false) String keyword,
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
                        keyword,
                        fromDate,
                        toDate,
                        paymentStatus,
                        PageRequest.of(page, size)
                );

        model.addAttribute("invoicePage", invoicePage);
        model.addAttribute("purchaseInvoices", invoicePage.getContent());
        model.addAttribute("stats", purchaseinvoiceService.getStats());

        model.addAttribute("paymentStatuses", purchaseinvoiceService.listPaymentStatuses());

        model.addAttribute("keyword", keyword);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("filterPaymentStatus", paymentStatus);

        model.addAttribute("currentPage", invoicePage.getNumber());
        model.addAttribute("totalPages", invoicePage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", invoicePage.getTotalElements());

        model.addAttribute("basePath", resolveBasePath(request));
        model.addAttribute("canCreate", purchaseinvoiceService.canCreatePurchaseInvoice(currentUserContext.getCurrentRole()));

        return "purchase-invoice/list";
    }

    // ------------------------------------------------------------------ Chủ nhà thuốc: tạo trực tiếp, có thể lưu Nháp
    // Chủ nhà thuốc luôn tạo được, không phụ thuộc có Kế toán hay không. Cũng có 2 nút Lưu Nháp/Nộp
    // như form của Kế toán, nhưng nút submit của Chủ nhà thuốc tự duyệt luôn (selfApprove=true)
    // vì không cần ai duyệt hộ mình.

    // Hiển thị form tạo phiếu nhập mới cho Chủ nhà thuốc (mặc định có sẵn 1 dòng hàng trống).
    @GetMapping("/owner/purchase-invoices/create")
    public String createPage(HttpServletRequest request, Model model) {
        PurchaseInvoiceCreateRequest form = new PurchaseInvoiceCreateRequest();
        PurchaseInvoiceDetailCreateRequest firstItem = new PurchaseInvoiceDetailCreateRequest();
        form.getDetails().add(firstItem);

        model.addAttribute("form", form);
        addCreatePageData(request, model, "/owner/purchase-invoices/create", false, true, true);

        return "purchase-invoice/create";
    }

    // Xử lý submit form tạo phiếu nhập của Chủ nhà thuốc: "draft" lưu nháp, còn lại tạo & tự duyệt luôn.
    @PostMapping("/owner/purchase-invoices/create")
    public String createPurchaseInvoice(@Valid @ModelAttribute("form") PurchaseInvoiceCreateRequest form,
                                        BindingResult bindingResult,
                                        @RequestParam(name = "action", defaultValue = "submit") String action,
                                        HttpServletRequest request,
                                        Model model,
                                        RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addCreatePageData(request, model, "/owner/purchase-invoices/create", false, true, true);
            return "purchase-invoice/create";
        }

        boolean saveAsDraft = "draft".equals(action);

        try {
            Integer accountId = currentUserContext.getCurrentAccountId();
            Integer purchaseId = saveAsDraft
                    ? purchaseinvoiceService.createPurchaseInvoiceDraft(form, accountId)
                    : purchaseinvoiceService.createPurchaseInvoice(form, accountId);

            redirectAttributes.addFlashAttribute("successMessage",
                    saveAsDraft ? "Đã lưu nháp phiếu nhập" : "Tạo phiếu nhập thành công");
            return "redirect:/owner/purchase-invoices/" + purchaseId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addCreatePageData(request, model, "/owner/purchase-invoices/create", false, true, true);
            return "purchase-invoice/create";
        }
    }

    // Hiển thị form sửa phiếu nháp (Chủ nhà thuốc), điền sẵn dữ liệu bằng getDraftEditForm().
    @GetMapping("/owner/purchase-invoices/{purchaseId}/edit")
    public String ownerEditDraftPage(@PathVariable Integer purchaseId,
                                     HttpServletRequest request,
                                     Model model,
                                     RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("form", purchaseinvoiceService.getDraftEditForm(purchaseId));
            addCreatePageData(request, model,
                    "/owner/purchase-invoices/" + purchaseId + "/edit", true, true, true);
            return "purchase-invoice/create";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/owner/purchase-invoices/" + purchaseId;
        }
    }

    // Xử lý submit sửa phiếu nháp của Chủ nhà thuốc: "draft" lưu nháp lại, còn lại sửa & tự duyệt luôn.
    @PostMapping("/owner/purchase-invoices/{purchaseId}/edit")
    public String ownerEditDraft(@PathVariable Integer purchaseId,
                                 @Valid @ModelAttribute("form") PurchaseInvoiceCreateRequest form,
                                 BindingResult bindingResult,
                                 @RequestParam(name = "action", defaultValue = "submit") String action,
                                 HttpServletRequest request,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addCreatePageData(request, model,
                    "/owner/purchase-invoices/" + purchaseId + "/edit", true, true, true);
            return "purchase-invoice/create";
        }

        boolean saveAsDraft = "draft".equals(action);

        try {
            Integer accountId = currentUserContext.getCurrentAccountId();
            Integer savedId = saveAsDraft
                    ? purchaseinvoiceService.updatePurchaseInvoiceDraft(purchaseId, form, accountId)
                    : purchaseinvoiceService.finalizePurchaseInvoiceDraft(purchaseId, form, accountId);

            redirectAttributes.addFlashAttribute("successMessage",
                    saveAsDraft ? "Đã lưu lại bản nháp" : "Tạo phiếu nhập thành công");
            return "redirect:/owner/purchase-invoices/" + savedId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addCreatePageData(request, model,
                    "/owner/purchase-invoices/" + purchaseId + "/edit", true, true, true);
            return "purchase-invoice/create";
        }
    }

    // Xóa hẳn phiếu nháp (Chủ nhà thuốc) — chỉ áp dụng khi phiếu đang ở trạng thái Nháp.
    @PostMapping("/owner/purchase-invoices/{purchaseId}/delete")
    public String ownerDeleteDraft(@PathVariable Integer purchaseId, RedirectAttributes redirectAttributes) {
        try {
            purchaseinvoiceService.deletePurchaseInvoiceDraft(purchaseId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xóa phiếu nháp");
            return "redirect:/owner/purchase-invoices";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/owner/purchase-invoices/" + purchaseId;
        }
    }

    // ------------------------------------------------------------------ Kế toán: nháp -> nộp -> Chủ nhà thuốc duyệt

    // Hiển thị form tạo phiếu nhập mới cho Kế toán (mặc định có sẵn 1 dòng hàng trống).
    @GetMapping("/accountant/purchase-invoices/create")
    public String accountantCreatePage(HttpServletRequest request, Model model) {
        PurchaseInvoiceCreateRequest form = new PurchaseInvoiceCreateRequest();
        PurchaseInvoiceDetailCreateRequest firstItem = new PurchaseInvoiceDetailCreateRequest();
        form.getDetails().add(firstItem);

        model.addAttribute("form", form);
        addCreatePageData(request, model, "/accountant/purchase-invoices/create", false, true, false);

        return "purchase-invoice/create";
    }

    // Xử lý submit form tạo phiếu nhập của Kế toán: "draft" lưu nháp, còn lại nộp lên chờ Chủ nhà thuốc duyệt.
    @PostMapping("/accountant/purchase-invoices/create")
    public String accountantCreatePurchaseInvoice(@Valid @ModelAttribute("form") PurchaseInvoiceCreateRequest form,
                                                   BindingResult bindingResult,
                                                   @RequestParam(name = "action", defaultValue = "submit") String action,
                                                   HttpServletRequest request,
                                                   Model model,
                                                   RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addCreatePageData(request, model, "/accountant/purchase-invoices/create", false, true, false);
            return "purchase-invoice/create";
        }

        boolean saveAsDraft = "draft".equals(action);

        try {
            Integer accountId = currentUserContext.getCurrentAccountId();
            Integer purchaseId = saveAsDraft
                    ? purchaseinvoiceService.createPurchaseInvoiceDraft(form, accountId)
                    : purchaseinvoiceService.createPurchaseInvoiceForApproval(form, accountId);

            redirectAttributes.addFlashAttribute("successMessage",
                    saveAsDraft ? "Đã lưu nháp phiếu nhập" : "Đã nộp phiếu nhập, chờ Chủ nhà thuốc duyệt");
            return "redirect:/accountant/purchase-invoices/" + purchaseId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addCreatePageData(request, model, "/accountant/purchase-invoices/create", false, true, false);
            return "purchase-invoice/create";
        }
    }

    // Hiển thị form sửa phiếu nháp (Kế toán), điền sẵn dữ liệu bằng getDraftEditForm().
    @GetMapping("/accountant/purchase-invoices/{purchaseId}/edit")
    public String accountantEditDraftPage(@PathVariable Integer purchaseId,
                                          HttpServletRequest request,
                                          Model model,
                                          RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("form", purchaseinvoiceService.getDraftEditForm(purchaseId));
            addCreatePageData(request, model,
                    "/accountant/purchase-invoices/" + purchaseId + "/edit", true, true, false);
            return "purchase-invoice/create";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/accountant/purchase-invoices/" + purchaseId;
        }
    }

    // Xử lý submit sửa phiếu nháp của Kế toán: "draft" lưu nháp lại, còn lại nộp lên chờ Chủ nhà thuốc duyệt.
    @PostMapping("/accountant/purchase-invoices/{purchaseId}/edit")
    public String accountantEditDraft(@PathVariable Integer purchaseId,
                                      @Valid @ModelAttribute("form") PurchaseInvoiceCreateRequest form,
                                      BindingResult bindingResult,
                                      @RequestParam(name = "action", defaultValue = "submit") String action,
                                      HttpServletRequest request,
                                      Model model,
                                      RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addCreatePageData(request, model,
                    "/accountant/purchase-invoices/" + purchaseId + "/edit", true, true, false);
            return "purchase-invoice/create";
        }

        boolean saveAsDraft = "draft".equals(action);

        try {
            Integer accountId = currentUserContext.getCurrentAccountId();
            Integer savedId = saveAsDraft
                    ? purchaseinvoiceService.updatePurchaseInvoiceDraft(purchaseId, form, accountId)
                    : purchaseinvoiceService.submitPurchaseInvoiceDraft(purchaseId, form, accountId);

            redirectAttributes.addFlashAttribute("successMessage",
                    saveAsDraft ? "Đã lưu lại bản nháp" : "Đã nộp phiếu nhập, chờ Chủ nhà thuốc duyệt");
            return "redirect:/accountant/purchase-invoices/" + savedId;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            addCreatePageData(request, model,
                    "/accountant/purchase-invoices/" + purchaseId + "/edit", true, true, false);
            return "purchase-invoice/create";
        }
    }

    // Xóa hẳn phiếu nháp (Kế toán) — chỉ áp dụng khi phiếu đang ở trạng thái Nháp.
    @PostMapping("/accountant/purchase-invoices/{purchaseId}/delete")
    public String accountantDeleteDraft(@PathVariable Integer purchaseId, RedirectAttributes redirectAttributes) {
        try {
            purchaseinvoiceService.deletePurchaseInvoiceDraft(purchaseId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xóa phiếu nháp");
            return "redirect:/accountant/purchase-invoices";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/accountant/purchase-invoices/" + purchaseId;
        }
    }

    // ------------------------------------------------------------------ Chủ nhà thuốc: duyệt / từ chối

    // Chủ nhà thuốc duyệt phiếu nhập đang Chờ duyệt — hàng được nhận vào kho ngay tại bước này.
    @PostMapping("/owner/purchase-invoices/{purchaseId}/approve")
    public String approvePurchaseInvoice(@PathVariable Integer purchaseId, RedirectAttributes redirectAttributes) {
        try {
            purchaseinvoiceService.approvePurchaseInvoice(purchaseId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã duyệt phiếu nhập, hàng đã được cộng vào kho");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:/owner/purchase-invoices/" + purchaseId;
    }

    // Chủ nhà thuốc từ chối phiếu nhập đang Chờ duyệt — phiếu quay về Nháp kèm lý do từ chối.
    @PostMapping("/owner/purchase-invoices/{purchaseId}/reject")
    public String rejectPurchaseInvoice(@PathVariable Integer purchaseId,
                                        @RequestParam(name = "reason", required = false) String reason,
                                        RedirectAttributes redirectAttributes) {
        try {
            purchaseinvoiceService.rejectPurchaseInvoice(purchaseId, reason);
            redirectAttributes.addFlashAttribute("successMessage", "Đã từ chối, phiếu quay về trạng thái Nháp");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:/owner/purchase-invoices/" + purchaseId;
    }

    // Màn chi tiết phiếu nhập: nạp dữ liệu + tính các quyền hành động (duyệt/từ chối/sửa/xóa/hủy) theo trạng thái và vai trò.
    @GetMapping({
            "/owner/purchase-invoices/{purchaseId}",
            "/accountant/purchase-invoices/{purchaseId}"
    })
    public String detailPage(@PathVariable Integer purchaseId,
                             HttpServletRequest request,
                             Model model) {
        PurchaseInvoiceDetailPageResponse detail = purchaseinvoiceService.getDetail(purchaseId);
        String basePath = resolveBasePath(request);
        String role = currentUserContext.getCurrentRole();

        boolean isPending = PurchaseInvoiceStatus.PENDING_APPROVAL.equals(detail.getPaymentStatus());
        boolean isDraft = PurchaseInvoiceStatus.DRAFT.equals(detail.getPaymentStatus());
        // Chủ nhà thuốc và Kế toán đều được sửa/xóa mọi bản Nháp, không giới hạn theo người tạo.
        boolean canManageDraft = RoleConstants.OWNER.equals(role) || RoleConstants.ACCOUNTANT.equals(role);

        model.addAttribute("detail", detail);
        model.addAttribute("basePath", basePath);
        model.addAttribute("canApprove", isPending && RoleConstants.OWNER.equals(role));
        model.addAttribute("canReject", isPending && RoleConstants.OWNER.equals(role));
        model.addAttribute("canEditDraft", isDraft && canManageDraft);
        model.addAttribute("canDeleteDraft", isDraft && canManageDraft);
        model.addAttribute("canCancel",
                purchaseinvoiceService.canCancel(purchaseId, currentUserContext.getCurrentAccountId()));

        return "purchase-invoice/detail";
    }

    // Hủy phiếu nhập đã duyệt (không áp dụng cho Nháp/Chờ duyệt) kèm lý do hủy.
    @PostMapping({
            "/owner/purchase-invoices/{purchaseId}/cancel",
            "/accountant/purchase-invoices/{purchaseId}/cancel"
    })
    public String cancelPurchaseInvoice(@PathVariable Integer purchaseId,
                                        @RequestParam(name = "reason", required = false) String reason,
                                        HttpServletRequest request,
                                        RedirectAttributes redirectAttributes) {
        try {
            purchaseinvoiceService.cancelPurchaseInvoice(
                    purchaseId, reason, currentUserContext.getCurrentAccountId());
            redirectAttributes.addFlashAttribute("successMessage", "Đã hủy phiếu nhập");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        return "redirect:" + resolveBasePath(request) + "/" + purchaseId;
    }

    // Màn in phiếu nhập: nạp dữ liệu in (đầu phiếu + các dòng hàng).
    @GetMapping({
            "/owner/purchase-invoices/{purchaseId}/print",
            "/accountant/purchase-invoices/{purchaseId}/print"
    })
    public String printPage(@PathVariable Integer purchaseId,
                            HttpServletRequest request,
                            Model model) {
        PurchaseInvoicePrintPageResponse printData = purchaseinvoiceService.getPrintPage(purchaseId);

        model.addAttribute("printData", printData);
        model.addAttribute("basePath", resolveBasePath(request));

        return "purchase-invoice/print";
    }

    // API JSON: lấy các dòng chi tiết dự trù mua hàng theo nhà cung cấp, dùng để tự điền form tạo phiếu nhập.
    @GetMapping({
            "/owner/purchase-invoices/procurement-plan-details",
            "/accountant/purchase-invoices/procurement-plan-details"
    })
    @ResponseBody
    public List<ProcurementPlanDetailOptionResponse> getProcurementPlanDetails(
            @RequestParam(name = "procurementId") Integer procurementId,
            @RequestParam(name = "supplierId") Integer supplierId) {
        return purchaseinvoiceService.getProcurementPlanDetailsForSupplier(procurementId, supplierId);
    }

    // Nạp toàn bộ dữ liệu dùng chung cho form tạo/sửa phiếu nhập (nhà cung cấp, sản phẩm, giá, đơn vị, cờ hiển thị...).
    private void addCreatePageData(HttpServletRequest request, Model model,
                                   String formAction, boolean editMode, boolean dualSubmit,
                                   boolean selfApprove) {
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
        model.addAttribute("noExpirationProductIds", purchaseinvoiceService.getNoExpirationProductIds());
        model.addAttribute("basePath", resolveBasePath(request));

        model.addAttribute("formAction", formAction);
        model.addAttribute("editMode", editMode);
        model.addAttribute("dualSubmit", dualSubmit);
        model.addAttribute("selfApprove", selfApprove);
    }

    /**
     * Map (id -> tên) để hiển thị lại ô tìm sản phẩm khi render lại form do lỗi validate — ô tìm
     * kiếm không phải field bound trực tiếp nên phải tra tên từ productId. Không dùng selection
     * expression Thymeleaf (`products.?[...]`) vì predicate không thấy được biến th:each ngoài.
     */
    private Map<Integer, String> toProductNameById(List<ProductOptionResponse> products) {
        return products.stream()
                .collect(Collectors.toMap(ProductOptionResponse::getProductID, ProductOptionResponse::getName));
    }

    /** Tương tự toProductNameById(), nhưng cho ô tìm nhà cung cấp. */
    private Map<Integer, String> toSupplierNameById(List<SupplierOptionResponse> suppliers) {
        return suppliers.stream()
                .collect(Collectors.toMap(SupplierOptionResponse::getId, SupplierOptionResponse::getName));
    }

    // Suy ra tiền tố URL (Chủ nhà thuốc/Kế toán) từ request hiện tại để dựng link điều hướng đúng vai trò.
    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (uri.startsWith("/owner/purchase-invoices")) {
            return "/owner/purchase-invoices";
        }
        if (uri.startsWith("/accountant/purchase-invoices")) {
            return "/accountant/purchase-invoices";
        }
        return "/";
    }
}
