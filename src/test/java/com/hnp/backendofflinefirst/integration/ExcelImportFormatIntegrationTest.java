package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Excel import column layouts. Every sheet gained an optional Persian-name column
 * right after {@code name}, which shifted the index of every column after it — a silent
 * off-by-one here would, for example, read the wrong cell as an asset's {@code active} flag.
 * The location sheet additionally dropped its unit-code column.
 */
class ExcelImportFormatIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired ExcelImportService importService;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;

    private static MockMultipartFile sheetOf(String name, String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(name);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", name + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    // ---- locations: code | name | nameFa | parentCode ----

    @Test
    void locationImportReadsThePersianNameAndResolvesTheParentFromTheFourthColumn() throws Exception {
        long t = System.nanoTime();
        String parentCode = "LOC-IMP-P-" + t;
        Location parent = new Location();
        parent.setCode(parentCode);
        parent.setName("Parent");
        parent.setCreatedAt(System.currentTimeMillis());
        parent.setUpdatedAt(System.currentTimeMillis());
        parent = hierarchyService.saveLocation(parent, List.of());

        String childCode = "LOC-IMP-C-" + t;
        ImportResult result = importService.importLocations(sheetOf("locations",
                new String[]{"code", "name", "nameFa", "parentCode"},
                new String[]{childCode, "Child location", "مکان فرزند", parentCode}));

        assertThat(result.getErrors()).isEmpty();
        Location saved = locationRepository.findByCodeIgnoreCase(childCode).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Child location");
        assertThat(saved.getNameFa()).isEqualTo("مکان فرزند");
        assertThat(saved.getParentId()).isEqualTo(parent.getId());
    }

    @Test
    void locationImportNoLongerLinksOperationalUnits() throws Exception {
        long t = System.nanoTime();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-IMP-" + t);
        unit.setName("Import unit");
        unit.setCreatedAt(System.currentTimeMillis());
        unit.setUpdatedAt(System.currentTimeMillis());
        operationalUnitRepository.saveAndFlush(unit);

        String code = "LOC-IMP-NOUNIT-" + t;
        // A 5th column is simply ignored — units are managed from the location form now.
        ImportResult result = importService.importLocations(sheetOf("locations",
                new String[]{"code", "name", "nameFa", "parentCode"},
                new String[]{code, "No unit", null, null, "OU-IMP-" + t}));

        assertThat(result.getErrors()).isEmpty();
        Location saved = locationRepository.findByCodeIgnoreCase(code).orElseThrow();
        assertThat(hierarchyService.unitIdsForLocation(saved.getId()))
                .as("an imported location starts unowned").isEmpty();
        assertThat(saved.getNameFa()).as("blank Persian name stays null").isNull();
    }

    @Test
    void locationImportStillRejectsAnUnknownParent() throws Exception {
        long t = System.nanoTime();
        ImportResult result = importService.importLocations(sheetOf("locations",
                new String[]{"code", "name", "nameFa", "parentCode"},
                new String[]{"LOC-IMP-BAD-" + t, "Orphan", "یتیم", "NO-SUCH-PARENT"}));

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().message()).contains("Parent location not found");
        assertThat(locationRepository.findByCodeIgnoreCase("LOC-IMP-BAD-" + t)).isEmpty();
    }

    // ---- sub-functions: code | name | nameFa | tag | parentSubFunctionCode | mainFunctionCode | systemCode | locationCode ----

    @Test
    void subFunctionImportKeepsTagAndParentAtTheirShiftedIndices() throws Exception {
        long t = System.nanoTime();
        Location loc = new Location();
        loc.setCode("LOC-SFIMP-" + t);
        loc.setName("SF import location");
        loc.setCreatedAt(System.currentTimeMillis());
        loc.setUpdatedAt(System.currentTimeMillis());
        loc = hierarchyService.saveLocation(loc, List.of());

        String code = "SF-IMP-" + t;
        ImportResult result = importService.importSubFunctions(sheetOf("sub-functions",
                new String[]{"code", "name", "nameFa", "tag", "parentSubFunctionCode",
                        "mainFunctionCode", "systemCode", "locationCode"},
                new String[]{code, "Sub fn", "تابع فرعی", "TAG-IMP-" + t, null, null, null, loc.getCode()}));

        assertThat(result.getErrors()).isEmpty();
        SubFunction saved = subFunctionRepository.findByCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getNameFa()).isEqualTo("تابع فرعی");
        assertThat(saved.getTag()).as("tag must come from column 3, not the Persian name").isEqualTo("TAG-IMP-" + t);
        assertThat(saved.getLocationId()).isEqualTo(loc.getId());
    }

    // ---- assets: assetCode | assetName | assetNameFa | nfcTagId | nfcSerial | subFunctionCode | className | active ----

    private static final String[] ASSET_COLS = {
            "assetCode", "assetName", "assetNameFa", "nfcTagId", "nfcSerial",
            "subFunctionCode", "className", "active"};

    @Test
    void assetImportReadsActiveFromItsShiftedColumnAndNotTheClassName() throws Exception {
        long t = System.nanoTime();
        Fixture f = seedAssetFixture(t);

        String code = "AST-IMP-" + t;
        ImportResult result = importService.importAssetEntries(sheetOf("asset-entries", ASSET_COLS,
                new String[]{code, "Pump", "پمپ", "NFC-IMP-" + t, "00:aa:34:" + t,
                        f.subFunctionCode(), f.className(), "false"}));

        assertThat(result.getErrors()).isEmpty();
        AssetEntry saved = assetEntryRepository.findFirstByAssetCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getAssetNameFa()).isEqualTo("پمپ");
        assertThat(saved.getNfcTagId()).as("NFC tag must come from column 3").isEqualTo("NFC-IMP-" + t);
        assertThat(saved.getNfcSerial()).as("NFC serial must come from column 4").isEqualTo("00:aa:34:" + t);
        assertThat(saved.getClassId()).as("class must come from column 6").isNotNull();
        assertThat(saved.isActive()).as("active must come from column 7").isFalse();
    }

    @Test
    void assetImportDefaultsThePersianNameToNullWhenBlank() throws Exception {
        long t = System.nanoTime();
        Fixture f = seedAssetFixture(t);

        String code = "AST-IMP-BLANK-" + t;
        ImportResult result = importService.importAssetEntries(sheetOf("asset-entries", ASSET_COLS,
                new String[]{code, "Pump", "   ", "NFC-IMPB-" + t, null,
                        f.subFunctionCode(), f.className(), "true"}));

        assertThat(result.getErrors()).isEmpty();
        AssetEntry saved = assetEntryRepository.findFirstByAssetCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getAssetNameFa()).isNull();
        assertThat(saved.isActive()).isTrue();
    }

    /**
     * The serial is optional and — unlike {@code nfcTagId} — must never be back-filled from the
     * sub-function. A blank cell has to stay blank, otherwise every asset on a tagged sub-function
     * would silently claim to carry the same physical chip and collide on the unique index.
     */
    @Test
    void assetImportLeavesABlankSerialNullInsteadOfInheritingTheSubFunctionTag() throws Exception {
        long t = System.nanoTime();
        Fixture f = seedAssetFixture(t);

        String code = "AST-IMP-NOSERIAL-" + t;
        ImportResult result = importService.importAssetEntries(sheetOf("asset-entries", ASSET_COLS,
                new String[]{code, "Pump", null, null, "   ",
                        f.subFunctionCode(), f.className(), "true"}));

        assertThat(result.getErrors()).isEmpty();
        AssetEntry saved = assetEntryRepository.findFirstByAssetCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getNfcSerial()).isNull();
        // The tag still inherits, which is exactly the behaviour the serial must not copy.
        assertThat(saved.getNfcTagId()).isNotNull();
    }

    @Test
    void assetImportRejectsTwoRowsClaimingTheSamePhysicalChip() throws Exception {
        long t = System.nanoTime();
        Fixture f = seedAssetFixture(t);
        Fixture f2 = seedAssetFixture(t + 1);
        String serial = "00:bb:77:" + t;

        ImportResult result = importService.importAssetEntries(sheetOf("asset-entries", ASSET_COLS,
                new String[]{"AST-DUP-A-" + t, "Pump A", null, "NFC-DUPA-" + t, serial,
                        f.subFunctionCode(), f.className(), "true"},
                new String[]{"AST-DUP-B-" + t, "Pump B", null, "NFC-DUPB-" + t, serial.toUpperCase(),
                        f2.subFunctionCode(), f2.className(), "true"}));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().message())
                .as("case-insensitive, matching ux_asset_entries_nfc_serial_lower")
                .contains("Duplicate NFC serial in file");
    }

    @Test
    void assetImportRejectsASerialThatAnotherAssetAlreadyOwns() throws Exception {
        long t = System.nanoTime();
        Fixture f = seedAssetFixture(t);
        Fixture f2 = seedAssetFixture(t + 1);
        String serial = "00:cc:99:" + t;

        ImportResult first = importService.importAssetEntries(sheetOf("asset-entries", ASSET_COLS,
                new String[]{"AST-DB-A-" + t, "Pump A", null, "NFC-DBA-" + t, serial,
                        f.subFunctionCode(), f.className(), "true"}));
        assertThat(first.getErrors()).isEmpty();

        ImportResult second = importService.importAssetEntries(sheetOf("asset-entries", ASSET_COLS,
                new String[]{"AST-DB-B-" + t, "Pump B", null, "NFC-DBB-" + t, serial,
                        f2.subFunctionCode(), f2.className(), "true"}));

        assertThat(second.getSuccessCount()).isZero();
        assertThat(second.getErrors()).hasSize(1);
        assertThat(second.getErrors().getFirst().message()).contains("Duplicate NFC serial");
    }

    /** Several assets may leave the serial empty — NULLs are distinct in the unique index. */
    @Test
    void assetImportAllowsManyAssetsWithNoSerialAtAll() throws Exception {
        long t = System.nanoTime();
        Fixture f = seedAssetFixture(t);
        Fixture f2 = seedAssetFixture(t + 1);

        ImportResult result = importService.importAssetEntries(sheetOf("asset-entries", ASSET_COLS,
                new String[]{"AST-NULL-A-" + t, "Pump A", null, "NFC-NULLA-" + t, null,
                        f.subFunctionCode(), f.className(), "true"},
                new String[]{"AST-NULL-B-" + t, "Pump B", null, "NFC-NULLB-" + t, null,
                        f2.subFunctionCode(), f2.className(), "true"}));

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getSuccessCount()).isEqualTo(2);
    }

    // ---- fixture ----

    private record Fixture(String subFunctionCode, String className) {}

    private Fixture seedAssetFixture(long t) {
        long now = System.currentTimeMillis();
        Location loc = new Location();
        loc.setCode("LOC-ASTIMP-" + t);
        loc.setName("Asset import location");
        loc.setCreatedAt(now);
        loc.setUpdatedAt(now);
        loc = hierarchyService.saveLocation(loc, List.of());

        PlantSystem sys = new PlantSystem();
        sys.setCode("SYS-ASTIMP-" + t);
        sys.setName("Asset import system");
        sys.setLocationId(loc.getId());
        sys.setCreatedAt(now);
        sys.setUpdatedAt(now);
        sys = hierarchyService.savePlantSystem(sys);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-ASTIMP-" + t);
        mf.setName("Asset import main");
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, sys.getId());
        mf = hierarchyService.saveMainFunction(mf);

        SubFunction sf = new SubFunction();
        sf.setCode("SF-ASTIMP-" + t);
        sf.setName("Asset import sub");
        sf.setTag("TAG-ASTIMP-" + t);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetClass cls = new AssetClass();
        cls.setName("ImportClass-" + t);
        cls.setCreatedAt(now);
        cls.setUpdatedAt(now);
        cls = assetClassRepository.saveAndFlush(cls);

        return new Fixture(sf.getCode(), cls.getName());
    }
}
