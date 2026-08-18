package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The organizational unit and position on a user: schema, service, Excel import, and template.
 *
 * <p>Two things are pinned that a unit test cannot reach.
 *
 * <p><b>The columns exist and the entity maps to them.</b> `ddl-auto=validate` means a missing
 * or mistyped column stops the context from starting at all, so simply booting this test proves
 * V4 ran; the assertions then check the values survive a round trip through Postgres.
 *
 * <p><b>The Excel layout stays aligned across three files.</b> The import parser, the template
 * generator and the modal prose in users.html each carry the column order independently, and
 * they drift silently: a file still imports, it just reads one column as another. The template
 * assertions below are the tripwire — in particular that the two new columns are **appended**,
 * so a template downloaded before this change still imports correctly.
 */
class UserOrganizationFieldsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired ExcelImportService importService;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static MockMultipartFile usersSheet(String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("users");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "users.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static final String[] HEADER = {
            "username", "personnelCode", "fullName", "nationalCode", "phoneNumber", "nfcTag",
            "shift", "password", "authType", "active", "roleCodes", "orgUnit", "orgPosition"
    };

    // --- schema + service round trip ----------------------------------------

    @Test
    void bothFieldsSurviveARoundTripThroughTheDatabase() {
        long t = System.nanoTime();
        User created = userService.create("u-org-" + t, "کاربر", "PC-ORG-" + t, null, null, null, null,
                "مهندسی نگهداری و تعمیرات", "کارشناس ارشد ابزار دقیق",
                "pass123", UserAuthType.LOCAL, true, List.of());

        User reloaded = userRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getOrgUnit()).isEqualTo("مهندسی نگهداری و تعمیرات");
        assertThat(reloaded.getOrgPosition()).isEqualTo("کارشناس ارشد ابزار دقیق");

        // Straight from the column, so a mapping that quietly wrote somewhere else is caught.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT org_unit FROM users WHERE id = ?", String.class, created.getId()))
                .isEqualTo("مهندسی نگهداری و تعمیرات");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT org_position FROM users WHERE id = ?", String.class, created.getId()))
                .isEqualTo("کارشناس ارشد ابزار دقیق");
    }

    @Test
    void aUserWithoutThemIsStoredAsNullRatherThanEmptyString() {
        long t = System.nanoTime();
        User created = userService.create("u-org-none-" + t, "کاربر", "PC-ORGN-" + t, null, null, null, null,
                null, null, "pass123", UserAuthType.LOCAL, true, List.of());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT org_unit FROM users WHERE id = ?", String.class, created.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT org_position FROM users WHERE id = ?", String.class, created.getId())).isNull();
    }

    @Test
    void theyAreNotConnectedToOperationalUnitsInAnyWay() {
        // The whole reason the naming is a hazard: `orgUnit` is free text on the person, while
        // `operational_units` decides what they can see. A value here must never appear as a
        // unit membership.
        long t = System.nanoTime();
        User created = userService.create("u-org-scope-" + t, "کاربر", "PC-ORGS-" + t, null, null, null, null,
                "واحد عملیاتی SOC", null, "pass123", UserAuthType.LOCAL, true, List.of());

        Integer supervisorRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM unit_supervisors WHERE user_id = ?", Integer.class, created.getId());
        Integer operatorRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM unit_operators WHERE user_id = ?", Integer.class, created.getId());
        assertThat(supervisorRows).isZero();
        assertThat(operatorRows).isZero();
    }

    // --- Excel import --------------------------------------------------------

    @Test
    void theImportReadsBothColumnsFromTheEndOfTheRow() throws Exception {
        long t = System.nanoTime();
        String username = "imp-org-" + t;

        ImportResult result = importService.importUsers(usersSheet(HEADER,
                new String[]{username, "PC-IMP-" + t, "کاربر ایمپورت", null, null, null,
                        "شیفت A", "pass123", "LOCAL", "true", null, "واحد برق", "تکنسین برق"}));

        assertThat(result.getErrors()).isEmpty();
        User saved = userRepository.findByUsername(username).orElseThrow();
        assertThat(saved.getOrgUnit()).isEqualTo("واحد برق");
        assertThat(saved.getOrgPosition()).isEqualTo("تکنسین برق");
        // Everything before them must still land where it did — the point of appending.
        assertThat(saved.getShift()).isEqualTo("شیفت A");
        assertThat(saved.getFullName()).isEqualTo("کاربر ایمپورت");
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void aFileFromBeforeTheseColumnsExistedStillImports() throws Exception {
        // The compatibility guarantee that decided where the columns go. An administrator's
        // previously downloaded template has 11 columns and must keep working untouched.
        long t = System.nanoTime();
        String username = "imp-legacy-" + t;

        ImportResult result = importService.importUsers(usersSheet(
                new String[]{"username", "personnelCode", "fullName", "nationalCode", "phoneNumber",
                        "nfcTag", "shift", "password", "authType", "active", "roleCodes"},
                new String[]{username, "PC-LEG-" + t, "کاربر قدیمی", null, null, null,
                        "شیفت B", "pass123", "LOCAL", "true", null}));

        assertThat(result.getErrors()).isEmpty();
        User saved = userRepository.findByUsername(username).orElseThrow();
        assertThat(saved.getShift()).isEqualTo("شیفت B");
        assertThat(saved.getOrgUnit()).isNull();
        assertThat(saved.getOrgPosition()).isNull();
    }

    @Test
    void bothColumnsAreOptionalInTheImport() throws Exception {
        long t = System.nanoTime();
        String username = "imp-org-blank-" + t;

        ImportResult result = importService.importUsers(usersSheet(HEADER,
                new String[]{username, "PC-IMPB-" + t, "کاربر", null, null, null,
                        null, "pass123", "LOCAL", "true", null, "   ", null}));

        assertThat(result.getErrors()).isEmpty();
        User saved = userRepository.findByUsername(username).orElseThrow();
        assertThat(saved.getOrgUnit()).as("blank normalises to null").isNull();
        assertThat(saved.getOrgPosition()).isNull();
    }

    @Test
    void anOverlongValueIsReportedAsARowErrorRatherThanFailingTheWholeImport() throws Exception {
        long t = System.nanoTime();

        ImportResult result = importService.importUsers(usersSheet(HEADER,
                new String[]{"imp-org-long-" + t, "PC-IMPL-" + t, "کاربر", null, null, null,
                        null, "pass123", "LOCAL", "true", null, "x".repeat(200), null}));

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).message()).contains("Organizational unit");
        assertThat(userRepository.findByUsername("imp-org-long-" + t)).isEmpty();
    }

    @Test
    void aRowThatIsBlankAcrossAllThirteenColumnsIsSkippedNotReportedAsAnError() throws Exception {
        // The blank-row width had to grow with the layout; left at 11 a row carrying only the
        // two new columns would have been treated as empty and silently dropped.
        long t = System.nanoTime();
        String username = "imp-org-tail-" + t;

        ImportResult result = importService.importUsers(usersSheet(HEADER,
                new String[]{null, null, null, null, null, null, null, null, null, null, null, null, null},
                new String[]{username, "PC-IMPT-" + t, "کاربر", null, null, null,
                        null, "pass123", "LOCAL", "true", null, "واحد ابزار دقیق", "رئیس"}));

        assertThat(result.getErrors()).isEmpty();
        assertThat(userRepository.findByUsername(username).orElseThrow().getOrgUnit())
                .isEqualTo("واحد ابزار دقیق");
    }

    // --- the page and the form ------------------------------------------------

    @Test
    @WithAppUser(username = "org-admin", roles = "ADMIN", authorities = {"GET:/users", "POST:/users"})
    void theUsersPageRendersBothFieldsAndTheirValues() throws Exception {
        // Thymeleaf failures are runtime failures — a bad expression takes the whole page down
        // with a 500 that no unit test sees. Rendering it here is the guard.
        long t = System.nanoTime();
        userService.create("u-page-" + t, "کاربر صفحه", "PC-PAGE-" + t, null, null, null, null,
                "واحد ابزار دقیق", "رئیس کارگاه", "pass123", UserAuthType.LOCAL, true, List.of());

        String html = mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("واحد سازمانی");
        assertThat(html).contains("پست سازمانی");
        assertThat(html).contains("name=\"orgUnit\"");
        assertThat(html).contains("name=\"orgPosition\"");
        // The row itself, not just the form labels.
        assertThat(html).contains("واحد ابزار دقیق");
        assertThat(html).contains("رئیس کارگاه");
    }

    @Test
    @WithAppUser(username = "org-admin", roles = "ADMIN", authorities = {"GET:/users", "POST:/users"})
    void aValueOfLiterallyOffStillRenders() throws Exception {
        // Thymeleaf reads the strings "off", "false" and "no" as boolean false, so a truthiness
        // test would replace exactly those values with the «—» placeholder. Unlikely as a unit
        // name, entirely possible as an imported one.
        long t = System.nanoTime();
        userService.create("u-off-" + t, "کاربر", "PC-OFF-" + t, null, null, null, null,
                "OFF", "no", "pass123", UserAuthType.LOCAL, true, List.of());

        String html = mockMvc.perform(get("/users")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(">OFF<");
        assertThat(html).contains(">no<");
    }

    @Test
    @WithAppUser(username = "org-admin", roles = "ADMIN", authorities = {"GET:/users", "POST:/users"})
    void theRolePickerDoesNotWrapALatinRoleCodeInParentheses() throws Exception {
        // Not an organisation field, but this is the test that renders /users. The role picker
        // showed «نام نقش (ADMIN)»; parentheses are bidi-neutral, so the RTL page mirrored them
        // and an operator saw «نام نقش )ADMIN(». The code is isolated in a <bdi> now — see
        // AGENTS.md 9c-4, which lists the other three places this bug was live.
        String html = mockMvc.perform(get("/users")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("(ADMIN)");
        assertThat(html).contains("<bdi");
    }

    @Test
    @WithAppUser(username = "org-admin", roles = "ADMIN", authorities = {"POST:/users", "POST:/users/{id}"})
    void theCreateAndEditFormsCarryBothFieldsThroughToTheDatabase() throws Exception {
        // The controller copies parameters one by one; a field missing from either handler is
        // silently dropped, and on the edit handler that also *clears* a stored value.
        long t = System.nanoTime();
        String username = "u-form-" + t;

        mockMvc.perform(post("/users").with(csrf())
                        .param("username", username)
                        .param("fullName", "کاربر فرم")
                        .param("personnelCode", "PC-FORM-" + t)
                        .param("orgUnit", "واحد فرم")
                        .param("orgPosition", "کارشناس فرم")
                        .param("password", "pass123")
                        .param("authType", "LOCAL")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());

        User created = userRepository.findByUsername(username).orElseThrow();
        assertThat(created.getOrgUnit()).isEqualTo("واحد فرم");
        assertThat(created.getOrgPosition()).isEqualTo("کارشناس فرم");

        mockMvc.perform(post("/users/" + created.getId()).with(csrf())
                        .param("username", username)
                        .param("fullName", "کاربر فرم")
                        .param("personnelCode", "PC-FORM-" + t)
                        .param("orgUnit", "واحد ویرایش‌شده")
                        .param("orgPosition", "کارشناس ارشد")
                        .param("authType", "LOCAL")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());

        User updated = userRepository.findById(created.getId()).orElseThrow();
        assertThat(updated.getOrgUnit()).isEqualTo("واحد ویرایش‌شده");
        assertThat(updated.getOrgPosition()).isEqualTo("کارشناس ارشد");
    }

    // --- template -------------------------------------------------------------

    @Test
    @WithAppUser(username = "org-admin", roles = "ADMIN", authorities = "GET:/users/import-template")
    void theDownloadedTemplateCarriesTheTwoNewColumnsLast() throws Exception {
        byte[] bytes = mockMvc.perform(get("/users/import-template"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Row header = wb.getSheetAt(0).getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) HEADER.length);
            for (int c = 0; c < HEADER.length; c++) {
                assertThat(header.getCell(c).getStringCellValue())
                        .as("template column %d", c)
                        .isEqualTo(HEADER[c]);
            }
        }
    }

    @Test
    @WithAppUser(username = "org-admin", roles = "ADMIN", authorities = "GET:/users/import-template")
    void theTemplateAndTheImportParserAgreeColumnForColumn() throws Exception {
        // Written as a round trip rather than two lists: fill the downloaded template itself
        // and check every value lands in the field its header names. This is what catches a
        // template edited without the parser, or the reverse.
        byte[] bytes = mockMvc.perform(get("/users/import-template"))
                .andReturn().getResponse().getContentAsByteArray();

        long t = System.nanoTime();
        String username = "imp-roundtrip-" + t;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row header = wb.getSheetAt(0).getRow(0);
            Row row = wb.getSheetAt(0).createRow(1);
            String[] values = {username, "PC-RT-" + t, "کاربر رفت‌وبرگشت", null, null, null,
                    "شیفت C", "pass123", "LOCAL", "true", null, "واحد مکانیک", "سرپرست کارگاه"};
            for (int c = 0; c < header.getLastCellNum(); c++) {
                if (values[c] != null) row.createCell(c).setCellValue(values[c]);
            }
            wb.write(out);

            ImportResult result = importService.importUsers(new MockMultipartFile("file", "users.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()));
            assertThat(result.getErrors()).isEmpty();
        }

        User saved = userRepository.findByUsername(username).orElseThrow();
        assertThat(saved.getPersonnelCode()).isEqualTo("PC-RT-" + t);
        assertThat(saved.getFullName()).isEqualTo("کاربر رفت‌وبرگشت");
        assertThat(saved.getShift()).isEqualTo("شیفت C");
        assertThat(saved.getOrgUnit()).isEqualTo("واحد مکانیک");
        assertThat(saved.getOrgPosition()).isEqualTo("سرپرست کارگاه");
        assertThat(saved.isActive()).isTrue();
    }
}
