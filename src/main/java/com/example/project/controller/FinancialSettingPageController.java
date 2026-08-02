package com.example.project.controller;

import com.example.project.dto.request.FinancialSettingUpdateRequest;
import com.example.project.dto.response.FinancialsettingResponse;
import com.example.project.service.FinancialsettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Controller
public class FinancialSettingPageController {
    private static final String VIEW = "owner/financial-setting";

    private final FinancialsettingService financialsettingService;

    public FinancialSettingPageController(FinancialsettingService financialsettingService) {
        this.financialsettingService = financialsettingService;
    }

    @GetMapping({"/owner/financial-setting", "/accountant/financial-setting"})
    public String view(HttpServletRequest request, Model model) {
        boolean editable = request.getRequestURI().startsWith("/owner/");

        FinancialsettingResponse settings = financialsettingService.getSettings();
        if (!model.containsAttribute("financialSettingForm")) {
            model.addAttribute("financialSettingForm", toForm(settings));
        }
        model.addAttribute("editable", editable);
        model.addAttribute("revenueGroupLocked", financialsettingService.isRevenueGroupLocked());
        model.addAttribute("cashSafeBalanceLocked", financialsettingService.isCashSafeBalanceLocked());
        model.addAttribute("bankAccountBalanceLocked", financialsettingService.isBankAccountBalanceLocked());
        model.addAttribute("balanceUpdatedAtDisplay", formatBalanceUpdatedAt(settings.getBalanceUpdatedAt()));
        model.addAttribute("pageTitle", "Thiết lập tài chính");
        return VIEW;
    }

    @PostMapping("/owner/financial-setting")
    public String save(@Valid @ModelAttribute("financialSettingForm") FinancialSettingUpdateRequest form,
                        BindingResult bindingResult,
                        Model model,
                        RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("editable", true);
            model.addAttribute("revenueGroupLocked", financialsettingService.isRevenueGroupLocked());
            model.addAttribute("cashSafeBalanceLocked", financialsettingService.isCashSafeBalanceLocked());
            model.addAttribute("bankAccountBalanceLocked", financialsettingService.isBankAccountBalanceLocked());
            model.addAttribute("balanceUpdatedAtDisplay",
                    formatBalanceUpdatedAt(financialsettingService.getSettings().getBalanceUpdatedAt()));
            model.addAttribute("pageTitle", "Thiết lập tài chính");
            return VIEW;
        }

        try {
            financialsettingService.saveSettings(form);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/owner/financial-setting";
        }
        redirectAttributes.addFlashAttribute("successMessage", "Cập nhật thiết lập tài chính thành công");
        return "redirect:/owner/financial-setting";
    }

    private FinancialSettingUpdateRequest toForm(FinancialsettingResponse response) {
        FinancialSettingUpdateRequest form = new FinancialSettingUpdateRequest();
        form.setTaxCalculationMethod(response.getTaxCalculationMethod());
        form.setRevenueGroup(response.getRevenueGroup());
        form.setAnnualRevenueThreshold1(response.getAnnualRevenueThreshold1());
        form.setAnnualRevenueThreshold2(response.getAnnualRevenueThreshold2());
        form.setReturnProductOnInvoiceValueRate(response.getReturnProductOnInvoiceValueRate());
        form.setAutoGenerateVATInvoice(response.getAutoGenerateVATInvoice());
        form.setVatInvoiceSeries(response.getVatInvoiceSeries());
        form.setOpeningCashDefault(response.getOpeningCashDefault());
        form.setTaxCode(response.getTaxCode());
        form.setLocationCode(response.getLocationCode());
        form.setLocationName(response.getLocationName());
        form.setAddress(response.getAddress());
        form.setPhoneNumber(response.getPhoneNumber());
        form.setEmail(response.getEmail());
        form.setBankAccountNumber(response.getBankAccountNumber());
        form.setBankName(response.getBankName());
        form.setAutoOffsetDebtOnRefund(response.getAutoOffsetDebtOnRefund());
        form.setReturnPolicyMaxDays(response.getReturnPolicyMaxDays());
        form.setCashSafeBalance(response.getCashSafeBalance());
        form.setBankAccountBalance(response.getBankAccountBalance());
        return form;
    }

    private String formatBalanceUpdatedAt(LocalDateTime balanceUpdatedAt) {
        if (balanceUpdatedAt == null) {
            return "Chưa cập nhật";
        }
        return balanceUpdatedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }
}
