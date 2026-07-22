package com.hnp.backendofflinefirst.audit;

import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEntitySupportTest {

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
