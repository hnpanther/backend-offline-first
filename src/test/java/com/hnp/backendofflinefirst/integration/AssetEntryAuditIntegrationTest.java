package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AuditLog;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AuditLogRepository;
import com.hnp.backendofflinefirst.service.AssetEntryService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies UPDATE audit captures pre-change values even when the managed entity is
 * mutated before {@code repository.save} (Hibernate L1 / auto-flush trap).
 */
class AssetEntryAuditIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetEntryService assetEntryService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void managedAssetUpdateRecordsPreviousNfcInAuditLog() throws Exception {
        long now = System.currentTimeMillis();
        AssetEntry created = new AssetEntry();
        created.setAssetCode("AST-AUDIT-" + now);
        created.setAssetName("پمپ شماره یک");
        created.setDescription("پمپ تست audit");
        created.setNfcTagId("NFC-111-" + now);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        created = assetEntryRepository.saveAndFlush(created);
        Long assetId = created.getId();

        AssetEntry form = new AssetEntry();
        form.setAssetCode(created.getAssetCode());
        form.setAssetName(created.getAssetName());
        form.setDescription(created.getDescription());
        form.setNfcTagId("NFC-999-" + now);
        form.setClassId(created.getClassId());
        form.setSubFunctionId(created.getSubFunctionId());

        assetEntryService.update(assetId, form);

        AssetEntry reloaded = assetEntryRepository.findById(assetId).orElseThrow();
        assertThat(reloaded.getNfcTagId()).isEqualTo("NFC-999-" + now);

        AuditLog updateLog = awaitAudit(assetId, AuditAction.UPDATE);
        assertThat(updateLog.getChanges()).anySatisfy(change -> {
            assertThat(change.get("field")).isEqualTo("nfcTagId");
            assertThat(change.get("oldValue")).isEqualTo("NFC-111-" + now);
            assertThat(change.get("newValue")).isEqualTo("NFC-999-" + now);
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createStillRecordsAuditWithoutOldValues() throws Exception {
        long now = System.currentTimeMillis();
        AssetEntry created = new AssetEntry();
        created.setAssetCode("AST-CREATE-AUDIT-" + now);
        created.setAssetName("Asset create audit");
        created.setNfcTagId("NFC-CREATE-" + now);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        created = assetEntryRepository.saveAndFlush(created);
        Long assetId = created.getId();

        AuditLog createLog = awaitAudit(assetId, AuditAction.CREATE);
        assertThat(createLog.getChanges()).anySatisfy(change ->
                assertThat(change).containsEntry("field", "nfcTagId")
                        .containsEntry("oldValue", "")
                        .containsEntry("newValue", "NFC-CREATE-" + now));
    }

    private AuditLog awaitAudit(Long assetId, AuditAction action) throws Exception {
        return pollUntil(10_000, () -> {
            List<AuditLog> logs = auditLogRepository
                    .findByEntityTypeAndEntityIdOrderByRecordedAtDesc("asset_entries", String.valueOf(assetId));
            return logs.stream()
                    .filter(log -> log.getAction() == action)
                    .findFirst()
                    .orElse(null);
        });
    }

    private static <T> T pollUntil(long timeoutMs, Callable<T> probe) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            T value = probe.call();
            if (value != null) {
                return value;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for audit row");
    }
}
