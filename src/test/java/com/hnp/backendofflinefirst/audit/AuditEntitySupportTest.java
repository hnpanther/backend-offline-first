package com.hnp.backendofflinefirst.audit;

import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.entity.ImportJobError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEntitySupportTest {

    /**
     * The bookkeeping of an import job is not part of the audit trail.
     *
     * <p>This is a correctness rule, not a tidiness one. {@code ImportJob} is re-saved every
     * 25 rows by the progress listener and {@code ImportJobError} once per stored error row,
     * so on a real 9,942-row asset import those two produced 2,574 of 4,503 audit rows — and
     * the queue they filled then rejected the very save that writes the job's final status,
     * leaving it stuck at RUNNING with no way out but a restart. Writing a job's status must
     * not depend on the audit pipeline the job is saturating.
     */
    @Test
    void importJobBookkeepingIsNotAudited() {
        assertThat(AuditEntitySupport.shouldAudit(new ImportJob())).isFalse();
        assertThat(AuditEntitySupport.shouldAudit(new ImportJobError())).isFalse();
    }

    @Test
    void theEntitiesAnImportActuallyCreatesAreStillAudited() {
        // Only the job's own paperwork is exempt. Losing the trail for imported master data
        // would be a real regression — that is what an import is for.
        assertThat(AuditEntitySupport.shouldAudit(new AssetEntry())).isTrue();
    }

    @Test
    void captureFieldValuesIsIndependentOfLaterMutation() {
        AssetEntry entity = new AssetEntry();
        entity.setId(10L);
        entity.setAssetCode("AST-100");
        entity.setNfcTagId("NFC-111");
        entity.setAssetName("پمپ شماره یک");

        Map<String, Object> snapshot = AuditEntitySupport.captureFieldValues(entity);
        entity.setNfcTagId("NFC-999");

        assertThat(snapshot.get("nfcTagId")).isEqualTo("NFC-111");
        assertThat(entity.getNfcTagId()).isEqualTo("NFC-999");
    }

    @Test
    void diffDetectsChangeWhenOldStateIsMapSnapshot() {
        AssetEntry entity = new AssetEntry();
        entity.setId(10L);
        entity.setAssetCode("AST-100");
        entity.setNfcTagId("NFC-111");
        entity.setAssetName("پمپ شماره یک");

        Map<String, Object> oldSnapshot = AuditEntitySupport.captureFieldValues(entity);
        entity.setNfcTagId("NFC-999");

        List<AuditFieldChange> changes = AuditEntitySupport.diff(oldSnapshot, entity, AuditAction.UPDATE);

        assertThat(changes)
                .anySatisfy(change -> {
                    assertThat(change.field()).isEqualTo("nfcTagId");
                    assertThat(change.oldValue()).isEqualTo("NFC-111");
                    assertThat(change.newValue()).isEqualTo("NFC-999");
                });
    }

    @Test
    void diffOfManagedStyleMutationWithoutSnapshotLooksUnchanged() {
        AssetEntry entity = new AssetEntry();
        entity.setId(10L);
        entity.setNfcTagId("NFC-111");
        entity.setAssetCode("AST-100");
        entity.setAssetName("pump");

        entity.setNfcTagId("NFC-999");
        // Simulates the old bug: "old" and "new" are the same mutated instance.
        List<AuditFieldChange> changes = AuditEntitySupport.diff(entity, entity, AuditAction.UPDATE);

        assertThat(changes).isEmpty();
    }

    @Test
    void captureFromLoadedStateUsesPreDirtyPropertyValues() {
        AssetEntry entity = new AssetEntry();
        entity.setId(42L);
        entity.setAssetCode("AST-100");
        entity.setNfcTagId("NFC-999"); // already dirty in memory
        entity.setAssetName("pump");

        Map<String, Object> snapshot = AuditEntitySupport.captureFromLoadedState(
                entity,
                new String[]{"assetCode", "nfcTagId", "assetName", "description", "classId", "subFunctionId"},
                new Object[]{"AST-100", "NFC-111", "pump", null, null, null});

        assertThat(snapshot.get("nfcTagId")).isEqualTo("NFC-111");
        assertThat(snapshot.get("id")).isEqualTo(42L);
        assertThat(snapshot).doesNotContainKey("updatedAt");
    }
}
