package com.hnp.backendofflinefirst.ui;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Maps English exception / validation messages from the service layer to Persian for end users.
 */
public final class ErrorTranslator {

    private ErrorTranslator() {}

    public static String toFa(String english) {
        if (english == null || english.isBlank()) {
            return FaMessages.genericError();
        }
        if (english.startsWith("Duplicate asset code:")) {
            return "کد دارایی تکراری است:" + english.substring("Duplicate asset code:".length());
        }
        if (english.startsWith("Duplicate NFC tag:")) {
            return "شناسه NFC تکراری است:" + english.substring("Duplicate NFC tag:".length());
        }
        if (english.startsWith("Duplicate role code:")) {
            return "کد نقش تکراری است:" + english.substring("Duplicate role code:".length());
        }
        if (english.startsWith("Duplicate username:")) {
            return "نام کاربری تکراری است:" + english.substring("Duplicate username:".length());
        }
        if (english.startsWith("Missing assets on server (ids:")) {
            String suffix = english.substring("Missing assets on server (ids:".length());
            return "یک یا چند دارایی این لاگ‌شیت روی سرور وجود ندارد (شناسه‌ها:" + suffix
                    .replace("). Sync the app online to refresh asset lists.",
                            "). اپ را آنلاین کنید تا لیست دارایی‌ها به‌روز شود.");
        }
        if (english.startsWith("Template is inactive:")) {
            return "قالب غیرفعال است: " + english.substring("Template is inactive:".length());
        }
        if (english.startsWith("Excel export max rows must be between")) {
            return "حداکثر ردیف خروجی باید بین " + english.replace("Excel export max rows must be between ", "")
                    .replace(" and ", " و ").replace(".", "") + " باشد.";
        }
        if (english.startsWith("Audit retention days must be between")) {
            return "مدت نگهداری audit باید بین " + english.replace("Audit retention days must be between ", "")
                    .replace(" and ", " و ").replace(" days.", " روز باشد.");
        }
        if ("Bad credentials".equals(english) || "Invalid credentials".equals(english)) {
            return FaMessages.apiBadCredentials();
        }
        if ("Access is denied".equals(english) || "Access Denied".equals(english)) {
            return FaMessages.apiAccessDenied();
        }
        return switch (english) {
            case "Asset code is required." -> "کد دارایی اجباری است.";
            case "Audit purge is already running." -> "پاکسازی audit در حال اجراست.";
            case "No audit purge is running." -> "عملیات پاکسازی در حال اجرا نیست.";
            case "Log sheet server id was not provided." -> "شناسه سروری لاگ‌شیت ارسال نشده است.";
            case "Log sheet not found on server." -> "لاگ‌شیت روی سرور یافت نشد.";
            case "Log sheet not found." -> "لاگ‌شیت یافت نشد.";
            case "This log sheet was already completed by someone else." -> "این لاگ‌شیت قبلاً توسط شخص دیگری تکمیل شده است.";
            case "This log sheet is no longer assigned to you." -> "این لاگ‌شیت دیگر به شما تخصیص ندارد.";
            case "This log sheet completion deadline has passed." -> "مهلت تکمیل این لاگ‌شیت به پایان رسیده است.";
            case "This log sheet is already completed." -> "این لاگ‌شیت قبلاً تکمیل شده است.";
            case "Web completion is only allowed for the supervisor who claimed the sheet." ->
                    "تکمیل در وب فقط برای سرپرستی که خودش کار را برداشته مجاز است.";
            case "Log sheets can only be completed in the mobile app." ->
                    FaMessages.mobileAppCompletionOnly();
            case "You may only create templates for units you supervise." ->
                    "فقط برای واحد تحت سرپرستی خود می‌توانید قالب ایجاد کنید.";
            case "Log sheet template not found." -> "قالب لاگ‌شیت یافت نشد.";
            case "This log sheet template is inactive." -> "این قالب لاگ‌شیت غیرفعال است.";
            case "Access to this log sheet is not allowed." -> "دسترسی به این لاگ شیت مجاز نیست.";
            case "Selected operational unit is not allowed." -> "واحد عملیاتی انتخاب‌شده مجاز نیست.";
            case "Operational unit not found." -> "واحد عملیاتی یافت نشد.";
            case "Unit cannot be its own parent." -> "واحد نمی‌تواند والد خودش باشد.";
            case "This unit has child units and cannot be deleted." -> "این واحد دارای زیرمجموعه است و قابل حذف نیست.";
            case "This unit has locations and cannot be deleted." -> "این واحد دارای مکان است و قابل حذف نیست.";
            case "This unit has log sheet templates and cannot be deleted." -> "این واحد دارای قالب لاگ‌شیت است و قابل حذف نیست.";
            case "This unit has log sheets and cannot be deleted." -> "این واحد دارای لاگ‌شیت است و قابل حذف نیست.";
            case "Role not found." -> "نقش یافت نشد.";
            case "System roles cannot be deleted." -> "نقش سیستمی قابل حذف نیست.";
            case "This role is assigned to users and cannot be deleted." -> "این نقش به کاربران اختصاص داده شده و قابل حذف نیست.";
            case "User not found." -> "کاربر یافت نشد.";
            case "This user is assigned to operational units and cannot be deleted." ->
                    "این کاربر به واحد عملیاتی اختصاص داده شده و قابل حذف نیست.";
            case "Password and confirmation do not match." -> "رمز عبور و تکرار آن یکسان نیست.";
            case "Password must be at least 6 characters." -> "رمز عبور باید حداقل ۶ کاراکتر باشد.";
            case "This log sheet cannot be claimed." -> "این لاگ‌شیت قابل پیک‌آپ نیست.";
            case "This log sheet is outside your unit scope." -> "این لاگ‌شیت در محدوده واحد شما نیست.";
            case "This log sheet cannot be released." -> "این لاگ‌شیت قابل برگرداندن نیست.";
            case "Only the claimer can release this sheet." -> "فقط پیک‌آپ‌کننده می‌تواند این کار را برگرداند.";
            case "Only the unit supervisor can release an assigned sheet." ->
                    "کار انتساب‌شده را فقط سرپرست واحد می‌تواند برگرداند.";
            case "This log sheet has no assignee to release." -> "این لاگ‌شیت مسئولی برای برگرداندن ندارد.";
            case "Only unassigned pending sheets can be assigned." -> "فقط لاگ‌شیت در انتظار قابل انتساب است.";
            case "Only supervisor-assigned in-progress sheets can be reassigned." ->
                    "فقط کاری که سرپرست انتساب داده قابل بازانتساب است.";
            case "This log sheet cannot be taken over." -> "این لاگ‌شیت قابل تصاحب نیست.";
            case "You are not the supervisor of this unit." -> "شما سرپرست این واحد نیستید.";
            case "This log sheet cannot be extended." -> "این لاگ‌شیت قابل تمدید نیست.";
            case "Only submitted log sheets can be reopened." ->
                    "فقط لاگ‌شیت‌های تکمیل‌شده قابل باز کردن مجدد هستند.";
            case "New deadline must be in the future." -> "مهلت جدید باید در آینده باشد.";
            case "Only system administrators can reopen submitted log sheets." ->
                    "فقط مدیر سیستم می‌تواند لاگ‌شیت تکمیل‌شده را باز کند.";
            case "Target user is not an operator of this unit." -> "کاربر مقصد اپراتور این واحد نیست.";
            case "Web completion is not allowed." -> FaMessages.logSheetWebCompletionDenied();
            case "Template not found." -> "قالب یافت نشد.";
            default -> english;
        };
    }

    /** Maps DB constraint violations (duplicate key, foreign key, etc.) to Persian. */
    public static String dataIntegrityViolation(DataIntegrityViolationException ex) {
        String detail = deepestMessage(ex);
        if (detail == null) {
            return FaMessages.referentialIntegrityError();
        }
        String lower = detail.toLowerCase();
        if (lower.contains("duplicate") || lower.contains("unique constraint") || lower.contains("already exists")) {
            return "مقدار تکراری — این شناسه یا کد قبلاً ثبت شده است.";
        }
        if (lower.contains("foreign key") || (lower.contains("violates") && lower.contains("constraint"))) {
            return constraintSpecificMessage(detail);
        }
        return FaMessages.referentialIntegrityError();
    }

    private static String constraintSpecificMessage(String detail) {
        if (detail.contains("fk_locations_parent")) {
            return "این مکان دارای زیرمکان است. ابتدا زیرمکان‌ها را حذف کنید.";
        }
        if (detail.contains("fk_plant_systems_parent")) {
            return "این سیستم دارای زیرسیستم است. ابتدا زیرسیستم‌ها را حذف کنید.";
        }
        if (detail.contains("fk_plant_systems_location")) {
            return "این مکان دارای سیستم وابسته است. ابتدا سیستم‌ها را حذف کنید.";
        }
        if (detail.contains("fk_main_functions_parent")) {
            return "این تابع اصلی دارای زیرتابع اصلی است. ابتدا زیرتابع‌ها را حذف کنید.";
        }
        if (detail.contains("fk_main_functions_location") || detail.contains("fk_sub_functions_location")) {
            return "این مکان در توابع اصلی/فرعی استفاده شده و قابل حذف نیست.";
        }
        if (detail.contains("fk_main_functions_system") || detail.contains("fk_sub_functions_system")) {
            return "این سیستم در توابع اصلی/فرعی استفاده شده و قابل حذف نیست.";
        }
        if (detail.contains("fk_sub_functions_main_function")) {
            return "این تابع اصلی دارای توابع فرعی است. ابتدا توابع فرعی را حذف کنید.";
        }
        if (detail.contains("fk_sub_functions_parent")) {
            return "این تابع فرعی دارای زیرتابع فرعی است. ابتدا زیرتابع‌ها را حذف کنید.";
        }
        if (detail.contains("fk_asset_entries_sub_function")) {
            return "این تابع فرعی دارای دارایی است. ابتدا دارایی‌ها را حذف کنید.";
        }
        if (detail.contains("fk_field_definitions_class")
                || detail.contains("fk_asset_entries_class")
                || detail.contains("fk_log_sheet_templates_class")) {
            return "این کلاس دارایی در فیلدها، دارایی‌ها یا قالب‌ها استفاده شده و قابل حذف نیست.";
        }
        if (detail.contains("fk_log_sheet_templates_unit")
                || detail.contains("fk_log_sheets_unit")
                || detail.contains("fk_locations_unit")) {
            return "این واحد عملیاتی هنوز مکان، قالب یا لاگ‌شیت دارد و قابل حذف نیست.";
        }
        if (detail.contains("fk_operational_units_parent")) {
            return "این واحد دارای زیرواحد است. ابتدا زیرواحدها را حذف کنید.";
        }
        if (detail.contains("fk_log_sheets_template")) {
            return "این قالب لاگ‌شیت در لاگ‌شیت‌های تولیدشده استفاده شده و قابل حذف نیست.";
        }
        if (detail.contains("fk_log_sheet_entries_asset") || detail.contains("fk_data_records_asset_entry")) {
            return "این دارایی در لاگ‌شیت یا رکورد استفاده شده و قابل حذف نیست.";
        }
        return FaMessages.referentialIntegrityError();
    }

    private static String deepestMessage(Throwable ex) {
        Throwable cur = ex;
        String last = null;
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                last = cur.getMessage();
            }
            cur = cur.getCause();
        }
        return last;
    }
}
