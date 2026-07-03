package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AppSetting;
import com.hnp.backendofflinefirst.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppSettingsService {

    public static final String KEY_EXCEL_EXPORT_MAX_ROWS = "excel.export.max_rows";
    public static final int DEFAULT_EXCEL_EXPORT_MAX_ROWS = 10_000;
    public static final int MIN_EXCEL_EXPORT_MAX_ROWS = 100;
    public static final int MAX_EXCEL_EXPORT_MAX_ROWS = 1_000_000;

    public static final String KEY_AUDIT_RETENTION_DAYS = "audit.retention.days";
    public static final int DEFAULT_AUDIT_RETENTION_DAYS = 90;
    public static final int MIN_AUDIT_RETENTION_DAYS = 1;
    public static final int MAX_AUDIT_RETENTION_DAYS = 3650;

    private final AppSettingRepository appSettingRepository;

    public int getExcelExportMaxRows() {
        return appSettingRepository.findById(KEY_EXCEL_EXPORT_MAX_ROWS)
                .map(s -> parsePositiveInt(s.getValue(), DEFAULT_EXCEL_EXPORT_MAX_ROWS))
                .orElse(DEFAULT_EXCEL_EXPORT_MAX_ROWS);
    }

    @Transactional
    public void saveExcelExportMaxRows(int maxRows) {
        if (maxRows < MIN_EXCEL_EXPORT_MAX_ROWS || maxRows > MAX_EXCEL_EXPORT_MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Excel export max rows must be between " + MIN_EXCEL_EXPORT_MAX_ROWS + " and "
                            + MAX_EXCEL_EXPORT_MAX_ROWS + ".");
        }
        saveSetting(KEY_EXCEL_EXPORT_MAX_ROWS, String.valueOf(maxRows));
    }

    public int getAuditRetentionDays() {
        return appSettingRepository.findById(KEY_AUDIT_RETENTION_DAYS)
                .map(s -> parsePositiveInt(s.getValue(), DEFAULT_AUDIT_RETENTION_DAYS))
                .orElse(DEFAULT_AUDIT_RETENTION_DAYS);
    }

    @Transactional
    public void saveAuditRetentionDays(int days) {
        if (days < MIN_AUDIT_RETENTION_DAYS || days > MAX_AUDIT_RETENTION_DAYS) {
            throw new IllegalArgumentException(
                    "Audit retention days must be between " + MIN_AUDIT_RETENTION_DAYS + " and "
                            + MAX_AUDIT_RETENTION_DAYS + " days.");
        }
        saveSetting(KEY_AUDIT_RETENTION_DAYS, String.valueOf(days));
    }

    @Transactional
    public void saveAll(int excelExportMaxRows, int auditRetentionDays) {
        saveExcelExportMaxRows(excelExportMaxRows);
        saveAuditRetentionDays(auditRetentionDays);
    }

    private void saveSetting(String key, String value) {
        AppSetting setting = appSettingRepository.findById(key)
                .orElseGet(() -> {
                    AppSetting s = new AppSetting();
                    s.setSettingKey(key);
                    return s;
                });
        setting.setValue(value);
        setting.setUpdatedAt(System.currentTimeMillis());
        appSettingRepository.save(setting);
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
