package com.example.project.controller;

import com.example.project.dto.response.PriceSettingDetailResponse;
import com.example.project.dto.response.PriceSettingProductRowResponse;
import com.example.project.service.PricesettingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owner-only "Cài đặt giá bán" screen — lets the Owner change any product's sell price directly
 * from one list instead of opening each product's own edit page (see
 * {@link PricesettingService} for the full framing). Same permission scope as Product
 * create/edit, which are also Owner-only. Every unit row of one product saves together, in one
 * action, via {@code /product} — a product with several units used to need one "Lưu" click per
 * row; now it's one click for the whole product.
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

    /**
     * Data behind the "Chi tiết giá &amp; thuế" modal, for one product. Fetched on click rather than
     * rendered with the list: the list shows 10 products a page and most visits never open the
     * panel, so pre-loading each one's batches would be 10 wasted queries per page view.
     *
     * <p>Same {@code @ResponseBody}-on-the-page-controller shape as
     * {@code PurchaseInvoicePageController.getProcurementPlanDetails} and
     * {@code CustomerController.checkDuplicate} — the generated {@code @RestController}s stay
     * untouched.</p>
     */
    @GetMapping("/{productId}/detail")
    @ResponseBody
    public PriceSettingDetailResponse detail(@PathVariable Integer productId) {
        return pricesettingService.getDetail(productId);
    }

    /**
     * Saves every unit row of one product at once — {@code productUnitId}/{@code sellPrice} are
     * two same-length, position-matched lists (one pair per unit row rendered on the product's
     * expanded panel), which Spring collects from the repeated same-name form fields in submission
     * order. See {@link PricesettingService#updatePrices} for the save/cascade semantics.
     */
    @PostMapping("/product")
    public String saveProduct(@RequestParam Integer productId,
                               @RequestParam("productUnitId") List<Integer> productUnitIds,
                               @RequestParam("sellPrice") List<BigDecimal> sellPrices,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer typeId,
                               @RequestParam(required = false) String sort,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false, defaultValue = "10") int size,
                               @RequestParam(required = false) Integer expandProductId,
                               RedirectAttributes redirectAttributes) {
        try {
            Map<Integer, BigDecimal> sellPriceByUnitId = new LinkedHashMap<>();
            for (int i = 0; i < productUnitIds.size() && i < sellPrices.size(); i++) {
                sellPriceByUnitId.put(productUnitIds.get(i), sellPrices.get(i));
            }

            PricesettingService.PriceUpdateResult result =
                    pricesettingService.updatePrices(productId, sellPriceByUnitId);
            redirectAttributes.addFlashAttribute("successMessage", describeResult(result));
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

    private String describeResult(PricesettingService.PriceUpdateResult result) {
        if (result.explicitCount() == 0) {
            return "Không có thay đổi nào để lưu";
        }
        if (result.cascadedCount() > 0) {
            return "Đã cập nhật giá bán cho " + result.explicitCount() + " đơn vị (đồng bộ theo tỷ lệ thêm "
                    + result.cascadedCount() + " đơn vị khác)";
        }
        return "Đã cập nhật giá bán cho " + result.explicitCount() + " đơn vị";
    }
}
