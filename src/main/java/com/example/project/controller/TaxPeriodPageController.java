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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tax period ("Kỳ thuế") screens — list and detail, both read-only for now; closing a period is a
 * separate step.
 *
 * <p>Reachable by the Accountant and the Owner. The BA's rule is that the Accountant closes a period
 * and the Owner only does so when the pharmacy has no accountant, but that only gates the
 * <em>closing</em> action; both roles may always look. There is no Pharmacist route, matching
 * Expense and the Financial Settings screen.</p>
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

    @GetMapping({OWNER_BASE, ACCOUNTANT_BASE})
    public String list(HttpServletRequest request, Model model) {
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
     * "Xem trước" — the period's figures totalled live from the transactions in it, with nothing
     * written. The docx allows a period to be closed either by hand or from a runtime calculation,
     * and this is the runtime half: it forces every definition (which documents count, from which
     * date, under which group) to be settled while a wrong answer still costs nothing but a reload.
     *
     * <p>Mapped above {@code /{periodId}} on purpose — Spring ranks a literal segment higher than a
     * template variable, so {@code /preview} never reaches the detail handler's {@code Integer}
     * conversion.</p>
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
            model.addAttribute("computation", taxperiodsnapshotService.computePeriod(period));
            taxRevenueNotificationService.warnIfRevenueThresholdReached(period, null);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath + "/preview";
        }

        model.addAttribute("selectedYear", period.startDate().getYear());
        model.addAttribute("selectedQuarter", quarterNumber(period));
        model.addAttribute("selectableYears", taxperiodsnapshotService.selectableYears());

        String role = currentUserContext.getCurrentRole();
        model.addAttribute("closeBlockedReason", taxperiodsnapshotService.closeBlockedReason(role, period));
        model.addAttribute("defaultNextGroup", taxperiodsnapshotService.groupForPeriod(period));
        model.addAttribute("groupOptions", TaxRevenueGroup.ALL);
        model.addAttribute("groupLabels", groupLabels());

        model.addAttribute("basePath", basePath);

        return "tax-period/preview";
    }

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

            taxRevenueNotificationService.warnIfRevenueThresholdReached(closedPeriod, id);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã chốt kỳ thuế " + form.getPeriodLabel());
            return "redirect:" + basePath + "/" + id;
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + basePath + "/preview";
        }
    }

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

    /** Group code -> Vietnamese label, for the "nhóm áp dụng cho kỳ sau" dropdown. */
    private Map<Integer, String> groupLabels() {
        Map<Integer, String> labels = new LinkedHashMap<>();
        for (Integer group : TaxRevenueGroup.ALL) {
            labels.put(group, TaxRevenueGroup.label(group));
        }
        return labels;
    }

    /** 1–4, derived from the period's first month. */
    private int quarterNumber(TaxperiodsnapshotService.TaxPeriod period) {
        return (period.startDate().getMonthValue() - 1) / 3 + 1;
    }

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

        model.addAttribute("groupOptions", TaxRevenueGroup.ALL);
        model.addAttribute("groupLabels", groupLabels());
        model.addAttribute("basePath", basePath);

        return "tax-period/detail";
    }

    /**
     * Accepts a vi-VN thousand-separated amount ("45.500.000") as well as plain digits.
     *
     * <p>The {@code data-money} fragment normally strips the separators before submitting, so the
     * server sees plain digits — but if that script does not run, Spring's default converter throws
     * and the user gets a raw 400 error page instead of a flash message. Money on these screens is
     * whole đồng, so a dot is always a separator here and never a decimal point.</p>
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

    private String resolveBasePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ACCOUNTANT_BASE) ? ACCOUNTANT_BASE : OWNER_BASE;
    }
}
