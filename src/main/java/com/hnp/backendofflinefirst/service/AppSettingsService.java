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

    public static final String KEY_JWT_EXPIRY_MINUTES = "auth.jwt.expiry_minutes";
    public static final int DEFAULT_JWT_EXPIRY_MINUTES = 480;
    public static final int MIN_JWT_EXPIRY_MINUTES = 5;
    public static final int MAX_JWT_EXPIRY_MINUTES = 10_080;

    // --- Attachment ceilings -------------------------------------------------
    // Counts are per field per asset. Upper bounds are sanity rails, not targets: at 3 photos
    // on a 50-asset daily sheet the storage arithmetic already runs to tens of GB a year, and
    // the ceilings exist so a mis-set value cannot quietly turn that into hundreds.
    public static final String KEY_MAX_IMAGES_PER_FIELD = "attachments.max_images_per_field";
    public static final int DEFAULT_MAX_IMAGES_PER_FIELD = 3;
    public static final String KEY_MAX_AUDIOS_PER_FIELD = "attachments.max_audios_per_field";
    public static final int DEFAULT_MAX_AUDIOS_PER_FIELD = 1;
    public static final String KEY_MAX_VIDEOS_PER_FIELD = "attachments.max_videos_per_field";
    public static final int DEFAULT_MAX_VIDEOS_PER_FIELD = 1;
    public static final int MIN_ATTACHMENTS_PER_FIELD = 1;
    public static final int MAX_ATTACHMENTS_PER_FIELD = 10;

    public static final String KEY_MAX_AUDIO_SECONDS = "attachments.max_audio_seconds";
    public static final int DEFAULT_MAX_AUDIO_SECONDS = 120;
    public static final String KEY_MAX_VIDEO_SECONDS = "attachments.max_video_seconds";
    public static final int DEFAULT_MAX_VIDEO_SECONDS = 120;
    public static final int MIN_MEDIA_SECONDS = 5;
    public static final int MAX_MEDIA_SECONDS = 600;

    /**
     * Whether the PWA offers its annotate-before-save step after a photo is taken.
     *
     * <p>On by default: the step is what lets an operator circle the leak they photographed, and
     * a reviewer reading the sheet a week later has no other way to know which part of the frame
     * mattered. Turning it off returns the tablets to plain capture — the photo is saved exactly
     * as it is taken, which is what they did before this existed.
     *
     * <p>Stored here rather than as a property because it is an operational choice a supervisor
     * may want to reverse on a bad day, not a deployment constant. It reaches every tablet on
     * the next bootstrap.
     */
    public static final String KEY_IMAGE_ANNOTATION_ENABLED = "attachments.image_annotation_enabled";
    public static final boolean DEFAULT_IMAGE_ANNOTATION_ENABLED = true;

    /**
     * Whether a PWA scan must match both the tag's Record 1 payload and the chip's hardware
     * serial.
     *
     * <p>On by default, and the default is the point: Record 1 can be copied onto any blank tag,
     * so the serial is the only part of a scan that means "I stood in front of this equipment."
     *
     * <p>It lives here rather than in a property so it can be changed **without a restart**. The
     * case that needs that is real and time-sensitive: a site whose assets have no serials
     * recorded yet has every scan rejected, and the fix cannot wait for a deployment window.
     * Turning it off is a deliberate, audited weakening of an integrity rule — not a preference —
     * so the Settings page says so where an administrator will read it.
     */
    public static final String KEY_NFC_STRICT_SERIAL_MATCH = "nfc.strict_serial_match";
    public static final boolean DEFAULT_NFC_STRICT_SERIAL_MATCH = true;

    private final AppSettingRepository appSettingRepository;

    /**
     * Every attachment ceiling in one object.
     *
     * <p>Read as a group because that is how it is used: the fill pages render all five at once
     * and {@code /api/bootstrap} ships all five to every tablet. Five separate getters would be
     * five round trips to the settings table for one screen.
     */
    public record AttachmentLimits(int maxImagesPerField,
                                   int maxAudiosPerField,
                                   int maxVideosPerField,
                                   int maxAudioSeconds,
                                   int maxVideoSeconds) {

        /** The ceiling for one kind, or 0 when the kind takes no attachments. */
        public int maxCountFor(com.hnp.backendofflinefirst.domain.AttachmentKind kind) {
            if (kind == null) return 0;
            return switch (kind) {
                case IMAGE -> maxImagesPerField;
                case AUDIO -> maxAudiosPerField;
                case VIDEO -> maxVideosPerField;
            };
        }

        /** The duration ceiling in ms, or null for a kind that has no duration (images). */
        public Long maxDurationMsFor(com.hnp.backendofflinefirst.domain.AttachmentKind kind) {
            if (kind == null) return null;
            return switch (kind) {
                case IMAGE -> null;
                case AUDIO -> maxAudioSeconds * 1000L;
                case VIDEO -> maxVideoSeconds * 1000L;
            };
        }
    }

    public AttachmentLimits getAttachmentLimits() {
        return new AttachmentLimits(
                readInt(KEY_MAX_IMAGES_PER_FIELD, DEFAULT_MAX_IMAGES_PER_FIELD),
                readInt(KEY_MAX_AUDIOS_PER_FIELD, DEFAULT_MAX_AUDIOS_PER_FIELD),
                readInt(KEY_MAX_VIDEOS_PER_FIELD, DEFAULT_MAX_VIDEOS_PER_FIELD),
                readInt(KEY_MAX_AUDIO_SECONDS, DEFAULT_MAX_AUDIO_SECONDS),
                readInt(KEY_MAX_VIDEO_SECONDS, DEFAULT_MAX_VIDEO_SECONDS));
    }

    @Transactional
    public void saveAttachmentLimits(AttachmentLimits limits) {
        requireCount("حداکثر تعداد تصویر", limits.maxImagesPerField());
        requireCount("حداکثر تعداد صوت", limits.maxAudiosPerField());
        requireCount("حداکثر تعداد ویدئو", limits.maxVideosPerField());
        requireSeconds("حداکثر مدت صوت", limits.maxAudioSeconds());
        requireSeconds("حداکثر مدت ویدئو", limits.maxVideoSeconds());

        saveSetting(KEY_MAX_IMAGES_PER_FIELD, String.valueOf(limits.maxImagesPerField()));
        saveSetting(KEY_MAX_AUDIOS_PER_FIELD, String.valueOf(limits.maxAudiosPerField()));
        saveSetting(KEY_MAX_VIDEOS_PER_FIELD, String.valueOf(limits.maxVideosPerField()));
        saveSetting(KEY_MAX_AUDIO_SECONDS, String.valueOf(limits.maxAudioSeconds()));
        saveSetting(KEY_MAX_VIDEO_SECONDS, String.valueOf(limits.maxVideoSeconds()));
    }

    public boolean isImageAnnotationEnabled() {
        return appSettingRepository.findById(KEY_IMAGE_ANNOTATION_ENABLED)
                .map(s -> parseBoolean(s.getValue(), DEFAULT_IMAGE_ANNOTATION_ENABLED))
                .orElse(DEFAULT_IMAGE_ANNOTATION_ENABLED);
    }

    @Transactional
    public void saveImageAnnotationEnabled(boolean enabled) {
        saveSetting(KEY_IMAGE_ANNOTATION_ENABLED, String.valueOf(enabled));
    }

    public boolean isNfcStrictSerialMatch() {
        return appSettingRepository.findById(KEY_NFC_STRICT_SERIAL_MATCH)
                .map(s -> parseBoolean(s.getValue(), DEFAULT_NFC_STRICT_SERIAL_MATCH))
                .orElse(DEFAULT_NFC_STRICT_SERIAL_MATCH);
    }

    @Transactional
    public void saveNfcStrictSerialMatch(boolean enabled) {
        saveSetting(KEY_NFC_STRICT_SERIAL_MATCH, String.valueOf(enabled));
    }

    private static void requireCount(String label, int value) {
        if (value < MIN_ATTACHMENTS_PER_FIELD || value > MAX_ATTACHMENTS_PER_FIELD) {
            throw new IllegalArgumentException(label + " باید بین "
                    + MIN_ATTACHMENTS_PER_FIELD + " و " + MAX_ATTACHMENTS_PER_FIELD + " باشد.");
        }
    }

    private static void requireSeconds(String label, int value) {
        if (value < MIN_MEDIA_SECONDS || value > MAX_MEDIA_SECONDS) {
            throw new IllegalArgumentException(label + " باید بین "
                    + MIN_MEDIA_SECONDS + " و " + MAX_MEDIA_SECONDS + " ثانیه باشد.");
        }
    }

    private int readInt(String key, int fallback) {
        return appSettingRepository.findById(key)
                .map(s -> parsePositiveInt(s.getValue(), fallback))
                .orElse(fallback);
    }

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

    public int getJwtExpiryMinutes() {
        return appSettingRepository.findById(KEY_JWT_EXPIRY_MINUTES)
                .map(s -> parsePositiveInt(s.getValue(), DEFAULT_JWT_EXPIRY_MINUTES))
                .orElse(DEFAULT_JWT_EXPIRY_MINUTES);
    }

    @Transactional
    public void saveJwtExpiryMinutes(int minutes) {
        if (minutes < MIN_JWT_EXPIRY_MINUTES || minutes > MAX_JWT_EXPIRY_MINUTES) {
            throw new IllegalArgumentException(
                    "JWT expiry must be between " + MIN_JWT_EXPIRY_MINUTES + " and "
                            + MAX_JWT_EXPIRY_MINUTES + " minutes.");
        }
        saveSetting(KEY_JWT_EXPIRY_MINUTES, String.valueOf(minutes));
    }

    @Transactional
    public void saveAll(int excelExportMaxRows, int auditRetentionDays, int jwtExpiryMinutes) {
        saveExcelExportMaxRows(excelExportMaxRows);
        saveAuditRetentionDays(auditRetentionDays);
        saveJwtExpiryMinutes(jwtExpiryMinutes);
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

    /**
     * Only the exact strings this class writes count as a decision; anything else falls back.
     *
     * <p>Deliberately not {@link Boolean#parseBoolean}, which answers "false" to every value it
     * does not recognise — including a typo, an empty row, or a legacy {@code "1"}. A setting
     * whose default is ON must not be silently switched OFF by an unreadable row.
     */
    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase();
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("yes")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("no")) {
            return false;
        }
        return fallback;
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
