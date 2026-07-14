package com.hnp.backendofflinefirst.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportEntityTypeTest {

    @Test
    void fromCodeResolvesKnownTypes() {
        assertThat(ImportEntityType.fromCode("asset-entries")).contains(ImportEntityType.ASSET_ENTRIES);
        assertThat(ImportEntityType.fromCode("unit-staff")).contains(ImportEntityType.UNIT_STAFF);
    }
}
