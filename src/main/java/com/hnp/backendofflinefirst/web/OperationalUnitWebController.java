package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.ExcelExportService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.service.MasterDataOptionsService;
import com.hnp.backendofflinefirst.service.OperationalUnitService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import com.hnp.backendofflinefirst.ui.ImportWebSupport;
import com.hnp.backendofflinefirst.ui.WebBulkDeleteSupport;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.ExcelUtils;
import com.hnp.backendofflinefirst.util.UserPickerHelper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/operational-units")
@RequiredArgsConstructor
public class OperationalUnitWebController {

    private final OperationalUnitService operationalUnitService;
    private final OperationalUnitRepository operationalUnitRepository;
    private final UserService userService;
    private final MasterDataOptionsService masterDataOptionsService;
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/operational-units')")
    public String list(@RequestParam(required = false) Long editId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        int pageSize = size != null ? size : WebListSupport.DEFAULT_SIZE;
        Pageable pageable = WebListSupport.pageable(page, pageSize);
        var result = WebListSupport.pagedList(q, pageable,
                operationalUnitRepository::findAll,
                operationalUnitRepository::search);
        model.addAttribute("activePage", "operational-units");

        List<OperationalUnit> units = result.getContent();
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
        WebListSupport.addPagination(model, result, q, page, pageSize);

        if (editId != null) {
            operationalUnitRepository.findById(editId).ifPresent(u -> {
                List<Long> supervisorIds = operationalUnitService.getSupervisorIds(u.getId());
                List<Long> operatorIds = operationalUnitService.getOperatorIds(u.getId());
                model.addAttribute("editEntity", u);
                model.addAttribute("editSupervisorCsv", UserPickerHelper.toCsv(supervisorIds));
                model.addAttribute("editOperatorCsv", UserPickerHelper.toCsv(operatorIds));
                // The remote parent picker needs the saved parent's label to render it preselected.
                model.addAttribute("editParentOption", u.getParentId() == null ? null
                        : masterDataOptionsService.operationalUnitOptionsByIds(List.of(u.getParentId()))
                                .stream().findFirst().orElse(null));
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

    /**
     * Searchable parent picker. The form previously rendered {@code ${units}}, which is only the
     * current page of the list — with hundreds of units most parents were simply not offered.
     */
    @GetMapping("/options/parents")
    @PreAuthorize("hasAuthority('GET:/operational-units')")
    @ResponseBody
    public List<SelectOptionDto> parentOptions(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Long excludeId,
                                               @RequestParam(defaultValue = "30") int limit) {
        return masterDataOptionsService.searchOperationalUnitParents(q, excludeId, limit);
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

    /**
     * Same permission as the single-row delete, deliberately: this deletes the same rows under
     * the same guards, so a separate authority would be a second thing to grant that means the
     * same thing — and one of the two would eventually be granted without the other.
     */
    @PostMapping("/delete-bulk")
    @PreAuthorize("hasAuthority('POST:/operational-units/{id}/delete')")
    public String deleteBulk(@RequestParam(required = false) List<Long> ids,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page,
                             RedirectAttributes ra) {
        WebBulkDeleteSupport.applyResult(operationalUnitService.deleteAll(ids), ra, "واحد عملیاتی");
        return WebBulkDeleteSupport.listRedirect("/operational-units", q, page);
    }
}
