package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ImportEntityType;
import com.hnp.backendofflinefirst.dto.ImportResult;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.service.ExcelImportService;
import com.hnp.backendofflinefirst.service.importjob.ImportJobService;
import com.hnp.backendofflinefirst.service.importjob.PathMultipartFile;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import com.hnp.backendofflinefirst.util.ExcelUtilsMinimalPackageTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue B-18 through the batch path: a minimal hand-written workbook is submitted to the batch
 * import, the stored upload is unchanged by the row count taken at submit, and the same stored
 * file then imports exactly as {@code ImportJobRunner} reads it.
 *
 * <p>{@code @Transactional}: the job's async run is registered after commit, so it never starts
 * here and the test drives the runner's read itself.
 */
@Transactional
class BatchImportMinimalPackageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired ImportJobService importJobService;
    @Autowired ExcelImportService excelImportService;
    @Autowired LocationRepository locationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @WithAppUser(username = "imp-admin", roles = "ADMIN",
            authorities = {"POST:/locations/import"})
    void aMinimalPackageSubmittedToTheBatchPathImportsItsRows() throws Exception {
        ensureOwnerExists();
        byte[] original = ExcelUtilsMinimalPackageTest.minimalXlsx();
        MockMultipartFile upload = new MockMultipartFile("file", "minimal.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", original);

        ImportJob job = importJobService.submit(ImportEntityType.LOCATIONS, upload, 1L);
        Path stored = Path.of(job.getFilePath());
        try {
            assertThat(job.getTotalRows()).isEqualTo(2);
            assertThat(Arrays.equals(Files.readAllBytes(stored), original))
                    .as("the row count at submit must not rewrite the stored upload")
                    .isTrue();

            ImportResult result = excelImportService.importEntity(
                    ImportEntityType.LOCATIONS, new PathMultipartFile(stored), null);

            assertThat(result.getErrors()).isEmpty();
            assertThat(locationRepository.findByCodeIgnoreCase("B18-A")).isPresent();
            assertThat(locationRepository.findByCodeIgnoreCase("B18-B")).isPresent();
        } finally {
            Files.deleteIfExists(stored);
        }
    }

    private void ensureOwnerExists() {
        Integer present = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = 1", Integer.class);
        if (present != null && present > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO users (id, username, password_hash, auth_type, full_name,
                                   personnel_code, active, created_at, updated_at)
                VALUES (1, 'import-test-owner', 'x', 'LOCAL', 'Import Test Owner', 'PC-IMPORT-TEST',
                        TRUE, ?, ?)
                """, System.currentTimeMillis(), System.currentTimeMillis());
    }
}
