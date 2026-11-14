package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.LookupImportMode;
import com.hnp.backendofflinefirst.entity.LookupItem;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.entity.LookupListImport;
import com.hnp.backendofflinefirst.repository.LookupItemRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import com.hnp.backendofflinefirst.service.LookupImportService;
import com.hnp.backendofflinefirst.service.LookupImportService.Counts;
import com.hnp.backendofflinefirst.service.LookupImportService.Preview;
import com.hnp.backendofflinefirst.service.LookupListService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Excel import of a lookup list (V22, roadmap §31 «The Excel import is the riskiest writer»).
 *
 * <p>What is pinned is what a successful import cannot show: that a partial file in full-replace
 * mode is stopped before it retires everybody it left out; that one bad row stops the whole file
 * rather than counting as «absent»; that a column left out of a file leaves its values alone while
 * an emptied cell clears them; that a code's leading zeros survive; and that an apply refuses when
 * the list moved under the preview.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {"GET:/lookup-lists", "POST:/lookup-lists/{id}/import"})
class LookupImportIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LookupListService listService;
    @Autowired LookupImportService importService;
    @Autowired LookupListRepository listRepository;
    @Autowired LookupItemRepository itemRepository;
    @Autowired ImportStorageProperties storageProperties;

    MockMvc mockMvc;
    LookupList list;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
        list = listService.createList("imp_" + tag, "پرسنل", null, null);
        listService.addColumn(list.getId(), "unit", "واحد", true, true, true);
        listService.addColumn(list.getId(), "phone", "تلفن", false, false, false);
    }

    /**
     * Previews that were never applied or discarded leave their file on disk — by design, swept
     * after a day. A test run must not leave them in the developer's {@code data/imports}.
     */
    @AfterEach
    void removeThisListsPreviewFiles() throws IOException {
        java.nio.file.Path dir = java.nio.file.Path.of(storageProperties.getStoragePath())
                .toAbsolutePath().normalize().resolve("lookup-previews");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (var files = java.nio.file.Files.newDirectoryStream(dir, list.getId() + "-*")) {
            for (java.nio.file.Path f : files) {
                java.nio.file.Files.deleteIfExists(f);
            }
        }
    }

    // ---------------------------------------------------------------------- helpers

    /** A workbook whose first row is {@code headers}; a cell value starting with '=' is written as a formula. */
    private static byte[] xlsx(List<String> headers, List<List<Object>> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("items");
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                head.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    Object v = values.get(c);
                    if (v == null) {
                        continue;
                    }
                    Cell cell = row.createCell(c);
                    if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else if (v.toString().startsWith("=")) {
                        cell.setCellFormula(v.toString().substring(1));
                    } else {
                        cell.setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String upload(byte[] bytes) throws IOException {
        return importService.store(list.getId(), new MockMultipartFile("file", "personnel.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
    }

    private Preview preview(byte[] bytes, LookupImportMode mode) throws IOException {
        return importService.preview(list.getId(), upload(bytes), mode);
    }

    private LookupListImport apply(Preview p) {
        return importService.apply(list.getId(), p.token(), p.mode(), p.sha256(), p.counts(),
                p.confirmationRequired() ? p.counts().deactivated() : null, 1L);
    }

    private LookupItem item(String code) {
        return itemRepository.findByListIdAndCodeIgnoreCase(list.getId(), code).orElseThrow();
    }

    private void seedItems(int n) {
        for (int i = 1; i <= n; i++) {
            listService.createItem(list.getId(), "P" + i, "نفر " + i, Map.of("unit", "واحد", "phone", "0912" + i), null, null);
        }
    }

    // ---------------------------------------------------------------------- add and update

    @Test
    void addAndUpdateTouchesOnlyWhatTheFileNames() throws IOException {
        seedItems(3);
        Preview p = preview(xlsx(List.of("code", "label", "unit"),
                List.of(List.of("P1", "نفر یک", "تعمیرات"),
                        List.of("P4", "نفر چهار", "ایمنی"))), LookupImportMode.ADD_UPDATE);

        assertThat(p.counts()).isEqualTo(new Counts(1, 1, 0, 0, 0));
        assertThat(p.labelChanges()).extracting(LookupImportService.LabelChange::code).containsExactly("P1");
        long before = listRepository.currentRevision(list.getId());
        apply(p);

        // One import, one revision — devices see one change, not one per row.
        assertThat(listRepository.currentRevision(list.getId())).isEqualTo(before + 1);
        assertThat(item("P1").getLabel()).isEqualTo("نفر یک");
        assertThat(item("P1").getAttributes()).containsEntry("unit", "تعمیرات")
                // «phone» is not a column of the file, so it is untouched.
                .containsEntry("phone", "09121");
        assertThat(item("P2").getLabel()).isEqualTo("نفر 2");
        assertThat(item("P4").isActive()).isTrue();
    }

    @Test
    void anEmptiedCellClearsTheValueWhereAnAbsentColumnKeepsIt() throws IOException {
        seedItems(1);
        apply(preview(xlsx(List.of("code", "label", "phone"), List.of(List.of("P1", "نفر 1", ""))),
                LookupImportMode.ADD_UPDATE));
        assertThat(item("P1").getAttributes()).doesNotContainKey("phone").containsEntry("unit", "واحد");
    }

    @Test
    void anUnchangedFileChangesNothingAndSaysSo() throws IOException {
        seedItems(2);
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("P1", "نفر 1"), List.of("P2", "نفر 2"))),
                LookupImportMode.ADD_UPDATE);
        assertThat(p.changesNothing()).isTrue();
        assertThatThrownBy(() -> apply(p)).hasMessage("The import file changes nothing in this lookup list.");
    }

    // ---------------------------------------------------------------------- full replace

    @Test
    void aPartialFileInFullReplaceNeedsItsDeactivationsConfirmed() throws IOException {
        seedItems(10);
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("P1", "نفر 1"))), LookupImportMode.FULL_REPLACE);

        assertThat(p.counts().deactivated()).isEqualTo(9);
        assertThat(p.confirmationRequired()).isTrue();
        assertThatThrownBy(() -> importService.apply(list.getId(), p.token(), p.mode(), p.sha256(), p.counts(), null, 1L))
                .hasMessage("Confirm the number of items this import deactivates.");
        assertThatThrownBy(() -> importService.apply(list.getId(), p.token(), p.mode(), p.sha256(), p.counts(), 8, 1L))
                .hasMessage("Confirm the number of items this import deactivates.");
        assertThat(item("P2").isActive()).isTrue();

        importService.apply(list.getId(), p.token(), p.mode(), p.sha256(), p.counts(), 9, 1L);
        assertThat(item("P2").isActive()).isFalse();
        assertThat(item("P2").getDeactivatedAt()).isNotNull();
        assertThat(item("P1").isActive()).isTrue();
    }

    @Test
    void fullReplaceReactivatesWhatTheFileNamesAndBelowTheThresholdAsksNothing() throws IOException {
        seedItems(20);
        listService.setItemActive(list.getId(), item("P20").getId(), false);
        java.util.List<List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            if (i != 5) {
                rows.add(List.of("P" + i, "نفر " + i));
            }
        }
        Preview p = preview(xlsx(List.of("code", "label"), rows), LookupImportMode.FULL_REPLACE);
        assertThat(p.counts().deactivated()).isEqualTo(1);
        assertThat(p.counts().reactivated()).isEqualTo(1);
        assertThat(p.confirmationRequired()).isFalse();
        apply(p);
        assertThat(item("P20").isActive()).isTrue();
        assertThat(item("P5").isActive()).isFalse();
    }

    @Test
    void oneBadRowStopsTheWholeFileRatherThanCountingAsAbsent() throws IOException {
        seedItems(3);
        Preview p = preview(xlsx(List.of("code", "label", "active"),
                List.of(List.of("P1", "نفر 1", "فعال"),
                        List.of("P2", "", "فعال"),
                        List.of("P3", "نفر 3", "شاید"))), LookupImportMode.FULL_REPLACE);
        assertThat(p.applicable()).isFalse();
        assertThat(p.errors()).extracting(LookupImportService.RowError::row).containsExactly(3, 4);
        assertThatThrownBy(() -> apply(p)).hasMessage("The import file has errors; nothing was applied.");
        assertThat(item("P2").isActive()).isTrue();
    }

    // ---------------------------------------------------------------------- the file's own rules

    @Test
    void anUnknownHeaderRefusesTheFileInsteadOfDroppingAColumn() {
        assertThatThrownBy(() -> preview(xlsx(List.of("code", "label", "unti"), List.of(List.of("P1", "x", "y"))),
                LookupImportMode.ADD_UPDATE))
                .hasMessage("Unknown column in the import file: unti");
    }

    @Test
    void duplicateCodesInOneFileNameTheEarlierRow() throws IOException {
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("A1", "x"), List.of("a1", "y"))),
                LookupImportMode.ADD_UPDATE);
        assertThat(p.errors()).extracting(LookupImportService.RowError::message)
                .containsExactly("Duplicate code in the file: a1 (row 2)");
    }

    @Test
    void formulasAreRefused() throws IOException {
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("A1", "=1+1"))), LookupImportMode.ADD_UPDATE);
        assertThat(p.errors()).extracting(LookupImportService.RowError::message)
                .containsExactly("Formulas are not accepted in an import file.");
    }

    @Test
    void codesKeepTheirZerosAndLongNumbersAreWrittenInFull() throws IOException {
        Preview p = preview(xlsx(List.of("code", "label"),
                List.of(List.of("00123", "متنی"), List.of(12345678901234L, "عددی بلند"), List.of("۰۰۹", "فارسی"))),
                LookupImportMode.ADD_UPDATE);
        apply(p);
        assertThat(item("00123").getLabel()).isEqualTo("متنی");
        assertThat(item("12345678901234").getLabel()).isEqualTo("عددی بلند");
        assertThat(item("009").getLabel()).isEqualTo("فارسی");
    }

    @Test
    void anAnonymisedItemCannotBeRevivedByAFile() throws IOException {
        seedItems(1);
        listService.anonymiseItem(list.getId(), item("P1").getId());
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("P1", "نام برگشته"))), LookupImportMode.ADD_UPDATE);
        assertThat(p.errors()).extracting(LookupImportService.RowError::message)
                .containsExactly("An anonymised lookup item cannot be imported: P1");
    }

    // ---------------------------------------------------------------------- apply's own guards

    @Test
    void anApplyRefusesWhenTheListMovedUnderThePreview() throws IOException {
        seedItems(2);
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("P1", "نام تازه"))), LookupImportMode.ADD_UPDATE);
        listService.updateItem(list.getId(), item("P1").getId(), null, "نام تازه", Map.of(), null, null);
        assertThatThrownBy(() -> apply(p))
                .hasMessage("The lookup list changed since the preview. Preview the file again.");
    }

    @Test
    void anApplyRefusesAHashThatIsNotThePreviewedFile() throws IOException {
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("Z1", "x"))), LookupImportMode.ADD_UPDATE);
        assertThatThrownBy(() -> importService.apply(list.getId(), p.token(), p.mode(), "00" + p.sha256().substring(2),
                p.counts(), null, 1L))
                .hasMessage("The import file is not the one that was previewed.");
    }

    @Test
    void anAppliedImportIsRecordedAndItsFileIsGone() throws IOException {
        Preview p = preview(xlsx(List.of("code", "label"), List.of(List.of("R1", "x"), List.of("R2", "y"))),
                LookupImportMode.ADD_UPDATE);
        LookupListImport done = apply(p);
        assertThat(done.getAdded()).isEqualTo(2);
        assertThat(done.getActorUserId()).isEqualTo(1L);
        assertThat(done.getRevisionAfter()).isEqualTo(listRepository.currentRevision(list.getId()));
        assertThat(importService.recentImports(list.getId())).extracting(LookupListImport::getId).contains(done.getId());
        assertThatThrownBy(() -> importService.preview(list.getId(), p.token(), p.mode()))
                .hasMessageStartingWith("The import preview has expired.");
    }

    // ---------------------------------------------------------------------- through the panel

    @Test
    void uploadPreviewAndApplyThroughThePanel() throws Exception {
        byte[] bytes = xlsx(List.of("code", "label", "unit"), List.of(List.of("W1", "وحید", "ایمنی")));
        MvcResult uploaded = mockMvc.perform(multipart("/lookup-lists/" + list.getId() + "/import")
                        .file(new MockMultipartFile("file", "پرسنل.xlsx", "application/octet-stream", bytes))
                        .param("mode", "ADD_UPDATE").with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        String previewUrl = uploaded.getResponse().getRedirectedUrl();
        assertThat(previewUrl).contains("/import/").contains("mode=ADD_UPDATE");

        String html = mockMvc.perform(get(previewUrl)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"pageContent\"").contains("پرسنل.xlsx").contains("وحید").doesNotContain(" th:");

        String token = previewUrl.substring(previewUrl.indexOf("/import/") + "/import/".length(), previewUrl.indexOf('?'));
        Preview p = importService.preview(list.getId(), token, LookupImportMode.ADD_UPDATE);
        mockMvc.perform(post("/lookup-lists/" + list.getId() + "/import/" + token + "/apply").with(csrf())
                        .param("mode", "ADD_UPDATE").param("sha256", p.sha256())
                        .param("added", "1").param("updated", "0").param("unchanged", "0")
                        .param("deactivated", "0").param("reactivated", "0"))
                .andExpect(status().is3xxRedirection());
        assertThat(item("W1").getAttributes()).containsEntry("unit", "ایمنی");
    }

    @Test
    void anUnreadableFileIsDiscardedWithAReason() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/lookup-lists/" + list.getId() + "/import")
                        .file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", "not excel".getBytes()))
                        .param("mode", "FULL_REPLACE").with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        mockMvc.perform(get(uploaded.getResponse().getRedirectedUrl()))
                .andExpect(status().is3xxRedirection());
        String token = uploaded.getResponse().getRedirectedUrl().replaceAll(".*/import/([^?]+).*", "$1");
        assertThatThrownBy(() -> importService.preview(list.getId(), token, LookupImportMode.FULL_REPLACE))
                .hasMessageStartingWith("The import preview has expired.");
    }

    @Test
    void theTemplateAndTheExportCarryTheHeadersAndKeepCodesAsText() throws Exception {
        listService.createItem(list.getId(), "007", "باند", Map.of("unit", "حراست"), null, null);
        for (String url : List.of("/lookup-lists/" + list.getId() + "/import-template",
                "/lookup-lists/" + list.getId() + "/export")) {
            byte[] body = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(body))) {
                Row head = wb.getSheetAt(0).getRow(0);
                assertThat(List.of(head.getCell(0).getStringCellValue(), head.getCell(1).getStringCellValue(),
                        head.getCell(2).getStringCellValue(), head.getCell(3).getStringCellValue()))
                        .containsExactly("code", "label", "unit", "phone");
                CellStyle codeStyle = wb.getSheetAt(0).getColumnStyle(0);
                assertThat(codeStyle.getDataFormatString()).isEqualTo("@");
                if (url.endsWith("/export")) {
                    assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("007");
                }
            }
        }
    }
}
