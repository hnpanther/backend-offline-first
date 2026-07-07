package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.AuditRetentionProgress;
import com.hnp.backendofflinefirst.dto.ImportResult;

import java.util.List;

/**
 * Persian user-facing strings for web flash messages, summaries, and UI helpers.
 * Service layer must use English only.
 */
public final class FaMessages {

    private FaMessages() {}

    public static final String UNKNOWN = "نامشخص";

    public static String apiUnauthorized() {
        return "لطفاً وارد شوید.";
    }

    public static String apiAccessDenied() {
        return "دسترسی مجاز نیست.";
    }

    public static String apiBadCredentials() {
        return "نام کاربری یا رمز عبور نادرست است.";
    }

    public static String genericError() {
        return "خطای نامشخص.";
    }

    public static String referentialIntegrityError() {
        return "این رکورد به داده‌های وابسته متصل است و قابل حذف نیست. ابتدا وابستگی‌ها را حذف کنید.";
    }

    public static String fileProcessingError(Throwable e) {
        String detail = e.getMessage() != null ? ErrorTranslator.toFa(e.getMessage()) : genericError();
        return "خطا در پردازش فایل: " + detail;
    }

    public static String assetCreated() {
        return "دارایی با موفقیت ثبت شد.";
    }

    public static String assetUpdated() {
        return "دارایی با موفقیت به‌روزرسانی شد.";
    }

    public static String assetDeleted() {
        return "دارایی حذف شد.";
    }

    public static String locationCreated() {
        return "مکان با موفقیت ثبت شد.";
    }

    public static String locationUpdated() {
        return "مکان با موفقیت به‌روزرسانی شد.";
    }

    public static String locationDeleted() {
        return "مکان حذف شد.";
    }

    public static String unitCreated() {
        return "واحد عملیاتی با موفقیت ثبت شد.";
    }

    public static String unitUpdated() {
        return "واحد عملیاتی با موفقیت به‌روزرسانی شد.";
    }

    public static String unitDeleted() {
        return "واحد عملیاتی حذف شد.";
    }

    public static String systemCreated() {
        return "سیستم با موفقیت ثبت شد.";
    }

    public static String systemUpdated() {
        return "سیستم با موفقیت به‌روزرسانی شد.";
    }

    public static String systemDeleted() {
        return "سیستم حذف شد.";
    }

    public static String mainFunctionCreated() {
        return "تابع اصلی با موفقیت ثبت شد.";
    }

    public static String mainFunctionUpdated() {
        return "تابع اصلی با موفقیت به‌روزرسانی شد.";
    }

    public static String mainFunctionDeleted() {
        return "تابع اصلی حذف شد.";
    }

    public static String subFunctionCreated() {
        return "زیرتابع با موفقیت ثبت شد.";
    }

    public static String subFunctionUpdated() {
        return "زیرتابع با موفقیت به‌روزرسانی شد.";
    }

    public static String subFunctionDeleted() {
        return "زیرتابع حذف شد.";
    }

    public static String userCreated() {
        return "کاربر با موفقیت ثبت شد.";
    }

    public static String userUpdated() {
        return "کاربر با موفقیت به‌روزرسانی شد.";
    }

    public static String userDeleted() {
        return "کاربر حذف شد.";
    }

    public static String roleCreated() {
        return "نقش با موفقیت ثبت شد.";
    }

    public static String roleUpdated() {
        return "نقش با موفقیت به‌روزرسانی شد.";
    }

    public static String roleDeleted() {
        return "نقش حذف شد.";
    }

    public static String templateCreated() {
        return "قالب با موفقیت ثبت شد.";
    }

    public static String templateUpdated() {
        return "قالب با موفقیت به‌روزرسانی شد.";
    }

    public static String templateDeleted() {
        return "قالب حذف شد.";
    }

    public static String assetClassCreated() {
        return "کلاس دارایی با موفقیت ثبت شد.";
    }

    public static String assetClassUpdated() {
        return "کلاس دارایی با موفقیت به‌روزرسانی شد.";
    }

    public static String assetClassDeleted() {
        return "کلاس دارایی حذف شد.";
    }

    public static String fieldDefinitionCreated() {
        return "فیلد با موفقیت ثبت شد.";
    }

    public static String fieldDefinitionUpdated() {
        return "فیلد با موفقیت به‌روزرسانی شد.";
    }

    public static String fieldDefinitionDeleted() {
        return "فیلد حذف شد.";
    }

    public static String settingsSaved() {
        return "تنظیمات ذخیره شد.";
    }

    public static String auditPurgeStarted() {
        return "پاکسازی audit شروع شد.";
    }

    public static String auditPurgeCancelled() {
        return "درخواست توقف پاکسازی ثبت شد.";
    }

    public static String logSheetCompleted() {
        return "لاگ‌شیت با موفقیت تکمیل شد.";
    }

    public static String logSheetClaimed() {
        return "لاگ‌شیت به شما اختصاص یافت.";
    }

    public static String logSheetReleased() {
        return "لاگ‌شیت برگردانده شد.";
    }

    public static String logSheetAssigned() {
        return "لاگ‌شیت به اپراتور انتساب داده شد.";
    }

    public static String logSheetReassigned() {
        return "لاگ‌شیت بازانتساب شد.";
    }

    public static String logSheetExtended() {
        return "مهلت لاگ‌شیت تمدید شد.";
    }

    public static String logSheetAdminReopened() {
        return "لاگ‌شیت نهایی‌شده بازگشایی شد و مهلت تکمیل جدید تنظیم گردید.";
    }

    public static String logSheetTakenOver() {
        return "لاگ‌شیت توسط سرپرست تصاحب شد.";
    }

    public static String auditPurgeStartedBackground() {
        return "پاکسازی audit در پس‌زمینه شروع شد. وضعیت را در همین صفحه می‌توانید ببینید.";
    }

    public static String auditPurgeCancelRequested() {
        return "درخواست توقف ثبت شد. عملیات پس از اتمام دستهٔ جاری متوقف می‌شود.";
    }

    public static String logSheetFromTemplateCreated() {
        return "لاگ‌شیت با موفقیت از قالب ساخته شد.";
    }

    public static String logSheetDraftSaved() {
        return "پیش‌نویس ذخیره شد.";
    }

    public static String mobileAppCompletionOnly() {
        return "تکمیل این لاگ‌شیت فقط از طریق اپ موبایل امکان‌پذیر است.";
    }

    public static String logSheetWebCompletionDenied() {
        return "تکمیل در وب برای شما مجاز نیست.";
    }

    public static String logSheetTakenOverNotice() {
        return "لاگ‌شیت تصاحب شد؛ سینک بعدی اپراتور ابطال خواهد شد.";
    }

    public static String passwordChanged() {
        return "رمز عبور با موفقیت تغییر کرد.";
    }

    public static String passwordMismatch() {
        return "رمز عبور و تکرار آن یکسان نیست.";
    }

    public static String passwordTooShort() {
        return "رمز عبور باید حداقل ۶ کاراکتر باشد.";
    }

    public static String generationStarted(int count) {
        return count + " لاگ‌شیت ایجاد شد.";
    }

    public static String exportTruncated(int maxRows) {
        return "توجه: خروجی به " + maxRows + " ردیف محدود شد.";
    }

    public static String importSummary(ImportResult result) {
        return ImportDisplay.summary(result);
    }

    public static String error(Throwable e) {
        return ErrorTranslator.toFa(e.getMessage());
    }

    public static String auditRetentionMessage(AuditRetentionProgress progress) {
        if (progress == null || progress.getMessage() == null) {
            return "";
        }
        return AuditRetentionViewHelper.messageFa(progress);
    }
}
