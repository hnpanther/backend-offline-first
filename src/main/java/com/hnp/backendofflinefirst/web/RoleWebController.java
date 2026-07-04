package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.RoleService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
    private final RoleRepository roleRepository;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/roles')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                roleRepository::findAll,
                roleRepository::search);
        model.addAttribute("activePage", "roles");
        model.addAttribute("roles", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("permissionsByCategory", roleService.permissionsByCategory());
        if (editId != null) {
            roleService.findById(editId).ifPresent(role -> {
                model.addAttribute("editEntity", role);
                model.addAttribute("selectedPermissionIds", roleService.getPermissionIdsForRole(role.getId()));
            });
        }
        return "roles";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/roles')")
    public void export(HttpServletResponse response) throws java.io.IOException {
        excelExportService.exportRoles(response);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/roles')")
    public String create(@RequestParam String code,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) List<Long> permissionIds,
                         RedirectAttributes ra) {
        try {
            roleService.createRole(code, name, description, permissionIds);
            ra.addFlashAttribute("successMessage", FaMessages.roleCreated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/roles";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/roles/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) List<Long> permissionIds,
                         RedirectAttributes ra) {
        try {
            roleService.updateRole(id, name, description, permissionIds);
            ra.addFlashAttribute("successMessage", FaMessages.roleUpdated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/roles";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/roles/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            roleService.deleteRole(id);
            ra.addFlashAttribute("successMessage", FaMessages.roleDeleted());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/roles";
    }
}
