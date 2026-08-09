package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AssetStatusHistory;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusHistoryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The asset history <em>page</em>: who may open it for a given asset, and what it renders.
 *
 * <p>Two things are pinned here that a service-level test cannot reach.
 *
 * <p><b>Scope.</b> The page must use the <em>reporting</em> scope — responsibility through a log
 * sheet — not location ownership. A supervisor of the unit that owns the sheet sees the history;
 * a supervisor of an unrelated unit is refused. Getting this wrong in either direction is
 * serious: too narrow and a supervisor cannot review work they are accountable for, too wide and
 * they read another unit's equipment records.
 *
 * <p><b>Rendering.</b> A status of literally {@code OFF} must appear. Thymeleaf evaluates the
 * strings {@code "off"}, {@code "false"} and {@code "no"} as boolean false, so writing
 * {@code th:if="${status}"} silently hides exactly those values — and «خارج از سرویس / OFF» is
 * the status an operator most needs to see. Found on a live page, not by any unit test.
 */
@Transactional
class AssetHistoryPageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetStatusHistoryRepository statusHistoryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetHierarchyService hierarchyService;

    /** WithAppUserSecurityContextFactory always authenticates as user id 1. */
    private static final long PRINCIPAL_USER_ID = 1L;

    MockMvc mockMvc;
    Long assetId;
    Long responsibleUnitId;
    Long otherUnitId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        seed();
    }

    @Test
    @WithAppUser(username = "history-admin", roles = "ADMIN", authorities = "GET:/reports")
    void anAdminSeesTheTimelineAndTheLiteralOffStatusIsRendered() throws Exception {
        String html = mockMvc.perform(get("/reports/asset-history").param("assetId", String.valueOf(assetId)))
                .andExpect(status().isOk())
                .andExpect(view().name("reports/asset-history"))
                .andExpect(model().attributeExists("selectedAsset"))
                .andReturn().getResponse().getContentAsString();

        // The regression that motivated this test: "OFF" must survive Thymeleaf's truthiness
        // rules and not be replaced by the "not recorded" placeholder.
        assertThat(html).contains("OFF");
        assertThat(html).contains("درخواست دستی");
    }

    @Test
    @WithAppUser(username = "history-supervisor-ok", roles = "SUPERVISOR", authorities = "GET:/reports")
    void aSupervisorOfTheUnitResponsibleForTheSheetMaySeeTheHistory() throws Exception {
        linkSupervisor(responsibleUnitId);

        mockMvc.perform(get("/reports/asset-history").param("assetId", String.valueOf(assetId)))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("selectedAsset"))
                .andExpect(model().attributeDoesNotExist("assetAccessDenied"));
    }

    @Test
    @WithAppUser(username = "history-supervisor-other", roles = "SUPERVISOR", authorities = "GET:/reports")
    void aSupervisorOfAnUnrelatedUnitIsRefusedTheAssetRatherThanShownItsHistory() throws Exception {
        linkSupervisor(otherUnitId);

        mockMvc.perform(get("/reports/asset-history").param("assetId", String.valueOf(assetId)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("assetAccessDenied", true))
                // Refused means refused: no asset, and no events leaking through the model.
                .andExpect(model().attributeDoesNotExist("selectedAsset"))
                .andExpect(model().attribute("events", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @WithAppUser(username = "history-nobody", roles = "OPERATOR")
    void withoutTheReportsAuthorityThePageIsRefused() throws Exception {
        // The web chain bounces a denied page to "/" with an error flash; a 403 body is the
        // /api/** convention, not this one (see EndpointSecurityTest for the same shape).
        mockMvc.perform(get("/reports/asset-history").param("assetId", String.valueOf(assetId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/"));
    }

    // -----------------------------------------------------------------------

    /**
     * Makes the authenticated principal a supervisor of {@code unitId}.
     *
     * <p>The id is not a choice: {@code WithAppUserSecurityContextFactory} always mints the
     * principal with id 1, and unit scope is resolved from the authenticated user's id — so the
     * link has to hang off that same id or the test would silently prove nothing.
     */
    private void linkSupervisor(Long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitId);
        link.setUserId(PRINCIPAL_USER_ID);
        unitSupervisorRepository.save(link);
    }

    /**
     * An asset whose location belongs to nobody in particular, reachable only through a log
     * sheet the responsible unit owns — the configuration a template with
     * {@code restrict_scope_to_unit = false} produces, and the one where getting scope wrong
     * actually bites.
     */
    private void seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit responsible = new OperationalUnit();
        responsible.setCode("AHP-RESP-" + nano);
        responsible.setName("Responsible Unit");
        responsible.setCreatedAt(now);
        responsible.setUpdatedAt(now);
        responsibleUnitId = operationalUnitRepository.save(responsible).getId();

        OperationalUnit other = new OperationalUnit();
        other.setCode("AHP-OTHER-" + nano);
        other.setName("Unrelated Unit");
        other.setCreatedAt(now);
        other.setUpdatedAt(now);
        otherUnitId = operationalUnitRepository.save(other).getId();

        Location location = new Location();
        location.setCode("AHP-LOC-" + nano);
        location.setName("History Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location);

        SubFunction sf = new SubFunction();
        sf.setCode("AHP-SF-" + nano);
        sf.setName("History Sub");
        sf.setTag("NFC-AHP-" + nano);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AHP-A-" + nano);
        asset.setAssetName("History Pump");
        asset.setSubFunctionId(sf.getId());
        asset.setActive(true);
        asset.setStatus("OFF");
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetId = assetEntryRepository.save(asset).getId();

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("History Sheet");
        sheet.setOperationalUnitId(responsibleUnitId);
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(com.hnp.backendofflinefirst.domain.GenerationMode.MANUAL);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.save(sheet);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheet.getId());
        entry.setAssetId(assetId);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        logSheetEntryRepository.save(entry);

        AssetStatusHistory row = new AssetStatusHistory();
        row.setAssetId(assetId);
        row.setOldStatus("IN_SERVICE");
        row.setNewStatus("OFF");
        row.setChangeType(AssetStatusChangeType.APPLIED);
        row.setSource(AssetStatusSource.MANUAL);
        row.setChangedAt(now);
        statusHistoryRepository.save(row);
    }
}
