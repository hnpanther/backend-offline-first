package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportWebController {

    private final DataRecordRepository dataRecordRepository;
    private final LogSheetRepository logSheetRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/reports')")
    public String reports(Model model) {
        model.addAttribute("activePage", "reports");

        // Records by status
        Map<String, Long> recordsByStatus = dataRecordRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRecordStatus() == null ? "نامشخص" : r.getRecordStatus(),
                        Collectors.counting()
                ));
        model.addAttribute("recordsByStatus", recordsByStatus);

        // Records by asset (top 10)
        Map<String, Long> recordsByAsset = dataRecordRepository.findAll().stream()
                .filter(r -> r.getAssetName() != null)
                .collect(Collectors.groupingBy(
                        r -> r.getAssetName(),
                        Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        model.addAttribute("recordsByAsset", recordsByAsset);

        // Log sheets by status
        Map<String, Long> logSheetsByStatus = logSheetRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        s -> s.getStatus() == null ? "نامشخص" : s.getStatus(),
                        Collectors.counting()
                ));
        model.addAttribute("logSheetsByStatus", logSheetsByStatus);

        // Log sheets by template
        Map<String, Long> logSheetsByTemplate = logSheetRepository.findAll().stream()
                .filter(s -> s.getTemplateName() != null)
                .collect(Collectors.groupingBy(
                        s -> s.getTemplateName(),
                        Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        model.addAttribute("logSheetsByTemplate", logSheetsByTemplate);

        model.addAttribute("totalRecords", dataRecordRepository.count());
        model.addAttribute("totalLogSheets", logSheetRepository.count());
        return "reports";
    }
}
