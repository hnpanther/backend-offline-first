package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.service.RoleService;
import com.hnp.backendofflinefirst.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserWebController {

    private final UserService userService;
    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/users')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) Long changePasswordId,
                       Model model) {
        model.addAttribute("activePage", "users");
        model.addAttribute("users", userService.findAll());
        model.addAttribute("roles", roleService.findAllRoles());
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

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/users')")
    public String create(@RequestParam String username,
                         @RequestParam String fullName,
                         @RequestParam String password,
                         @RequestParam(defaultValue = "false") boolean active,
                         @RequestParam(required = false) List<Long> roleIds,
                         RedirectAttributes ra) {
        try {
            userService.create(username, fullName, password, active, roleIds);
            ra.addFlashAttribute("successMessage", "کاربر با موفقیت ایجاد شد.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/users/{id}')")
    public String update(@PathVariable Long id,
                         @RequestParam String username,
                         @RequestParam String fullName,
                         @RequestParam(defaultValue = "false") boolean active,
                         @RequestParam(required = false) List<Long> roleIds,
                         RedirectAttributes ra) {
        try {
            userService.update(id, username, fullName, active, roleIds);
            ra.addFlashAttribute("successMessage", "کاربر با موفقیت ویرایش شد.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
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
            ra.addFlashAttribute("errorMessage", "رمز عبور و تکرار آن یکسان نیست.");
            return "redirect:/users?changePasswordId=" + id;
        }
        if (newPassword.length() < 6) {
            ra.addFlashAttribute("errorMessage", "رمز عبور باید حداقل ۶ کاراکتر باشد.");
            return "redirect:/users?changePasswordId=" + id;
        }
        try {
            userService.changePassword(id, newPassword);
            ra.addFlashAttribute("successMessage", "رمز عبور با موفقیت تغییر کرد.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/users/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            userService.delete(id);
            ra.addFlashAttribute("successMessage", "کاربر با موفقیت حذف شد.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
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
