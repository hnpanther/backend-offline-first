package com.hnp.backendofflinefirst.util;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue B-18: a workbook holding only the parts the format requires (no {@code styles.xml}, no
 * {@code docProps}) — what a script or another tool writes — imported through
 * {@code POST /locations/import} but ended {@code FAILED} with zero rows through
 * {@code /batch-import}. The batch path counts rows on the <em>stored file</em> first
 * ({@link ExcelUtils#countDataRows(Path)}), then the job reads the same file again. Counting must
 * leave the file exactly as it was uploaded.
 */
public class ExcelUtilsMinimalPackageTest {

    @TempDir Path dir;

    /** Five parts and nothing else: the smallest package POI and Excel accept. */
    public static byte[] minimalXlsx() throws IOException {
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>""";
        String rels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""";
        String workbook = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""";
        String workbookRels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>""";
        String sheet = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>code</t></is></c><c r="B1" t="inlineStr"><is><t>name</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>B18-A</t></is></c><c r="B2" t="inlineStr"><is><t>Alpha</t></is></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><t>B18-B</t></is></c><c r="B3" t="inlineStr"><is><t>Beta</t></is></c></row>
                </sheetData></worksheet>""";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", contentTypes);
            put(zip, "_rels/.rels", rels);
            put(zip, "xl/workbook.xml", workbook);
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels);
            put(zip, "xl/worksheets/sheet1.xml", sheet);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void countingAStoredMinimalPackageLeavesItReadable() throws Exception {
        byte[] original = minimalXlsx();
        Path stored = dir.resolve("upload.xlsx");
        Files.write(stored, original);

        assertThat(ExcelUtils.countDataRows(stored)).isEqualTo(2);

        // What ImportJobRunner then does with the same file — the symptom of B-18.
        try (InputStream in = Files.newInputStream(stored); Workbook wb = new XSSFWorkbook(in)) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(2);
        }
        // The cause: opening the file read-write let POI save the package back on close.
        assertThat(Arrays.equals(Files.readAllBytes(stored), original))
                .as("counting rows must not rewrite the stored upload")
                .isTrue();
    }
}
