package com.example.project.controller;

import com.example.project.constant.TaxRevenueGroup;
import com.example.project.context.CurrentUserContext;
import com.example.project.dto.request.TaxPeriodCloseRequest;
import com.example.project.dto.request.TaxPeriodUpdateRequest;
import com.example.project.service.TaxRevenueNotificationService;
import com.example.project.service.TaxperiodsnapshotService;
import jakarta.servlet.http.HttpServletRequest;
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

import java.beans.PropertyEditorSupport;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Màn hình Kỳ thuế — danh sách, xem trước, chi tiết, chốt kỳ và điều chỉnh kỳ mới nhất.
 *
 * <p>Owner và Accountant đều xem được; không có route cho Pharmacist.</p>
 */
@Controller
public class TaxPeriodPageController {

    private static final String OWNER_BASE = "/owner/tax-periods";
    private static final String ACCOUNTANT_BASE = "/accountant/tax-periods";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TaxperiodsnapshotService taxperiodsnapshotService;
    private final TaxRevenueNotificationService taxRevenueNotificationService;
    private final CurrentUserContext currentUserContext;

    public TaxPeriodPageController(TaxperiodsnapshotService taxperiodsnapshotService,
                                   TaxRevenueNotificationService taxRevenueNotificationService,
                                   CurrentUserContext currentUserContext) {
        this.taxperiodsnapshotService = taxperiodsnapshotService;
        this.currentUserContext = currentUserContext;
        this.taxRevenueNotificationService = taxRevenueNotificationService;
    }

    // Hiển thị danh sách kỳ thuế đã chốt + thông tin nhóm doanh thu hiện tại + kỳ kế tiếp cần chốt.
    @GetMapping({OWNER_BASE, ACCOUNTANT_BASE})
    public String list(HttpServletRequest request, Model model) {
        // Áp dụng chuyển nhóm 1→2 đang chờ (và cảnh báo nếu sắp vượt ngưỡng) mỗi lần mở màn hình —
        // không có scheduler nền, nên đây là lúc việc chuyển nhóm thực sự được ghi nhận.
        taxRevenueNotificationService.checkGroupTransitionAndWarn(taxperiodsnapshotService.currentQuarter(), null);

        TaxperiodsnapshotService.TaxPeriod next = taxperiodsnapshotService.nextPeriodToClose();
        Integer currentGroup = taxperiodsnapshotService.currentRevenueGroup();

        model.addAttribute("periods", taxperiodsnapshotService.listPeriods());

        model.addAttribute("currentGroup", currentGroup);
        model.addAttribute("currentGroupDisplay", TaxRevenueGroup.label(currentGroup));
        model.addAttribute("deductionGroup", TaxRevenueGroup.isDeductionGroup(currentGroup));
        model.addAttribute("taxExempt", TaxRevenueGroup.isTaxExempt(currentGroup));

        model.addAttribute("nextPeriodLabel", next.label());
        model.addAttribute("nextPeriodRange",
                DATE.format(next.startDate()) + " – " + DATE.format(next.endDate()));

        model.addAttribute("basePath", resolveBasePath(request));

        return "tax-period/list";
    }

    /**
     * "Xem trước" — tính số liệu kỳ trực tiếp từ chứng từ hiện có, không lưu gì cả.
     *
     * <p>Map phía trên {@code /{periodId}} có chủ đích — Spring ưu tiên đoạn URL cố định hơn biến
     * template, nên {@code /preview} không rơi vào handler chi tiết theo id.</p>
     */
    @GetMapping({OWNER_BASE + "/preview", ACCOUNTANT_BASE + "/preview"})
    public String preview(@RequestParam(name = "year", required = false) Integer year,
                          @RequestParam(name = "quarter", required = false) Integer quarter,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes,
                          Model model) {
        String basePath = resolveBasePath(request);

        TaxperiodsnapshotService.TaxPeriod period;
        try {
            period = taxperiodsnapshotService.resolveQuarter(year, quarter);
            // Áp dụng/cảnh báo chuyển nhóm TRƯỚC khi tính, để số liệu bên dưới phản ánh đúng nhóm mới.
            taxRevenueNotificationService.checkGroupTransitionAndWarn(period, null);
            model.addAttribute("computation", taxperiodsnapshotService.computePeriod(period));
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath + "/preview";
        }

        model.addAttribute("selectedYear", period.startDate().getYear());
        model.addAttribute("selectedQuarter", quarterNumber(period));
        model.addAttribute("selectableYears", taxperiodsnapshotService.selectableYears());

        String role = currentUserContext.getCurrentRole();
        model.addAttribute("closeBlockedReason", taxperiodsnapshotService.closeBlockedReason(role, period));
        Integer autoNextGroup = taxperiodsnapshotService.previewAutoNextGroup(period);
        model.addAttribute("autoNextGroupDisplay", TaxRevenueGroup.label(autoNextGroup));

        model.addAttribute("basePath", basePath);

        return "tax-period/preview";
    }

    // Chốt kỳ thuế theo form đã nhập, rồi chuyển sang trang chi tiết kỳ vừa chốt.
    @PostMapping({OWNER_BASE + "/close", ACCOUNTANT_BASE + "/close"})
    public String close(@ModelAttribute("form") TaxPeriodCloseRequest form,
                        HttpServletRequest request,
                        RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            Integer id = taxperiodsnapshotService.closePeriod(form, currentUserContext.getCurrentRole());

            TaxperiodsnapshotService.TaxPeriod closedPeriod =
                    taxperiodsnapshotService.resolveQuarter(
                            Integer.parseInt(form.getPeriodLabel().substring(0, 4)),
                            Integer.parseInt(form.getPeriodLabel().substring(6))
                    );

            taxRevenueNotificationService.checkGroupTransitionAndWarn(closedPeriod, id);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã chốt kỳ thuế " + form.getPeriodLabel());
            return "redirect:" + basePath + "/" + id;
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath + "/preview";
        }
    }

    // Điều chỉnh (amend) kỳ thuế mới nhất đã chốt theo form nhập lại.
    @PostMapping({OWNER_BASE + "/{periodId}/update", ACCOUNTANT_BASE + "/{periodId}/update"})
    public String update(@PathVariable Integer periodId,
                         @ModelAttribute("form") TaxPeriodUpdateRequest form,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        try {
            taxperiodsnapshotService.updateLatest(periodId, form);
            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật kỳ thuế");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:" + basePath + "/" + periodId;
    }

    /** Số quý 1–4, suy từ tháng đầu kỳ. */
    private int quarterNumber(TaxperiodsnapshotService.TaxPeriod period) {
        return (period.startDate().getMonthValue() - 1) / 3 + 1;
    }

    // Hiển thị chi tiết một kỳ thuế đã chốt.
    @GetMapping({OWNER_BASE + "/{periodId}", ACCOUNTANT_BASE + "/{periodId}"})
    public String detail(@PathVariable Integer periodId,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        String basePath = resolveBasePath(request);
        try {
            model.addAttribute("detail", taxperiodsnapshotService.getDetail(periodId));
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath;
        }

        model.addAttribute("basePath", basePath);

        return "tax-period/detail";
    }

    /**
     * Nhận số tiền có dấu chấm ngăn cách kiểu vi-VN ("45.500.000") lẫn số thuần.
     *
     * <p>Fragment {@code data-money} thường tự bỏ dấu chấm trước khi submit; nếu script đó không
     * chạy thì converter mặc định của Spring sẽ ném lỗi 400 thô thay vì flash message. Tiền trên các
     * màn hình này là đồng nguyên, nên dấu chấm luôn là dấu ngăn cách, không phải thập phân.</p>
     */
    @InitBinder
    public void bindSeparatedMoney(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                String cleaned = text == null ? "" : text.replace(".", "").replace(",", "").trim();
                setValue(cleaned.isEmpty() ? null : new BigDecimal(cleaned));
            }
        });
    }

    // Xác định basePath (Owner hay Accountant) dựa trên URI của request.
    private String resolveBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ACCOUNTANT_BASE) ? ACCOUNTANT_BASE : OWNER_BASE;
    }
}
