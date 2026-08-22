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

    public static String templateScheduleOverlapWarning() {
        return "توجه: مهلت تکمیل تنظیم‌شده از بازه‌ی تکرار زمان‌بندی بیشتر است؛ ممکن است چند لاگ‌شیت هم‌زمان و باز برای همین قالب انباشته شود.";
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

    public static String fieldDefinitionNotInClass() {
        return "این فیلد متعلق به این کلاس دارایی نیست.";
    }

    /**
     * Names the rejected value so a mistyped import or a stale form is diagnosable.
     *
     * <p>The value is submitted input. Thymeleaf's {@code th:text} escapes it, so echoing it is
     * safe, but its length is unbounded — clipped here so a huge POST cannot turn into a huge
     * toast.
     */
    public static String fieldDefinitionInvalidDataType(String dataType) {
        String shown = dataType == null ? "" : dataType;
        if (shown.length() > 40) {
            shown = shown.substring(0, 40) + "…";
        }
        return "نوع دادهٔ «" + shown + "» معتبر نیست و فیلد ذخیره نشد.";
    }

    public static String settingsSaved() {
        return "تنظیمات ذخیره شد.";
    }

    public static String apiSessionRevoked() {
        return "نشست ابطال شد؛ دستگاه در اولین ارتباط با سرور خارج می‌شود.";
    }

    public static String apiSessionsRevokedForUser(int count) {
        return count == 0
                ? "این کاربر نشست فعالی ندارد."
                : count + " نشست فعال این کاربر ابطال شد.";
    }

    /**
     * Deactivation closed the account's live sessions — said plainly, with the counts.
     *
     * <p>The numbers matter: "0 نشست" tells an administrator the person was not logged in
     * anywhere, which is a different situation from having just kicked them off a tablet
     * mid-round, and they should not have to guess which one happened.
     */
    public static String userDeactivatedAndSessionsClosed(int apiSessions, int webSessions) {
        int total = apiSessions + webSessions;
        if (total == 0) {
            return "کاربر غیرفعال شد. نشست فعالی برای این کاربر وجود نداشت.";
        }
        StringBuilder message = new StringBuilder("کاربر غیرفعال شد و دسترسی او بلافاصله قطع شد (");
        if (apiSessions > 0) {
            message.append(apiSessions).append(" نشست اپ موبایل");
            if (webSessions > 0) {
                message.append(" و ");
            }
        }
        if (webSessions > 0) {
            message.append(webSessions).append(" نشست وب");
        }
        return message.append(" بسته شد).").toString();
    }

    /**
     * A role change reaches the two kinds of session at different moments, and the
     * administrator has to be told which is which.
     *
     * <p>Mobile is immediate: an API request resolves its authorities from the database every
     * time, so the next request the tablet makes already has the new access — no re-login, and
     * nothing to revoke. The browser is not: a web session holds the {@code AppUserDetails}
     * captured at login, and keeps it until the person logs out or the session times out.
     *
     * <p>This used to say that <em>neither</em> applied until the next login, which was true when
     * a mobile token carried its permissions as claims. It no longer is.
     */
    public static String rolesChangedWebSessionStillOpen() {
        return "نقش‌های این کاربر تغییر کرد. نشست‌های اپ موبایل از همان درخواست بعدی، "
                + "دسترسی جدید را اعمال می‌کنند. اما اگر او مرورگری باز دارد، آن نشست تا خروج "
                + "یا پایان مهلت با دسترسی قبلی ادامه می‌دهد؛ برای اعمال فوری، نشست وب او را از "
                + "صفحه «نشست‌های وب» ببندید.";
    }

    public static String integrationKeyCreated(String clientName) {
        return "کلید یکپارچه‌سازی برای «" + clientName + "» ساخته شد. "
                + "این کلید فقط همین یک بار نمایش داده می‌شود؛ آن را ذخیره کنید.";
    }

    public static String integrationKeyEnabled() {
        return "کلید فعال شد.";
    }

    public static String integrationKeyDisabled() {
        return "کلید غیرفعال شد؛ درخواست‌های بعدی با این کلید رد می‌شوند.";
    }

    public static String integrationKeyRevoked() {
        return "کلید برای همیشه ابطال شد و دیگر قابل فعال‌سازی نیست.";
    }

    public static String webSessionExpired() {
        return "نشست وب ابطال شد؛ کاربر در اولین درخواست بعدی خارج می‌شود.";
    }

    public static String loginAttemptUnlocked(String username) {
        return "قفل کاربر «" + username + "» باز شد.";
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
        return "لاگ‌شیت تکمیل‌شده باز شد و مهلت جدید تنظیم گردید.";
    }

    public static String logSheetVoided() {
        return "لاگ‌شیت ابطال شد و از گزارش‌های پارامتر خارج گردید.";
    }

    public static String logSheetUnvoided() {
        return "ابطال لاگ‌شیت لغو شد و وضعیت دوباره تکمیل‌شده شد.";
    }

    public static String logSheetCancelled() {
        return "لاگ‌شیت لغو شد.";
    }

    public static String nfcFaultReportCreated() {
        return "گزارش خرابی NFC ثبت شد؛ ثبت دستی برای این دارایی در همین لاگ‌شیت باز شد.";
    }

    public static String nfcFaultReportDeleted() {
        return "گزارش خرابی NFC حذف شد.";
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

    public static String importJobCancelRequested() {
        return "درخواست توقف ثبت شد. عملیات پس از اتمام دستهٔ جاری متوقف می‌شود.";
    }

    public static String importJobDeleted() {
        return "عملیات ورود حذف شد.";
    }

    public static String importJobAbandoned() {
        return "عملیات رها شد و وضعیت آن «خطا» ثبت گردید. اکنون می‌توانید ورود جدیدی را شروع کنید.";
    }

    public static String bulkDeleted(int count, String entityLabelFa) {
        return count + " " + entityLabelFa + " حذف شد.";
    }

    public static String bulkDeletedPartial(int success, int failed, String entityLabelFa) {
        return success + " " + entityLabelFa + " حذف شد؛ " + failed + " مورد به‌دلیل وابستگی یا خطا حذف نشد.";
    }

    public static String logSheetFromTemplateCreated() {
        return "لاگ‌شیت با موفقیت از قالب ساخته شد.";
    }

    public static String customLogSheetCreated() {
        return "لاگ‌شیت سفارشی با موفقیت ساخته شد.";
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
