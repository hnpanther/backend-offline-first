package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asset list page should render class/sub-function labels from preloaded maps
 * (no per-row {@code @labels.*} lookups).
 */
class AssetEntryListLabelsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired SubFunctionRepository subFunctionRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-entries")
    void listPageShowsBatchedClassAndSubFunctionLabels() throws Exception {
        long now = System.currentTimeMillis();

        AssetClass assetClass = new AssetClass();
        assetClass.setName("کلاس-پمپ-لیبل");
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SF-LBL-" + now);
        subFunction.setName("تابع-پمپاژ-لیبل");
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        subFunction = subFunctionRepository.saveAndFlush(subFunction);

        AssetEntry first = new AssetEntry();
        first.setAssetCode("AST-LBL-A-" + now);
        first.setAssetName("Asset A");
        first.setClassId(assetClass.getId());
        first.setSubFunctionId(subFunction.getId());
        first.setCreatedAt(now);
        first.setUpdatedAt(now);
        assetEntryRepository.saveAndFlush(first);

        AssetEntry second = new AssetEntry();
        second.setAssetCode("AST-LBL-B-" + now);
        second.setAssetName("Asset B");
        second.setClassId(assetClass.getId());
        second.setSubFunctionId(subFunction.getId());
        second.setCreatedAt(now);
        second.setUpdatedAt(now);
        assetEntryRepository.saveAndFlush(second);

        mockMvc.perform(get("/asset-entries").param("q", "AST-LBL"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("assetClassLabelById", "subFunctionLabelById"))
                .andExpect(content().string(containsString("کلاس-پمپ-لیبل")))
                .andExpect(content().string(containsString("تابع-پمپاژ-لیبل")))
                .andExpect(content().string(containsString("AST-LBL-A-" + now)))
                .andExpect(content().string(containsString("AST-LBL-B-" + now)));
    }
}
