package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.repository.DataRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/records")
@RequiredArgsConstructor
public class RecordWebController {

    private final DataRecordRepository dataRecordRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/records')")
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String asset,
                       Model model) {
        model.addAttribute("activePage", "records");
        var records = dataRecordRepository.findAll();
        if (status != null && !status.isBlank()) {
            records = records.stream()
                    .filter(r -> status.equals(r.getRecordStatus()))
                    .toList();
        }
        if (asset != null && !asset.isBlank()) {
            records = records.stream()
                    .filter(r -> asset.equalsIgnoreCase(r.getAssetName()))
                    .toList();
        }
        model.addAttribute("records", records);
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterAsset", asset);
        return "records";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/records/{id}')")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("activePage", "records");
        dataRecordRepository.findById(id).ifPresent(r -> {
            model.addAttribute("record", r);
            try {
                String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(r.getFormData());
                model.addAttribute("formDataJson", pretty);
            } catch (Exception e) {
                model.addAttribute("formDataJson", "{}");
            }
        });
        return "record-detail";
    }
}
