package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetScanMode;
import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetListFilter;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.ManagementReportRows.ComplianceRow;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetCategory;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.CustomLogSheetService;
import com.hnp.backendofflinefirst.service.IntegrationLogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.LogSheetCategoryService;
import com.hnp.backendofflinefirst.service.LogSheetFilterOptionsService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.service.ManagementReportService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The log-sheet list's new filters (roadmap §26) and log-sheet categories (roadmap §28), end to
 * end against PostgreSQL.
 *
 * <p>The properties worth guarding are the ones a green screen does not show: a filter that
 * <b>widens</b> past the viewer's units, a picker that lists people in other units, a category
 * name that a rename rewrites on old rounds, and a delete that leaves rounds grouped by an id
 * that resolves to nothing.
 *
 * <p>{@code @Transactional}: every row here is rolled back, so nothing leaks into another test's
 * query (AGENTS.md #186). Assertions are narrowed to this fixture's own ids for the same reason.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets", "GET:/log-sheet-categories",
        "POST:/log-sheet-categories", "POST:/log-sheet-categories/{id}", "POST:/log-sheet-categories/{id}/delete"})
class LogSheetNarrowingAndCategoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UserRepository userRepository;
    @Autowired LogSheetAccessService accessService;
    @Autowired LogSheetFilterOptionsService filterOptionsService;
    @Autowired LogSheetCategoryService categoryService;
    @Autowired LogSheetTemplateService templateService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired CustomLogSheetService customLogSheetService;
    @Autowired ManagementReportService managementReportService;
    @Autowired IntegrationLogSheetService integrationLogSheetService;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;
    long now;
    String tag;
    OperationalUnit unitA;
    OperationalUnit unitB;
    User supervisorOfA;
    User operatorA;
    User operatorB;
    LogSheetCategory safety;
    LogSheetCategory retired;
    LogSheetTemplate template;
    LogSheet overdueScheduledInA;
    LogSheet manualPendingInA;
    LogSheet overdueInB;
    LogSheet submittedLateInA;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        now = System.currentTimeMillis();
        tag = Long.toString(System.nanoTime(), 36).toUpperCase();
        unitA = unit("NRW-A-" + tag);
        unitB = unit("NRW-B-" + tag);
        supervisorOfA = user("nrw-sup-" + tag, "سرپرست");
        operatorA = user("nrw-opa-" + tag, "اپراتور الف");
        operatorB = user("nrw-opb-" + tag, "اپراتور ب");
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitA.getId());
        link.setUserId(supervisorOfA.getId());
        unitSupervisorRepository.save(link);

        safety = categoryService.create("SAFE_" + tag, "چک‌لیست ایمنی", true);
        retired = categoryService.create("OLD_" + tag, "دسته قدیمی", true);
        categoryService.update(retired.getId(), null, retired.getName(), false);

        template = new LogSheetTemplate();
        template.setName("NRW template " + tag);
        template.setOperationalUnitId(unitA.getId());
        template.setAssetSelectionMode(AssetSelectionMode.NONE);
        template.setScanMode(AssetScanMode.NONE);
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCategoryId(safety.getId());
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        overdueScheduledInA = sheet(unitA, template.getId(), GenerationMode.SCHEDULED, operatorA,
                LogSheetStatus.ASSIGNED, now - 3_600_000L, safety);
        manualPendingInA = sheet(unitA, null, GenerationMode.MANUAL, null,
                LogSheetStatus.PENDING, now + 86_400_000L, null);
        overdueInB = sheet(unitB, null, GenerationMode.MANUAL, operatorB,
                LogSheetStatus.IN_PROGRESS, now - 3_600_000L, safety);
        submittedLateInA = sheet(unitA, null, GenerationMode.MANUAL, operatorA,
                LogSheetStatus.SUBMITTED, now - 3_600_000L, safety);
        submittedLateInA.setCompletedAt(now);
        submittedLateInA.setSubmittedAt(now);
        submittedLateInA = logSheetRepository.saveAndFlush(submittedLateInA);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================ §26 — the filters

    @Test
    void theUnitFilterIsThatUnitExactly() {
        assertThat(mine(filter("unitId", unitA.getId())))
                .containsExactlyInAnyOrder(overdueScheduledInA.getId(), manualPendingInA.getId(), submittedLateInA.getId());
    }

    @Test
    void aScopedViewerNamingAnotherUnitGetsNothingNotThatUnitsRounds() {
        asSupervisorOfA();
        assertThat(mine(filter("unitId", unitB.getId()))).isEmpty();
        // And with no unit named, the other unit is still not there — the filter narrows scope,
        // it never replaces it.
        assertThat(mine(filter())).doesNotContain(overdueInB.getId());
    }

    @Test
    void templateOriginAndAssigneeEachNarrow() {
        assertThat(mine(filter("templateId", template.getId()))).containsExactly(overdueScheduledInA.getId());
        assertThat(mine(filter("origin", "SCHEDULED"))).containsExactly(overdueScheduledInA.getId());
        assertThat(mine(filter("assigneeUserId", operatorA.getId())))
                .containsExactlyInAnyOrder(overdueScheduledInA.getId(), submittedLateInA.getId());
    }

    @Test
    void overdueIsOpenAndPastItsDeadline_notMerelyPastItsDeadline() {
        // The submitted round was due an hour ago too; a delivered round is not open.
        assertThat(mine(filter("overdue", "true")))
                .containsExactlyInAnyOrder(overdueScheduledInA.getId(), overdueInB.getId());
    }

    @Test
    void theCategoryFilterUsesTheFrozenId() {
        assertThat(mine(filter("categoryId", safety.getId())))
                .containsExactlyInAnyOrder(overdueScheduledInA.getId(), overdueInB.getId(), submittedLateInA.getId());
    }

    @Test
    void aWholeNumberSearchFindsTheSheetByIdInEitherDigits() {
        String id = String.valueOf(manualPendingInA.getId());
        assertThat(mine(filter("q", id))).contains(manualPendingInA.getId());
        String persian = id.chars().mapToObj(c -> String.valueOf((char) ('۰' + (c - '0'))))
                .collect(Collectors.joining());
        assertThat(mine(filter("q", persian))).contains(manualPendingInA.getId());
    }

    @Test
    void theListPageRefusesAnUnreadableFilterAndCarriesEveryFilterIntoTheExport() throws Exception {
        mockMvc.perform(get("/log-sheets").param("unitId", "abc"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        String html = mockMvc.perform(get("/log-sheets")
                        .param("unitId", String.valueOf(unitA.getId()))
                        .param("overdue", "true")
                        .param("categoryId", String.valueOf(safety.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/log-sheets/export?")
                .contains("unitId=" + unitA.getId())
                .contains("overdue=true")
                .contains("categoryId=" + safety.getId());
        // The pre-selected unit renders under its own label, so a pasted link shows what is in force.
        assertThat(html).contains(unitA.getCode());
    }

    @Test
    void thePickersOfferOnlyWhatTheViewersListCouldShow() {
        asSupervisorOfA();
        List<String> units = filterOptionsService.units(null, 50).stream().map(SelectOptionDto::value).toList();
        assertThat(units).contains(String.valueOf(unitA.getId())).doesNotContain(String.valueOf(unitB.getId()));

        List<String> people = filterOptionsService.assignees(null, 50).stream().map(SelectOptionDto::value).toList();
        assertThat(people).contains(String.valueOf(operatorA.getId())).doesNotContain(String.valueOf(operatorB.getId()));

        // A URL naming someone else's unit or person prints nothing about them.
        assertThat(filterOptionsService.unitLabel(unitB.getId())).isNull();
        assertThat(filterOptionsService.assigneeLabel(operatorB.getId())).isNull();
        assertThat(filterOptionsService.assigneeLabel(operatorA.getId())).contains("اپراتور الف");
    }

    // ================================================================ §28 — categories

    @Test
    void aCodeIsUpperCasedUniqueRegardlessOfCaseAndFixedOnceCreated() {
        assertThat(safety.getCode()).isEqualTo(("SAFE_" + tag).toUpperCase());
        assertThatThrownBy(() -> categoryService.create(("safe_" + tag).toLowerCase(), "x", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> categoryService.update(safety.getId(), "OTHER", "x", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> categoryService.create("bad code!", "x", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCategoryStillNamedCannotBeDeleted_eitherByATemplateOrByARound() {
        assertThatThrownBy(() -> categoryService.delete(safety.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("template");

        LogSheetCategory onlyOnARound = categoryService.create("RND_" + tag, "فقط روی لاگ‌شیت", true);
        sheet(unitA, null, GenerationMode.MANUAL, null, LogSheetStatus.PENDING, null, onlyOnARound);
        assertThatThrownBy(() -> categoryService.delete(onlyOnARound.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("log sheets");

        LogSheetCategory unused = categoryService.create("UNU_" + tag, "استفاده‌نشده", true);
        categoryService.delete(unused.getId());
    }

    @Test
    void aRetiredCategoryCannotBeNewlyChosen_butATemplateThatHasItKeepsIt() {
        assertThatThrownBy(() -> categoryService.requireAssignable(retired.getId(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inactive");
        assertThat(categoryService.requireAssignable(retired.getId(), retired.getId())).isEqualTo(retired.getId());
        assertThat(categoryService.requireAssignable(null, retired.getId())).isNull();
    }

    @Test
    void aRaisedRoundFreezesTheCategoryAndARenameDoesNotRewriteIt() {
        LogSheet raised = generationService.generateAt(template, GenerationMode.MANUAL, operatorA.getId(), now, now);
        assertThat(raised.getCategoryId()).isEqualTo(safety.getId());
        assertThat(raised.getCategoryName()).isEqualTo("چک‌لیست ایمنی");

        categoryService.update(safety.getId(), null, "HSE", true);
        assertThat(logSheetRepository.findById(raised.getId()).orElseThrow().getCategoryName())
                .isEqualTo("چک‌لیست ایمنی");
    }

    @Test
    void aCustomRoundChoosesItsCategoryAndARetiredOneIsRefused() {
        LogSheet custom = customLogSheetService.createCustom(unitA.getId(), "NRW custom " + tag, now + 86_400_000L,
                List.of(), true, null, safety.getId(), operatorA.getId(), now);
        assertThat(custom.getCategoryId()).isEqualTo(safety.getId());
        assertThat(custom.getCategoryName()).isEqualTo("چک‌لیست ایمنی");

        assertThatThrownBy(() -> customLogSheetService.createCustom(unitA.getId(), "NRW custom2 " + tag,
                now + 86_400_000L, List.of(), true, null, retired.getId(), operatorA.getId(), now))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inactive");
    }

    @Test
    void theComplianceReportGroupsByCategoryAndKeepsUncategorisedRoundsAsARow() {
        List<ComplianceRow> rows = managementReportService.complianceByCategory(now - 86_400_000L, null);
        ComplianceRow safe = rows.stream().filter(r -> safety.getId().equals(r.groupId())).findFirst().orElseThrow();
        assertThat(safe.groupLabel()).isEqualTo("چک‌لیست ایمنی");
        assertThat(safe.total()).isGreaterThanOrEqualTo(3);
        assertThat(rows).anyMatch(r -> r.groupId() == null && "بدون دسته".equals(r.groupLabel()));
    }

    @Test
    void theIntegrationFeedPublishesTheCodeAndTheFrozenName() {
        var detail = integrationLogSheetService.findDetail(submittedLateInA.getId()).orElseThrow();
        assertThat(detail.summary().category()).isNotNull();
        assertThat(detail.summary().category().code()).isEqualTo(safety.getCode());
        assertThat(detail.summary().category().name()).isEqualTo("چک‌لیست ایمنی");
    }

    @Test
    void thePageRendersAndItsFormsWork() throws Exception {
        mockMvc.perform(get("/log-sheet-categories")).andExpect(status().isOk());
        mockMvc.perform(post("/log-sheet-categories").with(csrf())
                        .param("code", "web_" + tag).param("name", "از فرم"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));
        LogSheetCategory created = categoryService.findAllForPicker().stream()
                .filter(c -> c.getCode().equals(("WEB_" + tag).toUpperCase())).findFirst().orElseThrow();

        // The marker says the box was on the form; an unticked box then retires the category.
        mockMvc.perform(post("/log-sheet-categories/" + created.getId()).with(csrf())
                        .param("name", "از فرم").param("_active", "on"))
                .andExpect(status().is3xxRedirection());
        assertThat(categoryService.require(created.getId()).isActive()).isFalse();
        // No marker at all (a request that never showed the box) keeps the stored state.
        mockMvc.perform(post("/log-sheet-categories/" + created.getId()).with(csrf()).param("name", "از فرم ۲"))
                .andExpect(status().is3xxRedirection());
        assertThat(categoryService.require(created.getId()).isActive()).isFalse();

        mockMvc.perform(post("/log-sheet-categories/" + safety.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void v19GrantsThePageToWhoeverMayCreateATemplateAndToNobodyElse() {
        List<String> holders = jdbc.queryForList("""
                SELECT r.code FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'GET:/log-sheet-categories'
                """, String.class);
        assertThat(holders).contains("ADMIN", "HIGH_USER").doesNotContain("SUPERVISOR", "OPERATOR");
    }

    @Test
    void theFrozenPairIsAllOrNothingInTheSchemaItself() {
        assertThatThrownBy(() -> jdbc.update("UPDATE log_sheets SET category_name = NULL WHERE id = ?",
                overdueScheduledInA.getId()))
                .hasMessageContaining("ck_log_sheets_category_frozen");
    }

    // ================================================================ helpers

    private LogSheetListFilter filter(Object... kv) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return LogSheetListFilter.fromParameters(params, null, null, now);
    }

    /** This fixture's ids among what the list returned — other tests' rows are not this test's business. */
    private List<Long> mine(LogSheetListFilter f) {
        Set<Long> ours = Set.of(overdueScheduledInA.getId(), manualPendingInA.getId(),
                overdueInB.getId(), submittedLateInA.getId());
        return accessService.findVisibleLogSheets(f, PageRequest.of(0, 500)).getContent().stream()
                .map(LogSheet::getId).filter(ours::contains).toList();
    }

    private void asSupervisorOfA() {
        AppUserDetails principal = new AppUserDetails(supervisorOfA, Set.of("SOME_ROLE"),
                Set.of("GET:/log-sheets", Capabilities.TEMPLATE_VIEW_SUPERVISED));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities().stream()
                        .map(a -> new SimpleGrantedAuthority(a.getAuthority())).toList()));
    }

    private OperationalUnit unit(String code) {
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(code);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.save(unit);
    }

    private User user(String username, String fullName) {
        User u = new User();
        u.setUsername(username);
        u.setFullName(fullName);
        u.setPersonnelCode("PC-" + username);
        u.setPasswordHash("x");
        u.setActive(true);
        return userRepository.save(u);
    }

    private LogSheet sheet(OperationalUnit unit, Long templateId, GenerationMode origin, User assignee,
                           LogSheetStatus status, Long dueAt, LogSheetCategory category) {
        LogSheet s = new LogSheet();
        s.setTemplateId(templateId);
        s.setTemplateName("NRW " + tag);
        s.setOperationalUnitId(unit.getId());
        s.setOrigin(origin);
        s.setStatus(status);
        s.setAssigneeUserId(assignee == null ? null : assignee.getId());
        s.setOperatorName(assignee == null ? null : assignee.getFullName());
        s.setDueAt(dueAt);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        if (category != null) {
            s.setCategoryId(category.getId());
            s.setCategoryName(category.getName());
        }
        return logSheetRepository.saveAndFlush(s);
    }
}
