package com.example.project.controller;

import com.example.project.dto.request.PositionCreateRequest;
import com.example.project.dto.response.PositionResponse;
import com.example.project.service.PositionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Màn quản lý vị trí lưu kho (danh sách / tạo / sửa) — chỉ Owner.
 */
@Controller
public class PositionController {
    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    /** Danh sách vị trí có phân trang và tìm kiếm. */
    @GetMapping("/owner/positions")
    public String positionList(@RequestParam(name = "search", required = false) String search,
                               @RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "5") int size,
                               Model model) {
        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 5;
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<PositionResponse> positionPage = positionService.list(search, pageable);

        model.addAttribute("positions", positionPage.getContent());
        model.addAttribute("totalPositions", positionService.countAll());
        model.addAttribute("search", search);
        model.addAttribute("currentPage", positionPage.getNumber());
        model.addAttribute("totalPages", positionPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", positionPage.getTotalElements());
        model.addAttribute("pageTitle", "Danh sách vị trí");
        model.addAttribute("basePath", "/owner/positions");
        return "owner/position-list";
    }

    /** Form tạo vị trí — giữ {@code positionForm} nếu redirect sau lỗi validate. */
    @GetMapping("/owner/positions/create-position")
    public String createPositionForm(Model model) {
        if (!model.containsAttribute("positionForm")) {
            model.addAttribute("positionForm", new PositionCreateRequest());
        }
        populateCreateForm(model);
        return "owner/create-position";
    }

    /** Xử lý submit tạo vị trí — validate rồi redirect về danh sách. */
    @PostMapping("/owner/positions/create-position")
    public String createPosition(@Valid @ModelAttribute("positionForm") PositionCreateRequest form,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            populateCreateForm(model);
            return "owner/create-position";
        }

        positionService.create(form);
        redirectAttributes.addFlashAttribute("success", "Tạo vị trí thành công");
        return "redirect:/owner/positions";
    }

    /** Form sửa vị trí — điền sẵn {@code positionForm} từ dữ liệu hiện tại. */
    @GetMapping("/owner/positions/update-position/{id}")
    public String updatePositionForm(@PathVariable Integer id, Model model) {
        PositionResponse position = positionService.getById(id);
        model.addAttribute("position", position);

        if (!model.containsAttribute("positionForm")) {
            PositionCreateRequest form = new PositionCreateRequest();
            form.setProductId(position.getProductId());
            form.setName(position.getName());
            model.addAttribute("positionForm", form);
        }

        populateForm(model, "Cập nhật vị trí");
        return "owner/update-position";
    }

    /** Xử lý submit cập nhật vị trí — validate rồi redirect về danh sách. */
    @PostMapping("/owner/positions/update-position/{id}")
    public String updatePosition(@PathVariable Integer id,
                                 @Valid @ModelAttribute("positionForm") PositionCreateRequest form,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("position", positionService.getById(id));
            populateForm(model, "Cập nhật vị trí");
            return "owner/update-position";
        }

        positionService.update(id, form);
        redirectAttributes.addFlashAttribute("success", "Cập nhật vị trí thành công");
        return "redirect:/owner/positions";
    }

    /** Chuẩn bị model cho form tạo vị trí. */
    private void populateCreateForm(Model model) {
        populateForm(model, "Tạo vị trí");
    }

    /** Nạp dropdown hàng hóa và metadata chung cho form tạo/sửa. */
    private void populateForm(Model model, String pageTitle) {
        model.addAttribute("products", positionService.listProducts());
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("basePath", "/owner/positions");
    }
}
