package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.MasterDataResponse;
import com.hnp.backendofflinefirst.service.MasterDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/master-data")
@RequiredArgsConstructor
public class MasterDataController {

    private final MasterDataService masterDataService;

    @GetMapping
    public MasterDataResponse getMasterData(@RequestParam(required = false) Long since) {
        return masterDataService.getMasterData(since);
    }
}
