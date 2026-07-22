package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldDefinitionMapsTest {

    @Test
    void toEmbeddedFieldsPreservesAllDefinitionsForClient() {
        FieldDefinition temp = new FieldDefinition();
        temp.setId(1L);
        temp.setClassId(7L);
        temp.setKey("temp");
        temp.setLabel("Temperature");
        temp.setDataType("number");
        temp.setRequired(true);

        FieldDefinition bar = new FieldDefinition();
        bar.setId(2L);
        bar.setClassId(7L);
        bar.setKey("Bar");
        bar.setLabel("Bar");
        bar.setDataType("number");
        bar.setRequired(false);

        List<Map<String, Object>> embedded = FieldDefinitionMaps.toEmbeddedFields(List.of(temp, bar));

        assertThat(embedded).hasSize(2);
        assertThat(embedded.get(0)).containsEntry("key", "temp").containsEntry("id", 1L);
        assertThat(embedded.get(1)).containsEntry("key", "Bar").containsEntry("id", 2L);
    }

    @Test
    void copyWithEmbeddedFieldsDoesNotMutateSource() {
        AssetClass source = new AssetClass();
        source.setId(7L);
        source.setName("Pump");
        source.setFields(List.of(Map.of("key", "stale")));

        FieldDefinition temp = new FieldDefinition();
        temp.setId(1L);
        temp.setClassId(7L);
        temp.setKey("temp");
        temp.setLabel("Temperature");
        temp.setDataType("number");

        AssetClass copy = FieldDefinitionMaps.copyWithEmbeddedFields(source, List.of(temp));

        assertThat(copy.getFields()).hasSize(1);
        assertThat(copy.getFields().get(0)).containsEntry("key", "temp");
        assertThat(source.getFields()).hasSize(1);
        assertThat(source.getFields().get(0)).containsEntry("key", "stale");
    }
}
