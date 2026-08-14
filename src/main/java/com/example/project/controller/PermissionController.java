package com.example.project.controller;

import com.example.project.constant.RoleConstants;
import com.example.project.service.OwnerPermissionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Màn hình "Bảng phân quyền" — chỉ Owner được truy cập. Mỗi tài khoản một dòng, một dropdown
 * vai trò, một nút lưu. Đổi vai trò sẽ post lên {@code /owner/permissions/cell}. Quyền truy cập
 * đã được Spring Security kiểm tra qua tiền tố {@code /owner/**} (yêu cầu {@code ROLE_OWNER}),
 * nên không cần check role thủ công ở đây.
 */
@Controller
@RequestMapping("/owner/permissions")
public class PermissionController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 10;

    private final OwnerPermissionService ownerPermissionService;

    public PermissionController(OwnerPermissionService ownerPermissionService) {
        this.ownerPermissionService = ownerPermissionService;
    }

    // Hiển thị màn hình Bảng phân quyền: danh sách tài khoản kèm vai trò, có phân trang và tìm kiếm.
    @GetMapping
    public String view(@RequestParam(name = "page", required = false, defaultValue = "0") int page,
                       @RequestParam(name = "size", required = false, defaultValue = "10") int size,
                       @RequestParam(name = "search", required = false) String search,
                       Model model) {
        model.addAttribute("permissionPage", ownerPermissionService.getPermissionPage(search, page, size));
        model.addAttribute("assignableRoleLabels", RoleConstants.permissionTableRoleLabels());
        model.addAttribute("search", search);
        model.addAttribute("pageTitle", "Bảng phân quyền");
        return "owner/permissions";
    }

    // Lưu vai trò mới cho 1 tài khoản (đổi dropdown trên 1 dòng), rồi quay lại trang/tìm kiếm hiện tại.
    @PostMapping("/cell")
    public String saveCell(@RequestParam Integer accountId,
                           @RequestParam(required = false) String role,
                           @RequestParam(required = false) String search,
                           @RequestParam(required = false, defaultValue = "0") int page,
                           @RequestParam(required = false, defaultValue = "10") int size,
                           RedirectAttributes redirectAttributes) {
        try {
            ownerPermissionService.saveRole(accountId, role);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật phân quyền thành công");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        // Giữ lại trang và từ khóa tìm kiếm hiện tại
        redirectAttributes.addAttribute("page", page < 0 ? DEFAULT_PAGE : page);
        redirectAttributes.addAttribute("size", size <= 0 ? DEFAULT_SIZE : size);
        if (search != null && !search.isBlank()) {
            redirectAttributes.addAttribute("search", search);
        }

        return "redirect:/owner/permissions";
    }
}
