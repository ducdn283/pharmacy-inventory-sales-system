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
 * Màn "Cài đặt giá bán" (chỉ Chủ nhà thuốc). Cho phép sửa giá bán của sản phẩm ngay trên danh
 * sách, không cần mở từng trang chi tiết. Mỗi sản phẩm lưu tất cả đơn vị cùng lúc qua {@code
 * /product} (một nút "Lưu" cho cả sản phẩm, không phải từng đơn vị).
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

    // Hiển thị danh sách sản phẩm có thể sửa giá bán, có tìm kiếm/lọc theo loại/sắp xếp và phân trang.
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
        // Chỉ có giá trị ngay sau khi lưu, để sản phẩm vừa sửa tự mở lại thay vì đóng mất.
        model.addAttribute("expandProductId", expandProductId);

        model.addAttribute("currentPage", rowPage.getNumber());
        model.addAttribute("totalPages", rowPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", rowPage.getTotalElements());

        return "owner/price-settings";
    }

    /**
     * Dữ liệu cho modal "Chi tiết giá &amp; thuế" của một sản phẩm. Gọi khi bấm xem, không tải
     * kèm danh sách để tránh tốn query cho các sản phẩm không ai xem chi tiết.
     */
    @GetMapping("/{productId}/detail")
    @ResponseBody
    public PriceSettingDetailResponse detail(@PathVariable Integer productId) {
        return pricesettingService.getDetail(productId);
    }

    /**
     * Lưu tất cả đơn vị của một sản phẩm cùng lúc. {@code productUnitId}/{@code sellPrice} là
     * hai danh sách khớp vị trí (mỗi cặp ứng với một dòng đơn vị trên form). Xem
     * {@link PricesettingService#updatePrices} để biết logic lưu/cascade giá.
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

    // Dựng thông báo flash mô tả kết quả lưu giá (số đơn vị đã sửa trực tiếp và số đơn vị cascade theo).
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
