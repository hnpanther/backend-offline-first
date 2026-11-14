package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.LookupItem;
import com.hnp.backendofflinefirst.entity.LookupList;
import com.hnp.backendofflinefirst.repository.LookupItemRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import com.hnp.backendofflinefirst.service.LookupListService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reference data — lookup lists and their items (V22, roadmap §31 Phase 2), end to end against
 * PostgreSQL: the rules a green screen does not show. A code that changes, «۱۲۳۴» and «1234»
 * becoming two people, a deactivation that forgets when it happened, an anonymisation that can be
 * undone, a revision that does not move, a column removed while its values stay hidden.
 *
 * <p>{@code @Transactional}: everything here rolls back. The list entity in the persistence context
 * is never trusted for {@code revision} — it is mapped read-only and moved by an SQL increment —
 * so every revision assertion reads {@link LookupListRepository#currentRevision}.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {"GET:/lookup-lists", "POST:/lookup-lists", "POST:/lookup-lists/{id}",
        "POST:/lookup-lists/{id}/delete", "POST:/lookup-lists/{id}/items", "POST:/lookup-lists/{id}/items/{itemId}",
        "POST:/lookup-lists/{id}/items/{itemId}/delete", "POST:/lookup-lists/{id}/import"})
class LookupListIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LookupListService service;
    @Autowired LookupListRepository listRepository;
    @Autowired LookupItemRepository itemRepository;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;
    String tag;
    LookupList personnel;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tag = Long.toString(System.nanoTime(), 36).toUpperCase();
        personnel = service.createList("per_" + tag, "پرسنل", "فهرست پرسنل", null);
        service.addColumn(personnel.getId(), "unit", "واحد", true, true, true);
        service.addColumn(personnel.getId(), "nationalId", "کد ملی", false, false, false);
    }

    private Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private long revision(LookupList list) {
        return listRepository.currentRevision(list.getId());
    }

    // ---------------------------------------------------------------------- lists and columns

    @Test
    void aListCodeIsUpperCasedUniqueIgnoringCaseAndFixed() {
        assertThat(personnel.getCode()).isEqualTo(("per_" + tag).toUpperCase());
        assertThatThrownBy(() -> service.createList("PER_" + tag, "دوباره", null, null))
                .hasMessageStartingWith("Duplicate lookup list code:");
        assertThatThrownBy(() -> service.updateList(personnel.getId(), "OTHER", "پرسنل", null, null, null))
                .hasMessage("A lookup list's code cannot be changed.");
    }

    @Test
    void aColumnKeyIsAnIdentifierNotOneOfTheFileHeadersAndNotTwice() {
        assertThatThrownBy(() -> service.addColumn(personnel.getId(), "label", "x", false, false, false))
                .hasMessage("This lookup column key is reserved.");
        assertThatThrownBy(() -> service.addColumn(personnel.getId(), "UNIT", "x", false, false, false))
                .hasMessageStartingWith("Duplicate lookup column key:");
        assertThatThrownBy(() -> service.addColumn(personnel.getId(), "9lives", "x", false, false, false))
                .hasMessage("Invalid lookup column key.");
    }

    @Test
    void everyChangeMovesTheRevisionByExactlyOne() {
        long r0 = revision(personnel);
        LookupItem item = service.createItem(personnel.getId(), "100", "علی", attrs("unit", "تعمیرات"), null, null);
        long r1 = revision(personnel);
        service.updateItem(personnel.getId(), item.getId(), null, "علی رضایی", attrs("unit", "تعمیرات"), null, null);
        long r2 = revision(personnel);
        service.setItemActive(personnel.getId(), item.getId(), false);
        long r3 = revision(personnel);
        assertThat(List.of(r1 - r0, r2 - r1, r3 - r2)).containsOnly(1L);
    }

    @Test
    void togglingSearchableRebuildsWhatTheSearchFinds() {
        service.createItem(personnel.getId(), "101", "مریم", attrs("nationalId", "0012345678"), null, null);
        assertThat(service.pageItems(personnel.getId(), "0012345678", PageRequest.of(0, 10)).getTotalElements()).isZero();

        service.updateColumn(personnel.getId(), "nationalId", "کد ملی", true, false, false);
        assertThat(service.pageItems(personnel.getId(), "0012345678", PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    void removingAColumnRemovesItsValuesRatherThanHidingThem() {
        LookupItem item = service.createItem(personnel.getId(), "102", "رضا", attrs("unit", "بهره‌برداری", "nationalId", "0098765432"), null, null);
        assertThat(service.countWithAttribute(personnel.getId(), "nationalId")).isEqualTo(1);

        service.removeColumn(personnel.getId(), "nationalId");

        String stored = jdbc.queryForObject("SELECT attributes::text FROM lookup_items WHERE id = ?", String.class, item.getId());
        assertThat(stored).doesNotContain("nationalId").doesNotContain("0098765432").contains("unit");
    }

    // ---------------------------------------------------------------------- item codes

    @Test
    void persianDigitsInACodeAreTheSameCodeAsLatinDigits() {
        LookupItem item = service.createItem(personnel.getId(), "۱۲۳۴", "حسن", Map.of(), null, null);
        assertThat(item.getCode()).isEqualTo("1234");
        assertThatThrownBy(() -> service.createItem(personnel.getId(), "1234", "دیگری", Map.of(), null, null))
                .hasMessageStartingWith("Duplicate lookup item code:");
    }

    @Test
    void aCodeHasNoSpaceAndNoInvisibleCharacterAndNeverChanges() {
        assertThatThrownBy(() -> service.createItem(personnel.getId(), "A 12", "x", Map.of(), null, null))
                .hasMessage("Invalid lookup item code.");
        assertThatThrownBy(() -> service.createItem(personnel.getId(), "A‌12", "x", Map.of(), null, null))
                .hasMessage("Invalid lookup item code.");
        LookupItem item = service.createItem(personnel.getId(), "A12", "x", Map.of(), null, null);
        assertThatThrownBy(() -> service.updateItem(personnel.getId(), item.getId(), "A13", "x", Map.of(), null, null))
                .hasMessage("A lookup item's code cannot be changed.");
        // The same code, typed in another case or with Persian digits, is not a change.
        service.updateItem(personnel.getId(), item.getId(), "a12", "y", Map.of(), null, null);
    }

    @Test
    void anItemOfAnotherListIsNotFoundThroughThisListsUrl() {
        LookupList other = service.createList("oth_" + tag, "دیگر", null, null);
        LookupItem foreign = service.createItem(other.getId(), "1", "x", Map.of(), null, null);
        assertThatThrownBy(() -> service.requireItem(personnel.getId(), foreign.getId()))
                .hasMessage("Lookup item not found.");
    }

    // ---------------------------------------------------------------------- deactivate, anonymise, delete

    @Test
    void deactivatingRecordsTheInstantAndReactivatingClearsIt() {
        LookupItem item = service.createItem(personnel.getId(), "200", "سارا", Map.of(), null, null);
        service.setItemActive(personnel.getId(), item.getId(), false);
        Long at = jdbc.queryForObject("SELECT deactivated_at FROM lookup_items WHERE id = ?", Long.class, item.getId());
        assertThat(at).isNotNull();
        service.setItemActive(personnel.getId(), item.getId(), true);
        assertThat(jdbc.queryForObject("SELECT deactivated_at FROM lookup_items WHERE id = ?", Long.class, item.getId())).isNull();
    }

    @Test
    void anonymisingKeepsOnlyTheCodeAndCanNeverBeUndone() {
        LookupItem item = service.createItem(personnel.getId(), "300", "نام واقعی", attrs("unit", "ایمنی", "nationalId", "0011111111"), null, null);
        service.anonymiseItem(personnel.getId(), item.getId());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT code, label, attributes::text AS a, active, anonymised_at, search_text FROM lookup_items WHERE id = ?", item.getId());
        assertThat(row.get("code")).isEqualTo("300");
        assertThat(row.get("label")).isEqualTo(LookupListService.ANONYMISED_LABEL);
        assertThat((String) row.get("a")).isEqualTo("{}");
        assertThat(row.get("active")).isEqualTo(false);
        assertThat(row.get("anonymised_at")).isNotNull();
        assertThat((String) row.get("search_text")).doesNotContain("نام");

        assertThatThrownBy(() -> service.setItemActive(personnel.getId(), item.getId(), true))
                .hasMessage("An anonymised lookup item cannot be changed.");
        assertThatThrownBy(() -> service.updateItem(personnel.getId(), item.getId(), null, "x", Map.of(), null, null))
                .hasMessage("An anonymised lookup item cannot be changed.");
        assertThatThrownBy(() -> service.anonymiseItem(personnel.getId(), item.getId()))
                .hasMessage("This lookup item is already anonymised.");
    }

    @Test
    void theDatabaseRefusesAnActiveItemWithADeactivationInstant() {
        LookupItem item = service.createItem(personnel.getId(), "400", "x", Map.of(), null, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE lookup_items SET deactivated_at = 1 WHERE id = ?", item.getId()))
                .hasMessageContaining("ck_lookup_items_deactivated_at");
    }

    // ---------------------------------------------------------------------- parent lists

    @Test
    void aParentLivesInAnotherListAndListsNeverFormACycle() {
        LookupList types = service.createList("typ_" + tag, "نوع خودرو", null, null);
        LookupList models = service.createList("mdl_" + tag, "مدل خودرو", null, types.getId());
        LookupItem pickup = service.createItem(types.getId(), "PU", "وانت", Map.of(), null, null);
        LookupItem nissan = service.createItem(models.getId(), "N1", "نیسان", Map.of(), null, "pu");
        assertThat(nissan.getParentItemId()).isEqualTo(pickup.getId());

        assertThatThrownBy(() -> service.updateList(types.getId(), null, "نوع خودرو", null, models.getId(), null))
                .hasMessage("This parent list would make a cycle of lookup lists.");
        assertThatThrownBy(() -> service.updateList(types.getId(), null, "نوع خودرو", null, types.getId(), null))
                .hasMessage("A lookup list cannot be its own parent.");
        assertThatThrownBy(() -> service.createItem(models.getId(), "N2", "x", Map.of(), null, "NOPE"))
                .hasMessageStartingWith("Parent item not found in the parent list:");
        assertThatThrownBy(() -> service.createItem(personnel.getId(), "P1", "x", Map.of(), null, "PU"))
                .hasMessage("This lookup list has no parent list.");
        assertThatThrownBy(() -> service.updateList(models.getId(), null, "مدل خودرو", null, null, null))
                .hasMessage("The parent list cannot change while items name a parent.");
    }

    @Test
    void anItemNamedAsAParentIsDeactivatedNeverDeleted() {
        LookupList types = service.createList("typ2_" + tag, "نوع", null, null);
        LookupList models = service.createList("mdl2_" + tag, "مدل", null, types.getId());
        LookupItem pickup = service.createItem(types.getId(), "PU", "وانت", Map.of(), null, null);
        service.createItem(models.getId(), "N1", "نیسان", Map.of(), null, "PU");
        assertThatThrownBy(() -> service.deleteItem(types.getId(), pickup.getId()))
                .hasMessage("This lookup item is the parent of other items.");
        assertThatThrownBy(() -> service.deleteList(types.getId()))
                .hasMessage("This lookup list still has items.");
    }

    @Test
    void onlyAnEmptyListThatNoListNamesAsParentIsDeleted() {
        LookupList parent = service.createList("par_" + tag, "والد", null, null);
        LookupList child = service.createList("chl_" + tag, "فرزند", null, parent.getId());
        assertThatThrownBy(() -> service.deleteList(parent.getId()))
                .hasMessage("This lookup list is the parent of another list.");
        service.deleteList(child.getId());
        service.deleteList(parent.getId());
        assertThat(listRepository.findById(parent.getId())).isEmpty();
    }

    // ---------------------------------------------------------------------- search

    @Test
    void searchFindsArabicLettersAndPersianDigitsAsTheirPersianForms() {
        service.createItem(personnel.getId(), "777", "علی کریمی", attrs("unit", "واحد ۱۲"), null, null);
        assertThat(service.pageItems(personnel.getId(), "علي كريمي", PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(service.pageItems(personnel.getId(), "واحد 12", PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------- panel

    @Test
    void thePagesRenderFinishedIncludingBothEditDialogs() throws Exception {
        LookupItem item = service.createItem(personnel.getId(), "500", "نرگس", attrs("unit", "ایمنی"), null, null);
        for (String url : List.of("/lookup-lists", "/lookup-lists/" + personnel.getId(),
                "/lookup-lists/" + personnel.getId() + "?editId=" + item.getId(),
                "/lookup-lists/" + personnel.getId() + "?editColumn=unit",
                "/lookup-lists/" + personnel.getId() + "?q=نرگس")) {
            String html = mockMvc.perform(get(url)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as(url).contains("id=\"pageContent\"").doesNotContain(" th:");
        }
    }

    @Test
    void theItemFormPostsTheDeclaredColumnsAndNothingElse() throws Exception {
        mockMvc.perform(post("/lookup-lists/" + personnel.getId() + "/items").with(csrf())
                        .param("code", "600").param("label", "کاوه")
                        .param("attr_unit", "انبار").param("attr_nationalId", "")
                        .param("attr_injected", "x").param("sortOrder", "۳"))
                .andExpect(status().is3xxRedirection());
        LookupItem saved = itemRepository.findByListIdAndCodeIgnoreCase(personnel.getId(), "600").orElseThrow();
        assertThat(saved.getAttributes()).containsExactly(Map.entry("unit", "انبار"));
        assertThat(saved.getSortOrder()).isEqualTo(3);
    }

    @Test
    @WithAppUser(authorities = {"GET:/lookup-lists"})
    void aViewerCannotAddAnItem() throws Exception {
        mockMvc.perform(post("/lookup-lists/" + personnel.getId() + "/items").with(csrf())
                .param("code", "700").param("label", "x"));
        assertThat(itemRepository.findByListIdAndCodeIgnoreCase(personnel.getId(), "700")).isEmpty();
    }

    // ---------------------------------------------------------------------- seed and messages

    @Test
    void theEightPermissionsAreSeededForAdministratorsOnly() {
        List<String> codes = List.of("GET:/lookup-lists", "POST:/lookup-lists", "POST:/lookup-lists/{id}",
                "POST:/lookup-lists/{id}/delete", "POST:/lookup-lists/{id}/items",
                "POST:/lookup-lists/{id}/items/{itemId}", "POST:/lookup-lists/{id}/items/{itemId}/delete",
                "POST:/lookup-lists/{id}/import");
        for (String code : codes) {
            List<String> roles = jdbc.queryForList("""
                    SELECT r.code FROM roles r
                    JOIN role_permissions rp ON rp.role_id = r.id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE p.code = ? AND r.system_role
                    """, String.class, code);
            assertThat(roles).as(code).containsExactly("ADMIN");
        }
    }

    @Test
    void everyRefusalReadsInPersian() {
        for (String english : List.of("Duplicate lookup item code: 12", "Invalid lookup item code.",
                "A lookup item's code cannot be changed.", "This lookup list still has items.",
                "Unknown column in the import file: foo", "Duplicate code in the file: 12 (row 3)",
                "The lookup list changed since the preview. Preview the file again.",
                "Confirm the number of items this import deactivates.",
                "An anonymised lookup item cannot be changed.", "Lookup item value is too long: unit")) {
            String fa = ErrorTranslator.toFa(english);
            assertThat(fa).as(english).isNotEqualTo(english).doesNotContainPattern("[A-Za-z]{6,}\\s[a-z]{3,}");
        }
    }
}
