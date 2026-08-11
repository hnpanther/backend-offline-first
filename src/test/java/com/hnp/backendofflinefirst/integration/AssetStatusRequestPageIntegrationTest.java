package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AssetStatusChangeRequest;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusChangeRequestRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The actions column of the asset status request queue.
 *
 * <p>Deciding a request means judging it against what the asset has already been through, so the
 * row carries a direct link to that asset's timeline. Two things about it are worth pinning.
 *
 * <p><b>It is gated on the report's own authority</b>, not on the queue's. Offering a link the
 * destination would then refuse is worse than offering none: the person clicks, lands on the
 * dashboard with an access error, and has no idea which of their permissions is at fault.
 *
 * <p><b>The cell renders for everyone who can open the page.</b> The whole {@code <td>} used to
 * sit behind the decide authority, so a viewer without it lost the cell entirely — a row one
 * column short of its header, and nowhere to put anything a viewer *is* allowed to do.
 *
 * <p>Every case runs as an admin so unit scope is unrestricted and only the <em>authorities</em>
 * differ. A scoped user with no visible assets sees an empty table, which would make the
 * "no link" assertions pass without proving anything about the gating.
 */
@Transactional
class AssetStatusRequestPageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetStatusChangeRequestRepository requestRepository;
    @Autowired AssetHierarchyService hierarchyService;

    MockMvc mockMvc;
    Long assetId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        seed();
    }

    @Test
    @WithAppUser(username = "req-admin", roles = "ADMIN",
            authorities = {"GET:/asset-status-requests", "POST:/asset-status-requests/{id}/decide", "GET:/reports"})
    void aDeciderGetsALinkStraightToTheAssetTimeline() throws Exception {
        String html = mockMvc.perform(get("/asset-status-requests"))
                .andExpect(status().isOk())
                .andExpect(view().name("asset-status-requests"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/reports/asset-history?assetId=" + assetId);
        assertThat(html).contains("bi-clock-history");
        // And the decide controls are still there — the link must not have displaced them.
        assertThat(html).contains("bi-check2-circle");
    }

    @Test
    @WithAppUser(username = "req-viewer", roles = "ADMIN",
            authorities = {"GET:/asset-status-requests", "GET:/reports"})
    void aViewerWhoCannotDecideStillGetsTheCellAndTheHistoryLink() throws Exception {
        String html = mockMvc.perform(get("/asset-status-requests"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/reports/asset-history?assetId=" + assetId);
        // Nothing they may not do: no approve, no reject, no undo.
        assertThat(html).doesNotContain("bi-check2-circle");
    }

    @Test
    @WithAppUser(username = "req-no-reports", roles = "ADMIN",
            authorities = {"GET:/asset-status-requests", "POST:/asset-status-requests/{id}/decide"})
    void withoutTheReportsAuthorityTheLinkIsNotOfferedAtAll() throws Exception {
        String html = mockMvc.perform(get("/asset-status-requests"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A dead end dressed as a button is worse than no button.
        assertThat(html).doesNotContain("/reports/asset-history");
        // The rest of the row is unaffected.
        assertThat(html).contains("bi-check2-circle");
    }

    // -----------------------------------------------------------------------

    private void seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        Location location = new Location();
        location.setCode("ASR-LOC-" + nano);
        location.setName("Request Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location);

        SubFunction sf = new SubFunction();
        sf.setCode("ASR-SF-" + nano);
        sf.setName("Request Sub");
        sf.setTag("NFC-ASR-" + nano);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("ASR-A-" + nano);
        asset.setAssetName("Request Pump");
        asset.setSubFunctionId(sf.getId());
        asset.setActive(true);
        asset.setStatus("IN_SERVICE");
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetId = assetEntryRepository.save(asset).getId();

        // Pending, and the only request for this asset, so the decide controls are all offered.
        AssetStatusChangeRequest request = new AssetStatusChangeRequest();
        request.setAssetId(assetId);
        request.setPreviousStatus("IN_SERVICE");
        request.setRequestedStatus("OFF");
        request.setStatus(AssetStatusRequestStatus.PENDING);
        request.setSource(AssetStatusSource.MANUAL);
        request.setReason("Test request");
        // requestedByUserId is deliberately left null: a pool sheet with no assignee that
        // auto-submits at its deadline raises exactly this, and looking a null id up in an
        // immutable empty map used to 500 the whole page.
        request.setRequestedAt(now);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        requestRepository.save(request);
    }
}
