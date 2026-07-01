package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleWebController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/roles')")
    public String list(@RequestParam(required = false) String editId, Model model) {
        model.addAttribute("activePage", "roles");
        model.addAttribute("roles", roleService.findAllRoles());
        model.addAttribute("permissionsByCategory", roleService.permissionsByCategory());
        if (editId != null) {
            roleService.findById(editId).ifPresent(role -> {
                model.addAttribute("editEntity", role);
                model.addAttribute("selectedPermissionIds", roleService.getPermissionIdsForRole(role.getId()));
            });
        }
        return "roles";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/roles')")
    public String create(@RequestParam String code,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) List<String> permissionIds,
                         RedirectAttributes ra) {
        try {
            roleService.createRole(code, name, description, permissionIds);
            ra.addFlashAttribute("successMessage", "نقش با موفقیت ایجاد شد.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/roles";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/roles/{id}')")
    public String update(@PathVariable String id,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) List<String> permissionIds,
                         RedirectAttributes ra) {
        try {
            roleService.updateRole(id, name, description, permissionIds);
            ra.addFlashAttribute("successMessage", "نقش با موفقیت ویرایش شد.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/roles";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/roles/{id}/delete')")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        try {
            roleService.deleteRole(id);
            ra.addFlashAttribute("successMessage", "نقش حذف شد.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/roles";
    }
}
