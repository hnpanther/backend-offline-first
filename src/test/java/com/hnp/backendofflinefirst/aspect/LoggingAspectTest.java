package com.hnp.backendofflinefirst.aspect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingAspectTest {

    @Test
    void conciseError_extractsPostgresMessage() {
        String msg = LoggingAspect.conciseError(new RuntimeException(
                "could not execute statement [ERROR: update or delete on table \"asset_classes\" "
                        + "violates RESTRICT setting of foreign key constraint \"fk_asset_entries_class\" "
                        + "on table \"asset_entries\"\n"
                        + "  Detail: Key (id)=(1) is referenced from table \"asset_entries\".] "
                        + "[delete from asset_classes where id=?]"));

        assertTrue(msg.contains("ERROR:"));
        assertTrue(msg.contains("asset_entries"));
        assertTrue(msg.contains("Detail: Key (id)=(1)"));
    }
}
