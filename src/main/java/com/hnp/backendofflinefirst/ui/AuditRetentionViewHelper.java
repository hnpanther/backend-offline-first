package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.AuditRetentionProgress;

/** Persian display for audit retention progress messages (English in {@link com.hnp.backendofflinefirst.service.AuditRetentionService}). */
public final class AuditRetentionViewHelper {

    private AuditRetentionViewHelper() {}

    public static String messageFa(AuditRetentionProgress progress) {
        String en = progress.getMessage();
        if (en == null || en.isBlank()) {
            return "";
        }
        if (en.contains(" audit rows older than ") && en.endsWith(" days were deleted.")) {
            int idx = en.indexOf(" audit rows older than ");
            String count = en.substring(0, idx);
            String days = en.substring(idx + " audit rows older than ".length(), en.indexOf(" days were deleted."));
            return count + " ردیف قدیمی‌تر از " + days + " روز حذف شد.";
        }
        if (en.startsWith("Operation stopped — ") && en.endsWith(" rows deleted so far.")) {
            String count = en.substring("Operation stopped — ".length(), en.length() - " rows deleted so far.".length());
            return "عملیات متوقف شد — " + count + " ردیف تا این لحظه حذف شده بود.";
        }
        return switch (en) {
            case "Purge started." -> "پاکسازی شروع شد.";
            case "Purge completed." -> "پاکسازی به پایان رسید.";
            case "Purge cancelled." -> "پاکسازی متوقف شد.";
            case "Purge failed." -> "پاکسازی با خطا مواجه شد.";
            case "No rows to delete." -> "ردیفی برای حذف نبود.";
            default -> en;
        };
    }

    public static String statusFa(AuditRetentionProgress progress) {
        if (progress == null || progress.getStatus() == null) {
            return FaMessages.UNKNOWN;
        }
        return switch (progress.getStatus()) {
            case IDLE -> "آماده";
            case RUNNING -> "در حال اجرا";
            case COMPLETED -> "پایان یافته";
            case CANCELLED -> "لغو شده";
            case FAILED -> "خطا";
        };
    }
}
