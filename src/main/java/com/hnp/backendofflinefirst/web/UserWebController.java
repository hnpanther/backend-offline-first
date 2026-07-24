package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.service.RoleService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.ImportWebSupport;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.ExcelUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserWebController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final RoleService roleService;
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/users')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) Long changePasswordId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                userRepository::findAll,
                userRepository::search);
        model.addAttribute("activePage", "users");
        model.addAttribute("users", result.getContent());
        WebListSupport.addPagination(model, result, q, page, pageSize);
        model.addAttribute("roles", roleService.findAllRoles());
        model.addAttribute("authTypes", UserAuthType.values());
        model.addAttribute("roleNameById", roleService.roleNameById());
        model.addAttribute("userRoleLabels", buildUserRoleLabels());

        if (editId != null) {
            userService.findById(editId).ifPresent(u -> {
                model.addAttribute("editEntity", u);
                model.addAttribute("selectedRoleIds", roleService.getRoleIdsForUser(u.getId()));
            });
        }
        if (changePasswordId != null) {
            userService.findById(changePasswordId).ifPresent(u -> model.addAttribute("passwordEntity", u));
        }
        return "users";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/users')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportUsers(response);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/users/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importUsers(file);
            ImportWebSupport.applyImportResult(result, ra);
        } catch (Exception e) {
            ImportWebSupport.applyFileError(e, ra);
        }
        return "redirect:/users";
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/users/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        ExcelUtils.writeTemplate(response, "users-template.xlsx",
                new String[]{"username", "fullName", "nationalCode", "phoneNumber", "nfcTag",
                        "password", "authType", "active", "roleCodes"});
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/users')")
    public String create(@RequestParam String username,
                         @RequestParam(required = false) String fullName,
                         @RequestParam(required = false) String nationalCode,
                         @RequestParam(required = false) String phoneNumber,
                         @RequestParam(required = false) String nfcTagId,
                         @RequestParam(required = false) String password,
                         @RequestParam(defaultValue = "LOCAL") String authType,
                         @RequestParam(defaultValue = "false") boolean active,
                         @RequestParam(required = false) List<Long> roleIds,
                         RedirectAttributes ra) {
        try {
            userService.create(username, fullName, nationalCode, phoneNumber, nfcTagId,
                    password, UserService.parseAuthType(authType), active, roleIds);
            ra.addFlashAttribute("successMessage", FaMessages.userCreated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/users/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam String username,
                         @RequestParam(required = false) String fullName,
                         @RequestParam(required = false) String nationalCode,
                         @RequestParam(required = false) String phoneNumber,
                         @RequestParam(required = false) String nfcTagId,
                         @RequestParam(defaultValue = "LOCAL") String authType,
                         @RequestParam(defaultValue = "false") boolean active,
                         @RequestParam(required = false) List<Long> roleIds,
                         RedirectAttributes ra) {
        try {
            userService.update(id, username, fullName, nationalCode, phoneNumber, nfcTagId,
                    UserService.parseAuthType(authType), active, roleIds);
            ra.addFlashAttribute("successMessage", FaMessages.userUpdated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/change-password")
    @PreAuthorize("hasAuthority('POST:/users/{id}/change-password')")
    public String changePassword(@PathVariable Long id,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 RedirectAttributes ra) {
        if (!newPassword.equals(confirmPassword)) {
            ra.addFlashAttribute("errorMessage", FaMessages.passwordMismatch());
            return "redirect:/users?changePasswordId=" + id;
        }
        if (newPassword.length() < 6) {
            ra.addFlashAttribute("errorMessage", FaMessages.passwordTooShort());
            return "redirect:/users?changePasswordId=" + id;
        }
        try {
            userService.changePassword(id, newPassword);
            ra.addFlashAttribute("successMessage", FaMessages.passwordChanged());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/users/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            userService.delete(id);
            ra.addFlashAttribute("successMessage", FaMessages.userDeleted());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/users";
    }

    private java.util.Map<Long, String> buildUserRoleLabels() {
        var roleNames = roleService.roleNameById();
        return userService.findAll().stream().collect(Collectors.toMap(
                u -> u.getId(),
                u -> roleService.getRoleIdsForUser(u.getId()).stream()
                        .map(roleNames::get)
                        .filter(n -> n != null)
                        .collect(Collectors.joining("، "))
        ));
    }
}
