package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.service.OperationalUnitService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.util.UserPickerHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/operational-units")
@RequiredArgsConstructor
public class OperationalUnitWebController {

    private final OperationalUnitService operationalUnitService;
    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/operational-units')")
    public String list(@RequestParam(required = false) String editId, Model model) {
        model.addAttribute("activePage", "operational-units");

        List<OperationalUnit> units = operationalUnitService.findAll();
        List<User> users = userService.findAll();

        Map<String, String> unitNameById = units.stream()
                .collect(Collectors.toMap(OperationalUnit::getId,
                        u -> u.getName() != null ? u.getName() : u.getCode()));
        Map<String, String> userNameById = users.stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername()));

        Map<String, List<String>> supervisorNamesByUnit = new HashMap<>();
        Map<String, List<String>> operatorNamesByUnit = new HashMap<>();
        for (OperationalUnit unit : units) {
            supervisorNamesByUnit.put(unit.getId(),
                    operationalUnitService.formatUserNames(
                            operationalUnitService.getSupervisorIds(unit.getId()), userNameById));
            operatorNamesByUnit.put(unit.getId(),
                    operationalUnitService.formatUserNames(
                            operationalUnitService.getOperatorIds(unit.getId()), userNameById));
        }

        model.addAttribute("units", units);
        model.addAttribute("users", users);
        model.addAttribute("userPickerItems", UserPickerHelper.toPickerItems(users));
        model.addAttribute("unitNameById", unitNameById);
        model.addAttribute("supervisorNamesByUnit", supervisorNamesByUnit);
        model.addAttribute("operatorNamesByUnit", operatorNamesByUnit);

        if (editId != null) {
            units.stream().filter(u -> u.getId().equals(editId)).findFirst().ifPresent(u -> {
                List<String> supervisorIds = operationalUnitService.getSupervisorIds(u.getId());
                List<String> operatorIds = operationalUnitService.getOperatorIds(u.getId());
                model.addAttribute("editEntity", u);
                model.addAttribute("editSupervisorCsv", UserPickerHelper.toCsv(supervisorIds));
                model.addAttribute("editOperatorCsv", UserPickerHelper.toCsv(operatorIds));
            });
        }
        return "operational-units";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/operational-units')")
    public String create(@ModelAttribute OperationalUnit unit,
                         @RequestParam(required = false) List<String> supervisorIds,
                         @RequestParam(required = false) List<String> operatorIds,
                         RedirectAttributes ra) {
        try {
            operationalUnitService.create(unit, supervisorIds, operatorIds);
            ra.addFlashAttribute("successMessage", "واحد عملیاتی با موفقیت ایجاد شد.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/operational-units";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/operational-units/{id}')")
    public String update(@PathVariable String id,
                         @ModelAttribute OperationalUnit form,
                         @RequestParam(required = false) List<String> supervisorIds,
                         @RequestParam(required = false) List<String> operatorIds,
                         RedirectAttributes ra) {
        try {
            operationalUnitService.update(id, form, supervisorIds, operatorIds);
            ra.addFlashAttribute("successMessage", "واحد عملیاتی با موفقیت ویرایش شد.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/operational-units";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/operational-units/{id}/delete')")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        try {
            operationalUnitService.delete(id);
            ra.addFlashAttribute("successMessage", "واحد عملیاتی با موفقیت حذف شد.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/operational-units";
    }
}
