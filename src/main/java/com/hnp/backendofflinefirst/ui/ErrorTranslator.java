package com.hnp.backendofflinefirst.ui;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Maps English exception / validation messages from the service layer to Persian for end users.
 */
public final class ErrorTranslator {

    private static String translateFormDataValidationDetail(String english) {
        return english
                .replaceAll("Form data validation failed \\(assetId=(\\d+)\\):", "دارایی با شناسه $1:")
                .replace("Form data validation failed (asset '", "دارایی «")
                .replace("' / ", "» (کد ")
                .replace("'): field '", "»: فیلد «")
                .replace("): field '", "): فیلد «")
                .replace("': required field is missing", "» اجباری است")
                .replace("': is outside the danger range", "» خارج از بازه خطر است")
                .replace("': must be a number", "» باید عدد باشد")
                .replace("': has an invalid option", "» گزینه نامعتبر دارد")
                .replace("': must be a boolean value", "» باید مقدار بولین باشد");
    }

    private ErrorTranslator() {}

    public static String toFa(String english) {
        if (english == null || english.isBlank()) {
            return FaMessages.genericError();
        }
        if (english.startsWith("Duplicate asset code:")) {
            return "کد دارایی تکراری است:" + english.substring("Duplicate asset code:".length());
        }
        if (english.startsWith("Duplicate NFC tag in file:")) {
            return "تگ NFC تکراری در همین فایل:" + english.substring("Duplicate NFC tag in file:".length());
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
        if (english.startsWith("Duplicate code in file:")) {
            return "کد تکراری در همین فایل:" + english.substring("Duplicate code in file:".length());
        }
        if (english.startsWith("Duplicate tag in file:")) {
            return "تگ تکراری در همین فایل:" + english.substring("Duplicate tag in file:".length());
        }
        if (english.contains(" tag:")) {
            int idx = english.indexOf(" tag:");
            return "تگ تکراری (" + english.substring(9, idx).trim() + "):" + english.substring(idx + " tag:".length());
        }
        if (english.startsWith("Duplicate field key:")) {
            return "کلید فیلد تکراری است:" + english.substring("Duplicate field key:".length());
        }
        if (english.contains(" name:")) {
            int idx = english.indexOf(" name:");
            return "نام تکراری (" + english.substring(9, idx).trim() + "):" + english.substring(idx + " name:".length());
        }
        if (english.contains(" code:")) {
            int idx = english.indexOf(" code:");
            return "کد تکراری (" + english.substring(9, idx).trim() + "):" + english.substring(idx + " code:".length());
        }
        if (english.startsWith("Password cannot be changed for Active Directory users.")) {
            return "برای کاربران اکتیو دایرکتوری امکان تغییر رمز محلی وجود ندارد.";
        }
        if (english.startsWith("Password is required for LOCAL and HYBRID users.")) {
            return "برای کاربران محلی و ترکیبی، رمز عبور الزامی است.";
        }
        if ("Only .xlsx files are supported.".equals(english)) {
            return "فقط فایل‌های اکسل با پسوند xlsx پشتیبانی می‌شوند.";
        }
        if (english.startsWith("No permission to import ")) {
            return "مجوز ورود این نوع داده را ندارید.";
        }
        if (english.startsWith("Excel file has ") && english.contains(" data rows; maximum allowed is ")) {
            int rowsIdx = "Excel file has ".length();
            int mid = english.indexOf(" data rows; maximum allowed is ");
            int end = english.lastIndexOf('.');
            String rows = english.substring(rowsIdx, mid);
            String max = english.substring(mid + " data rows; maximum allowed is ".length(),
                    end > mid ? end : english.length());
            return "فایل اکسل " + rows + " ردیف داده دارد؛ حداکثر مجاز " + max + " ردیف است. فایل را کوچک‌تر کنید و به‌ترتیب وارد کنید.";
        }
        if ("Invalid entity type.".equals(english)) {
            return "نوع داده انتخاب‌شده معتبر نیست.";
        }
        if (english.startsWith("Missing assets on server (ids:")) {
            String suffix = english.substring("Missing assets on server (ids:".length());
            return "یک یا چند دارایی این لاگ‌شیت روی سرور وجود ندارد (شناسه‌ها:" + suffix
                    .replace("). Sync the app online to refresh asset lists.",
                            "). اپ را آنلاین کنید تا لیست دارایی‌ها به‌روز شود.");
        }
        if (english.contains("Form data validation failed (")) {
            return "داده‌های فرم معتبر نیست — " + translateFormDataValidationDetail(english);
        }
        if (english.startsWith("Asset(s) not part of this log sheet (ids:")) {
            String suffix = english.substring("Asset(s) not part of this log sheet (ids:".length());
            return "یک یا چند دارایی ارسالی جزو این لاگ‌شیت نیست (شناسه‌ها:" + suffix
                    .replace(").", ").");
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
            case "location code is required." -> "کد مکان اجباری است.";
            case "plant system code is required." -> "کد سیستم واحد اجباری است.";
            case "plant system name is required." -> "نام سیستم واحد اجباری است.";
            case "main function code is required." -> "کد تابع اصلی اجباری است.";
            case "main function name is required." -> "نام تابع اصلی اجباری است.";
            case "sub function code is required." -> "کد تابع فرعی اجباری است.";
            case "sub function tag is required." -> "تگ تابع فرعی اجباری است.";
            case "Tag is required." -> "تگ اجباری است.";
            case "asset class name is required." -> "نام کلاس دارایی اجباری است.";
            case "field definition class is required." -> "کلاس دارایی برای تعریف فیلد اجباری است.";
            case "field key is required." -> "کلید فیلد اجباری است.";
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
            case "Log sheet template name is required." -> "نام قالب لاگ‌شیت اجباری است.";
            case "Asset class is required for log sheet template." -> "انتخاب کلاس دارایی برای قالب لاگ‌شیت الزامی است.";
            case "Asset class not found." -> "کلاس دارایی یافت نشد.";
            case "Sub function is required." -> "انتخاب تابع فرعی برای دارایی الزامی است.";
            case "Sub function not found." -> "تابع فرعی یافت نشد.";
            case "Operational unit is required for log sheet template." -> "انتخاب واحد عملیاتی برای قالب لاگ‌شیت الزامی است.";
            case "Scope type is required for log sheet template." -> "انتخاب نوع محدوده برای قالب لاگ‌شیت الزامی است.";
            case "Scope is required for log sheet template." -> "انتخاب محدوده برای قالب لاگ‌شیت الزامی است.";
            case "Scope not found." -> "محدوده انتخاب‌شده یافت نشد.";
            case "Scope does not belong to the selected operational unit." ->
                    "محدوده انتخاب‌شده متعلق به واحد عملیاتی انتخاب‌شده نیست.";
            case "Access to this log sheet is not allowed." -> "دسترسی به این لاگ شیت مجاز نیست.";
            case "Selected operational unit is not allowed." -> "واحد عملیاتی انتخاب‌شده مجاز نیست.";
            case "Operational unit not found." -> "واحد عملیاتی یافت نشد.";
            case "Operational unit code is required." -> "کد واحد عملیاتی اجباری است.";
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
            case "This user has performed actions in the app and cannot be deleted. Deactivate the user instead." ->
                    "این کاربر در اپ فعالیت داشته و قابل حذف نیست. به‌جای حذف، کاربر را غیرفعال کنید.";
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
            case "Import job not found." -> "عملیات ورود یافت نشد.";
            case "Import job is not active." -> "این عملیات در حال اجرا نیست.";
            case "Stop the import job before deleting it." -> "ابتدا عملیات را متوقف کنید، سپس حذف کنید.";
            case "Cancelled by user." -> "توسط کاربر متوقف شد.";
            case "Another import is already queued or running. Wait for it to finish, then submit the next file." ->
                    "یک ورود دیگر در صف یا در حال اجراست. پس از اتمام، فایل بعدی را ارسال کنید.";
            case "Import was not started before server restart." -> "قبل از راه‌اندازی مجدد سرور، پردازش شروع نشده بود.";
            case "Import file missing after server restart." -> "فایل ورود پس از راه‌اندازی مجدد سرور یافت نشد.";
            case "Import interrupted by server restart." -> "پردازش به‌دلیل راه‌اندازی مجدد سرور قطع شد.";
            case "This location has child locations and cannot be deleted." -> "این مکان دارای زیرمکان است. ابتدا زیرمکان‌ها را حذف کنید.";
            case "This location has plant systems and cannot be deleted." -> "این مکان دارای سیستم وابسته است. ابتدا سیستم‌ها را حذف کنید.";
            case "This location is referenced by functions and cannot be deleted." -> "این مکان در توابع اصلی/فرعی استفاده شده و قابل حذف نیست.";
            case "This plant system has child systems and cannot be deleted." -> "این سیستم دارای زیرسیستم است. ابتدا زیرسیستم‌ها را حذف کنید.";
            case "This plant system is referenced by functions and cannot be deleted." -> "این سیستم در توابع اصلی/فرعی استفاده شده و قابل حذف نیست.";
            case "This main function has child main functions and cannot be deleted." -> "این تابع اصلی دارای زیرتابع اصلی است. ابتدا زیرتابع‌ها را حذف کنید.";
            case "This main function has sub functions and cannot be deleted." -> "این تابع اصلی دارای توابع فرعی است. ابتدا توابع فرعی را حذف کنید.";
            case "This sub function has child sub functions and cannot be deleted." -> "این تابع فرعی دارای زیرتابع فرعی است. ابتدا زیرتابع‌ها را حذف کنید.";
            case "This sub function has asset entries and cannot be deleted." -> "این تابع فرعی دارای دارایی است. ابتدا دارایی‌ها را حذف کنید.";
            case "This asset entry is referenced by log sheets or records and cannot be deleted." -> "این دارایی در لاگ‌شیت یا رکورد استفاده شده و قابل حذف نیست.";
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
        if (detail.contains("ux_plant_systems_code_lower") || detail.contains("uk_plant_systems_code")) {
            return "کد سیستم واحد تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_main_functions_code_lower") || detail.contains("uk_main_functions_code")) {
            return "کد تابع اصلی تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_operational_units_code_lower")) {
            return "کد واحد عملیاتی تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_locations_code_lower") || detail.contains("uk_locations_code")) {
            return "کد مکان تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_sub_functions_code_lower") || detail.contains("uk_sub_functions_code")) {
            return "کد تابع فرعی تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_sub_functions_tag_lower")) {
            return "تگ تابع فرعی تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_asset_classes_name_lower")) {
            return "نام کلاس دارایی تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_asset_entries_asset_code_lower") || detail.contains("uk_asset_entries_asset_code")) {
            return "کد دارایی تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_asset_entries_nfc_tag_id_lower") || detail.contains("uk_asset_entries_nfc_tag_id")) {
            return "تگ NFC تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
        if (detail.contains("ux_field_definitions_class_key_lower")) {
            return "کلید فیلد در این کلاس تکراری است (بدون توجه به حروف بزرگ/کوچک).";
        }
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
        if (detail.contains("ux_log_sheet_templates_name_lower")) {
            return "نام قالب لاگ‌شیت تکراری است (بدون توجه به حروف بزرگ/کوچک).";
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
        if (detail.contains("fk_import_jobs_submitted_by_user")
                || detail.contains("fk_audit_log_actor_user")
                || detail.contains("fk_log_sheets_assignee_user")
                || detail.contains("fk_log_sheets_assigned_by_user")
                || detail.contains("fk_log_sheets_completed_by_user")
                || detail.contains("fk_lsal_actor_user")
                || detail.contains("fk_lsal_from_user")
                || detail.contains("fk_lsal_to_user")
                || detail.contains("fk_lsvs_submitted_by_user")) {
            return "این کاربر در اپ فعالیت داشته و قابل حذف نیست. به‌جای حذف، کاربر را غیرفعال کنید.";
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
