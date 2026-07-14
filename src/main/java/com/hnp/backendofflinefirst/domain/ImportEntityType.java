package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.security.PermissionCodes;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum ImportEntityType {
    LOCATIONS("locations", "مکان‌ها", PermissionCodes.POST_LOCATIONS_IMPORT, "/locations/import-template"),
    PLANT_SYSTEMS("plant-systems", "سیستم‌های واحد", PermissionCodes.POST_PLANT_SYSTEMS_IMPORT, "/plant-systems/import-template"),
    MAIN_FUNCTIONS("main-functions", "توابع اصلی", PermissionCodes.POST_MAIN_FUNCTIONS_IMPORT, "/main-functions/import-template"),
    SUB_FUNCTIONS("sub-functions", "توابع فرعی", PermissionCodes.POST_SUB_FUNCTIONS_IMPORT, "/sub-functions/import-template"),
    ASSET_ENTRIES("asset-entries", "دارایی‌ها", PermissionCodes.POST_ASSET_ENTRIES_IMPORT, "/asset-entries/import-template"),
    USERS("users", "کاربران", "POST:/users/import", "/users/import-template"),
    OPERATIONAL_UNITS("operational-units", "واحدهای عملیاتی", PermissionCodes.POST_OPERATIONAL_UNITS_IMPORT, "/operational-units/import-template"),
    UNIT_STAFF("unit-staff", "سرپرست/اپراتور واحد", PermissionCodes.POST_OPERATIONAL_UNITS_IMPORT_STAFF, "/operational-units/import-staff-template");

    private final String code;
    private final String faLabel;
    private final String importPermission;
    private final String templatePath;

    public static Optional<ImportEntityType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code.trim()))
                .findFirst();
    }
}
