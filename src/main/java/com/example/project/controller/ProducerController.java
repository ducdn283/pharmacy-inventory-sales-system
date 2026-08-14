package com.example.project.controller;

import com.example.project.dto.request.ProducerCreateRequest;
import com.example.project.dto.response.ProducerResponse;
import com.example.project.service.ProducerService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Màn quản lý nhà sản xuất (danh sách / tạo / sửa) — chỉ Owner.
 */
@Controller
public class ProducerController {
    private final ProducerService producerService;

    public ProducerController(ProducerService producerService) {
        this.producerService = producerService;
    }

    /** Danh sách nhà sản xuất có phân trang và tìm kiếm. */
    @GetMapping("/owner/producers")
    public String producerList(@RequestParam(name = "search", required = false) String search,
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
        Page<ProducerResponse> producerPage = producerService.list(search, pageable);

        model.addAttribute("producers", producerPage.getContent());
        model.addAttribute("totalProducers", producerService.countAll());
        model.addAttribute("search", search);
        model.addAttribute("currentPage", producerPage.getNumber());
        model.addAttribute("totalPages", producerPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", producerPage.getTotalElements());
        model.addAttribute("pageTitle", "Danh sách nhà sản xuất");
        model.addAttribute("basePath", "/owner/producers");
        return "owner/producer-list";
    }

    /** Form tạo nhà sản xuất — giữ {@code producerForm} nếu redirect sau lỗi validate. */
    @GetMapping("/owner/producers/create-producer")
    public String createProducerForm(Model model) {
        if (!model.containsAttribute("producerForm")) {
            model.addAttribute("producerForm", new ProducerCreateRequest());
        }
        model.addAttribute("pageTitle", "Tạo nhà sản xuất");
        model.addAttribute("basePath", "/owner/producers");
        return "owner/create-producer";
    }

    /**
     * Tạo nhanh nhà sản xuất từ nút "+" trên form tạo/sửa hàng hóa (JSON).
     * Cùng kiểu request/response với endpoint thêm khách hàng nhanh trên màn bán hàng.
     */
    @PostMapping(value = "/owner/producers/quick-create",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> quickCreateProducer(@Valid @RequestBody ProducerCreateRequest request,
                                                 BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            String message = bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .findFirst()
                    .orElse("Dữ liệu không hợp lệ");
            return ResponseEntity.badRequest().body(Map.of("message", message));
        }
        try {
            return ResponseEntity.ok(producerService.create(request));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    /** Xử lý submit tạo nhà sản xuất — validate rồi redirect về danh sách. */
    @PostMapping("/owner/producers/create-producer")
    public String createProducer(@Valid @ModelAttribute("producerForm") ProducerCreateRequest form,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Tạo nhà sản xuất");
            model.addAttribute("basePath", "/owner/producers");
            return "owner/create-producer";
        }

        producerService.create(form);
        redirectAttributes.addFlashAttribute("success", "Tạo nhà sản xuất thành công");
        return "redirect:/owner/producers";
    }

    /** Form sửa nhà sản xuất — điền sẵn {@code producerForm} từ dữ liệu hiện tại. */
    @GetMapping("/owner/producers/update-producer/{id}")
    public String updateProducerForm(@PathVariable Integer id, Model model) {
        ProducerResponse producer = producerService.getById(id);
        model.addAttribute("producer", producer);

        if (!model.containsAttribute("producerForm")) {
            ProducerCreateRequest form = new ProducerCreateRequest();
            form.setName(producer.getName());
            model.addAttribute("producerForm", form);
        }

        model.addAttribute("pageTitle", "Cập nhật nhà sản xuất");
        model.addAttribute("basePath", "/owner/producers");
        return "owner/update-producer";
    }

    /** Xử lý submit cập nhật nhà sản xuất — validate rồi redirect về danh sách. */
    @PostMapping("/owner/producers/update-producer/{id}")
    public String updateProducer(@PathVariable Integer id,
                                 @Valid @ModelAttribute("producerForm") ProducerCreateRequest form,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("producer", producerService.getById(id));
            model.addAttribute("pageTitle", "Cập nhật nhà sản xuất");
            model.addAttribute("basePath", "/owner/producers");
            return "owner/update-producer";
        }

        producerService.update(id, form);
        redirectAttributes.addFlashAttribute("success", "Cập nhật nhà sản xuất thành công");
        return "redirect:/owner/producers";
    }
}
