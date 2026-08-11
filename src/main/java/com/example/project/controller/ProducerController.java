package com.example.project.controller;

import com.example.project.dto.request.ProducerCreateRequest;
import com.example.project.dto.response.ProducerResponse;
import com.example.project.service.ProducerService;
import jakarta.servlet.http.HttpServletRequest;
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

@Controller
public class ProducerController {
    private final ProducerService producerService;

    public ProducerController(ProducerService producerService) {
        this.producerService = producerService;
    }

    // như position (comment bên position)
    // hiện danh sách nhà sản xuất
    @GetMapping({
            "/owner/producers",
            "/pharmacist/producers",
            "/accountant/producers"
    })
    public String producerList(@RequestParam(name = "search", required = false) String search,
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

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<ProducerResponse> producerPage = producerService.list(search, pageable);
        String basePath = resolveBasePath(request);

        model.addAttribute("producers", producerPage.getContent());
        model.addAttribute("totalProducers", producerService.countAll());
        model.addAttribute("search", search);
        model.addAttribute("currentPage", producerPage.getNumber());
        model.addAttribute("totalPages", producerPage.getTotalPages());
        model.addAttribute("pageSize", size);
        model.addAttribute("totalItems", producerPage.getTotalElements());
        model.addAttribute("pageTitle", "Danh sách nhà sản xuất");
        model.addAttribute("basePath", basePath);
        return "owner/producer-list";
    }

    // tạo from create
    @GetMapping("/owner/producers/create-producer")
    public String createProducerForm(HttpServletRequest request, Model model) {
        if (!model.containsAttribute("producerForm")) {
            model.addAttribute("producerForm", new ProducerCreateRequest());
        }
        model.addAttribute("pageTitle", "Tạo nhà sản xuất");
        model.addAttribute("basePath", resolveBasePath(request));
        return "owner/create-producer";
    }

    // Quick-add from the Product create/edit form's "+" button (Owner-only, since that's the only
    // role with a product create/edit screen) — same JSON-in/JSON-out shape as the sale screen's
    // "thêm khách hàng nhanh" endpoint (InvoiceController.createCustomerFromSelling).
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

    //tạo nhà sản xuất
    @PostMapping("/owner/producers/create-producer")
    public String createProducer(@Valid @ModelAttribute("producerForm") ProducerCreateRequest form,
                                 BindingResult bindingResult,
                                 HttpServletRequest request,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        if (bindingResult.hasErrors()) {
            model.addAttribute("pageTitle", "Tạo nhà sản xuất");
            model.addAttribute("basePath", basePath);
            return "owner/create-producer";
        }

        producerService.create(form);
        redirectAttributes.addFlashAttribute("success", "Tạo nhà sản xuất thành công");
        return "redirect:" + basePath;
    }

    //tạo form update
    @GetMapping("/owner/producers/update-producer/{id}")
    public String updateProducerForm(@PathVariable Integer id,
                                     HttpServletRequest request,
                                     Model model) {
        ProducerResponse producer = producerService.getById(id);
        model.addAttribute("producer", producer);

        if (!model.containsAttribute("producerForm")) {
            ProducerCreateRequest form = new ProducerCreateRequest();
            form.setName(producer.getName());
            model.addAttribute("producerForm", form);
        }

        model.addAttribute("pageTitle", "Cập nhật nhà sản xuất");
        model.addAttribute("basePath", resolveBasePath(request));
        return "owner/update-producer";
    }

    // chỉnh sửa nhà sản xuất
    @PostMapping("/owner/producers/update-producer/{id}")
    public String updateProducer(@PathVariable Integer id,
                                 @Valid @ModelAttribute("producerForm") ProducerCreateRequest form,
                                 BindingResult bindingResult,
                                 HttpServletRequest request,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        String basePath = resolveBasePath(request);
        if (bindingResult.hasErrors()) {
            model.addAttribute("producer", producerService.getById(id));
            model.addAttribute("pageTitle", "Cập nhật nhà sản xuất");
            model.addAttribute("basePath", basePath);
            return "owner/update-producer";
        }

        producerService.update(id, form);
        redirectAttributes.addFlashAttribute("success", "Cập nhật nhà sản xuất thành công");
        return "redirect:" + basePath;
    }

    // lấy url
    private String resolveBasePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/pharmacist/producers")) {
            return "/pharmacist/producers";
        }
        if (uri.startsWith("/accountant/producers")) {
            return "/accountant/producers";
        }
        return "/owner/producers";
    }
}
