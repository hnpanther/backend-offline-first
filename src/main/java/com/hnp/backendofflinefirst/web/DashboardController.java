package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
@RequiredArgsConstructor
public class DashboardController {

    private final LocationRepository locationRepository;
    private final AssetClassRepository assetClassRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final DataRecordRepository dataRecordRepository;
    private final LogSheetRepository logSheetRepository;
    private final SubFunctionRepository subFunctionRepository;
    private final UserRepository userRepository;
    private final OperationalUnitRepository operationalUnitRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('GET:/')")
    public String dashboard(Model model) {
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("locationCount", locationRepository.count());
        model.addAttribute("assetClassCount", assetClassRepository.count());
        model.addAttribute("assetEntryCount", assetEntryRepository.count());
        model.addAttribute("recordCount", dataRecordRepository.count());
        model.addAttribute("logSheetCount", logSheetRepository.count());
        model.addAttribute("subFunctionCount", subFunctionRepository.count());
        model.addAttribute("userCount", userRepository.count());
        model.addAttribute("operationalUnitCount", operationalUnitRepository.count());
        return "index";
    }
}
