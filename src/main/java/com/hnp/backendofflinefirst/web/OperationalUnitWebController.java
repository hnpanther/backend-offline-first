package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.service.OperationalUnitService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.ImportWebSupport;
import com.hnp.backendofflinefirst.util.ExcelUtils;
import com.hnp.backendofflinefirst.util.UserPickerHelper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
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
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/operational-units')")
    public String list(@RequestParam(required = false) Long editId, Model model) {
        model.addAttribute("activePage", "operational-units");

        List<OperationalUnit> units = operationalUnitService.findAll();
        List<User> users = userService.findAll();

        Map<Long, String> unitNameById = units.stream()
                .collect(Collectors.toMap(OperationalUnit::getId,
                        u -> u.getName() != null ? u.getName() : u.getCode()));
        Map<Long, String> userNameById = users.stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername()));

        Map<Long, List<String>> supervisorNamesByUnit = new HashMap<>();
        Map<Long, List<String>> operatorNamesByUnit = new HashMap<>();
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
                List<Long> supervisorIds = operationalUnitService.getSupervisorIds(u.getId());
                List<Long> operatorIds = operationalUnitService.getOperatorIds(u.getId());
                model.addAttribute("editEntity", u);
                model.addAttribute("editSupervisorCsv", UserPickerHelper.toCsv(supervisorIds));
                model.addAttribute("editOperatorCsv", UserPickerHelper.toCsv(operatorIds));
            });
        }
        return "operational-units";
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET:/operational-units')")
    public void export(HttpServletResponse response) throws IOException {
        excelExportService.exportOperationalUnits(response);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('POST:/operational-units/import')")
    public String importExcel(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importOperationalUnits(file);
            ImportWebSupport.applyImportResult(result, ra);
        } catch (Exception e) {
            ImportWebSupport.applyFileError(e, ra);
        }
        return "redirect:/operational-units";
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('GET:/operational-units/import-template')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        ExcelUtils.writeTemplate(response, "operational-units-template.xlsx",
                new String[]{"code", "name", "parentCode"});
    }

    @PostMapping("/import-staff")
    @PreAuthorize("hasAuthority('POST:/operational-units/import-staff')")
    public String importStaff(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            ImportResult result = excelImportService.importUnitStaff(file);
            ImportWebSupport.applyImportResult(result, ra);
        } catch (Exception e) {
            ImportWebSupport.applyFileError(e, ra);
        }
        return "redirect:/operational-units";
    }

    @GetMapping("/import-staff-template")
    @PreAuthorize("hasAuthority('GET:/operational-units/import-staff-template')")
    public void downloadStaffTemplate(HttpServletResponse response) throws IOException {
        ExcelUtils.writeTemplate(response, "operational-units-staff-template.xlsx",
                new String[]{"unitCode", "roleType", "username"});
    }

    @PostMapping
    @PreAuthorize("hasAuthority('POST:/operational-units')")
    public String create(@ModelAttribute OperationalUnit unit,
                         @RequestParam(required = false) List<Long> supervisorIds,
                         @RequestParam(required = false) List<Long> operatorIds,
                         RedirectAttributes ra) {
        try {
            operationalUnitService.create(unit, supervisorIds, operatorIds);
            ra.addFlashAttribute("successMessage", FaMessages.unitCreated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/operational-units";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('POST:/operational-units/{id}')")
    public String update(@PathVariable Long id,
                         @ModelAttribute OperationalUnit form,
                         @RequestParam(required = false) List<Long> supervisorIds,
                         @RequestParam(required = false) List<Long> operatorIds,
                         RedirectAttributes ra) {
        try {
            operationalUnitService.update(id, form, supervisorIds, operatorIds);
            ra.addFlashAttribute("successMessage", FaMessages.unitUpdated());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/operational-units";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('POST:/operational-units/{id}/delete')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            operationalUnitService.delete(id);
            ra.addFlashAttribute("successMessage", FaMessages.unitDeleted());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", ErrorTranslator.toFa(e.getMessage()));
        }
        return "redirect:/operational-units";
    }
}
