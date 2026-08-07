package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.AttachmentStorageService;
import com.hnp.backendofflinefirst.service.AttachmentSweepService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The orphan sweep, and above all the rail that stops it eating live data.
 *
 * <p>This job deletes files. The failure that matters is not "an orphan survived" — that costs
 * some disk — it is "a file belonging to a real attachment was deleted", which costs an
 * operator's evidence and cannot be undone. Most of what follows is about that direction.
 */
/*
 * Deliberately NOT @Transactional. The sweep runs on its own thread with its own transaction,
 * so a row written inside a rolled-back test transaction would be invisible to it — and the
 * sweep would then delete a file that is, as far as the test is concerned, referenced. Running
 * against committed data is also closer to what actually happens. Cleanup is explicit in setUp.
 */
class AttachmentSweepIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AttachmentSweepService sweepService;
    @Autowired AttachmentStorageService storageService;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;

    private Path root;
    private Long sheetId;
    private Long assetId;

    @BeforeEach
    void setUp() throws IOException {
        seedSheet();
        root = storageService.getRoot();
        Files.createDirectories(root);
        attachmentRepository.deleteAll();
        // A shared build-local root across tests: clear it so counts are about this test only.
        if (Files.isDirectory(root)) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    /** Writes a file under the root and returns the storage key that would reference it. */
    private String writeFile(String name, int ageHours) throws IOException {
        Path dir = root.resolve("2026/01/01");
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, new byte[] {1, 2, 3, 4});
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(System.currentTimeMillis() - ageHours * 3_600_000L));
        return "2026/01/01/" + name;
    }

    /**
     * A real sheet and asset, because {@code attachments} has genuine foreign keys and this test
     * commits for real (no rollback) so the sweep's own thread can see the rows.
     */
    private void seedSheet() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("SWEEP-BU-" + nano);
        unit.setName("Sweep Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("SWEEP-LOC-" + nano);
        location.setName("Sweep Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SWEEP-SF-" + nano);
        subFunction.setName("Sweep Sub");
        subFunction.setTag("NFC-SWEEP-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Sweep Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("SWEEP-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetId = assetEntryRepository.save(asset).getId();

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Sweep Template " + nano);
        template.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        template.setScopeId(location.getId());
        template.setClassId(assetClass.getId());
        template.setOperationalUnitId(unit.getId());
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        sheetId = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now).getId();
    }

    private Attachment row(String storageKey) {
        Attachment a = new Attachment();
        a.setId(UUID.randomUUID().toString());
        a.setLogSheetId(sheetId);
        a.setAssetId(assetId);
        a.setFieldKey("pump_photo");
        a.setKind(AttachmentKind.IMAGE);
        a.setMimeType("image/png");
        a.setSizeBytes(4L);
        a.setStorageKey(storageKey);
        a.setUploadedAt(System.currentTimeMillis());
        return attachmentRepository.save(a);
    }

    // -----------------------------------------------------------------------
    // The grace period — the part that must never regress
    // -----------------------------------------------------------------------

    @Test
    void neverDeletesAFileYoungerThanTheGracePeriod() throws Exception {
        // This is the in-flight upload: store() has written the bytes and the transaction has
        // not committed, so it has no row *yet*. Deleting it would destroy a photo an operator
        // just took, and leave the row that follows pointing at nothing.
        String key = writeFile("in-flight.png", 0);

        sweepService.startSweep();
        awaitIdle();

        assertThat(storageService.exists(key)).isTrue();
        assertThat(sweepService.getProgress().getDeletedCount()).isZero();
    }

    @Test
    void deletesAnOldFileWithNoRow() throws Exception {
        // The common case: a log sheet was deleted, the FK cascade removed the rows, and
        // nothing ever told the filesystem.
        String key = writeFile("orphan.png", 48);

        sweepService.startSweep();
        awaitIdle();

        assertThat(storageService.exists(key)).isFalse();
        assertThat(sweepService.getProgress().getDeletedCount()).isEqualTo(1);
        assertThat(sweepService.getProgress().getReclaimedBytes()).isEqualTo(4);
    }

    @Test
    void keepsAnOldFileThatIsStillReferenced() throws Exception {
        String key = writeFile("referenced.png", 48);
        row(key);

        sweepService.startSweep();
        awaitIdle();

        assertThat(storageService.exists(key)).isTrue();
        assertThat(sweepService.getProgress().getDeletedCount()).isZero();
    }

    @Test
    void separatesReferencedFromOrphanedInTheSamePass() throws Exception {
        String kept = writeFile("kept.png", 48);
        String dropped = writeFile("dropped.png", 48);
        String young = writeFile("young.png", 1);
        row(kept);

        sweepService.startSweep();
        awaitIdle();

        assertThat(storageService.exists(kept)).isTrue();
        assertThat(storageService.exists(young)).isTrue();
        assertThat(storageService.exists(dropped)).isFalse();
        assertThat(sweepService.getProgress().getDeletedCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------

    @Test
    void estimateCountsWithoutDeletingAnything() throws Exception {
        String key = writeFile("orphan.png", 48);

        AttachmentSweepService.SweepEstimate estimate = sweepService.estimate();

        assertThat(estimate.orphanCount()).isEqualTo(1);
        assertThat(estimate.orphanBytes()).isEqualTo(4);
        // The whole point of an estimate: the administrator has not agreed to anything yet.
        assertThat(storageService.exists(key)).isTrue();
    }

    @Test
    void reportsOrphansStillInsideTheGracePeriodSeparately() throws Exception {
        // Without this the page would show a reassuring "0 to delete" while dead files sat on
        // the disk, and an administrator would have no idea the space was being consumed.
        writeFile("young-orphan.png", 1);
        writeFile("old-orphan.png", 48);

        AttachmentSweepService.SweepEstimate estimate = sweepService.estimate();

        assertThat(estimate.orphanCount()).isEqualTo(1);
        assertThat(estimate.youngOrphanCount()).isEqualTo(1);
    }

    @Test
    void doesNotCountAReferencedYoungFileAsAnOrphan() throws Exception {
        String key = writeFile("young-referenced.png", 1);
        row(key);

        assertThat(sweepService.estimate().youngOrphanCount()).isZero();
    }

    @Test
    void reportsRowsWhoseFileIsMissingWithoutTouchingThem() {
        Attachment orphanRow = row("2026/01/01/never-written-" + System.nanoTime() + ".png");

        long missing = sweepService.countRowsWithMissingFiles();

        assertThat(missing).isEqualTo(1);
        // Deleting the row would erase the only remaining evidence that a file was lost, which
        // is exactly when an administrator most needs to see it.
        assertThat(attachmentRepository.findById(orphanRow.getId())).isPresent();
    }

    @Test
    void anEmptyStorageRootIsNotAnError() throws Exception {
        sweepService.startSweep();
        awaitIdle();

        assertThat(sweepService.getProgress().getStatus())
                .isEqualTo(com.hnp.backendofflinefirst.dto.AttachmentSweepProgress.Status.COMPLETED);
        assertThat(sweepService.getProgress().getDeletedCount()).isZero();
    }

    @Test
    void refusesToCancelWhenNothingIsRunning() {
        // The guard exists so a stray click cannot mark a future sweep as pre-cancelled.
        assertThatThrownBy(() -> sweepService.requestCancel())
                .isInstanceOf(IllegalStateException.class);
    }

    private void awaitIdle() throws InterruptedException {
        for (int i = 0; i < 100 && sweepService.isRunning(); i++) {
            Thread.sleep(50);
        }
        assertThat(sweepService.isRunning()).isFalse();
    }
}
