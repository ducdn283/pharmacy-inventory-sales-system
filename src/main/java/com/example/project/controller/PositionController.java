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

@Controller
public class PositionController {
    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    // hiện danh sách vị trí — chỉ Owner
    @GetMapping("/owner/positions")
    //required = false nghĩa là ko bắt buộc phải có
    public String positionList(@RequestParam(name = "search", required = false) String search,
                               @RequestParam(name = "page", defaultValue = "0") int page, //trang số mấy, nếu ko có page=0
                               @RequestParam(name = "size", defaultValue = "5") int size, //số lượng bản ghi mỗi trang
                               Model model) {
        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 5;
        }

        //Pageable là interface mô tả cách phân trang: trang số mấy, số lượng bản ghi mỗi trang, sắp xếp tăng dần theo id
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        //lấy danh sách vị trí
        Page<PositionResponse> positionPage = positionService.list(search, pageable);

        model.addAttribute("positions", positionPage.getContent()); //gửi đi danh sách vị trí
        model.addAttribute("totalPositions", positionService.countAll()); // đếm tổng vị trí
        model.addAttribute("search", search);
        model.addAttribute("currentPage", positionPage.getNumber()); //trang hiện tại (1,2,3,...)
        model.addAttribute("totalPages", positionPage.getTotalPages());  //tổng số trang
        model.addAttribute("pageSize", size); //số lượng bản ghi mỗi trang
        model.addAttribute("totalItems", positionPage.getTotalElements()); //tổng số vị trí
        model.addAttribute("pageTitle", "Danh sách vị trí");
        model.addAttribute("basePath", "/owner/positions");
        return "owner/position-list";
    }

    // hiển thị trang tạo Position: chỉ hiển thị form
    @GetMapping("/owner/positions/create-position")
    public String createPositionForm(Model model) {
        if (!model.containsAttribute("positionForm")) {
            model.addAttribute("positionForm", new PositionCreateRequest());
        }
        populateCreateForm(model);
        return "owner/create-position";
    }

    // tạo vị trí
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

    // hiển thị trang update Position: chỉ hiển thị form
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

    // chỉnh sửa vị trí
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

    //form tạo
    private void populateCreateForm(Model model) {
        populateForm(model, "Tạo vị trí");
    }

    // thêm dữ liệu vào form
    private void populateForm(Model model, String pageTitle) {
        model.addAttribute("products", positionService.listProducts());
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("basePath", "/owner/positions");
    }
}
