package com.example.project.controller;

import com.example.project.dto.response.PriceSettingProductRowResponse;
import com.example.project.service.PricesettingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * Owner-only "Cài đặt giá bán" screen — lets the Owner change any product's sell price directly
 * from one list instead of opening each product's own edit page (see
 * {@link PricesettingService} for the full framing). Same permission scope as Product
 * create/edit, which are also Owner-only. Each unit row saves independently via {@code /cell},
 * same shape as {@code PermissionController}'s per-cell save.
 */
@Controller
@RequestMapping("/owner/price-settings")
public class PriceSettingPageController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 10;

    private final PricesettingService pricesettingService;

    public PriceSettingPageController(PricesettingService pricesettingService) {
        this.pricesettingService = pricesettingService;
    }

    @GetMapping
    public String list(@RequestParam(name = "keyword", required = false) String keyword,
                        @RequestParam(name = "typeId", required = false) Integer typeId,
                        @RequestParam(name = "sort", required = false,
                                defaultValue = PricesettingService.SORT_NAME_ASC) String sort,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        @RequestParam(name = "size", defaultValue = "10") int size,
                        @RequestParam(name = "expandProductId", required = false) Integer expandProductId,
                        Model model) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }

        Page<PriceSettingProductRowResponse> rowPage =
                pricesettingService.search(keyword, typeId, sort, PageRequest.of(page, size));

        model.addAttribute("rowPage", rowPage);
        model.addAttribute("rows", rowPage.getContent());
        model.addAttribute("types", pricesettingService.listTypes());

        model.addAttribute("keyword", keyword);
        model.addAttribute("filterTypeId", typeId);
        model.addAttribute("sort", sort);
        // Set only right after a /cell save, so the product whose price just changed re-opens
        // instead of collapsing back and hiding the result of the edit.
        model.addAttribute("expandProductId", expandProductId);

        model.addAttribute("currentPage", rowPage.getNumber());
        model.addAttribute("totalPages", rowPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", rowPage.getTotalElements());

        return "owner/price-settings";
    }

    @PostMapping("/cell")
    public String saveCell(@RequestParam Integer productUnitId,
                            @RequestParam BigDecimal sellPrice,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) Integer typeId,
                            @RequestParam(required = false) String sort,
                            @RequestParam(required = false, defaultValue = "0") int page,
                            @RequestParam(required = false, defaultValue = "10") int size,
                            @RequestParam(required = false) Integer expandProductId,
                            RedirectAttributes redirectAttributes) {
        try {
            int cascaded = pricesettingService.updatePrice(productUnitId, sellPrice);
            String message = cascaded > 0
                    ? "Đã cập nhật giá bán (đồng bộ theo tỷ lệ cho " + cascaded + " đơn vị khác)"
                    : "Đã cập nhật giá bán";
            redirectAttributes.addFlashAttribute("successMessage", message);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        redirectAttributes.addAttribute("page", page < 0 ? DEFAULT_PAGE : page);
        redirectAttributes.addAttribute("size", size <= 0 ? DEFAULT_SIZE : size);
        if (keyword != null && !keyword.isBlank()) {
            redirectAttributes.addAttribute("keyword", keyword);
        }
        if (typeId != null) {
            redirectAttributes.addAttribute("typeId", typeId);
        }
        if (sort != null && !sort.isBlank()) {
            redirectAttributes.addAttribute("sort", sort);
        }
        if (expandProductId != null) {
            redirectAttributes.addAttribute("expandProductId", expandProductId);
        }

        return "redirect:/owner/price-settings";
    }
}
