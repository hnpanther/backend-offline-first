package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.ImportError;
import com.hnp.backendofflinefirst.dto.ImportResult;

import java.util.List;
import java.util.stream.Collectors;

/** Persian display for {@link ImportResult} (English messages in service layer). */
public final class ImportDisplay {

    private ImportDisplay() {}

    public static String summary(ImportResult result) {
        if (result == null) {
            return "";
        }
        if (result.hasErrors()) {
            return "ورود " + result.getSuccessCount() + " ردیف موفق، "
                    + result.getErrorCount() + " خطا.";
        }
        return "ورود " + result.getSuccessCount() + " ردیف با موفقیت انجام شد.";
    }

    public static List<String> errors(ImportResult result) {
        if (result == null) {
            return List.of();
        }
        return result.getErrors().stream()
                .map(ImportDisplay::formatError)
                .collect(Collectors.toList());
    }

    public static String formatError(ImportError error) {
        return "ردیف " + error.row() + ": " + importErrorToFa(error.message());
    }

    static String importErrorToFa(String english) {
        if (english == null || english.isBlank()) {
            return FaMessages.genericError();
        }
        if (english.startsWith("Plant system not found:")) {
            return "سیستم واحد یافت نشد: " + english.substring("Plant system not found:".length()).trim();
        }
        if (english.startsWith("Operational unit not found:")) {
            return "واحد عملیاتی یافت نشد: " + english.substring("Operational unit not found:".length()).trim();
        }
        if (english.startsWith("Duplicate asset code:")) {
            return "کد دارایی تکراری است: " + english.substring("Duplicate asset code:".length()).trim();
        }
        if (english.startsWith("Duplicate NFC tag:")) {
            return "شناسه NFC تکراری است: " + english.substring("Duplicate NFC tag:".length()).trim();
        }
        if (english.startsWith("Duplicate unit code:")) {
            return "کد واحد تکراری است: " + english.substring("Duplicate unit code:".length()).trim();
        }
        if (english.startsWith("Parent unit not found before this row")) {
            return "والد قبل از این ردیف یافت نشد (ترتیب ردیف‌ها رعایت نشده): "
                    + english.substring(english.lastIndexOf(':') + 1).trim();
        }
        if (english.startsWith("User not found:")) {
            return "کاربر یافت نشد: " + english.substring("User not found:".length()).trim();
        }
        if (english.startsWith("Invalid role type")) {
            return "نوع نقش نامعتبر است (SUPERVISOR یا OPERATOR): "
                    + english.substring(english.indexOf(':') + 1).trim();
        }
        if (english.startsWith("Role code not found:")) {
            return "کد نقش یافت نشد: " + english.substring("Role code not found:".length()).trim();
        }
        if (english.startsWith("Parent location not found:")) {
            return "مکان والد یافت نشد: " + english.substring("Parent location not found:".length()).trim();
        }
        if (english.startsWith("Parent unit not found:")) {
            return "واحد والد یافت نشد: " + english.substring("Parent unit not found:".length()).trim();
        }
        if (english.startsWith("Parent system not found:")) {
            return "سیستم والد یافت نشد: " + english.substring("Parent system not found:".length()).trim();
        }
        if (english.startsWith("Parent main function not found:")) {
            return "تابع اصلی والد یافت نشد: " + english.substring("Parent main function not found:".length()).trim();
        }
        if (english.startsWith("Location not found:")) {
            return "مکان یافت نشد: " + english.substring("Location not found:".length()).trim();
        }
        if (english.startsWith("Unit not found:")) {
            return "واحد یافت نشد: " + english.substring("Unit not found:".length()).trim();
        }
        if (english.startsWith("System not found:")) {
            return "سیستم یافت نشد: " + english.substring("System not found:".length()).trim();
        }
        if (english.startsWith("Main function not found:")) {
            return "تابع اصلی یافت نشد: " + english.substring("Main function not found:".length()).trim();
        }
        if (english.startsWith("Sub function not found:")) {
            return "زیرتابع یافت نشد: " + english.substring("Sub function not found:".length()).trim();
        }
        if (english.startsWith("Asset class not found:")) {
            return "کلاس دارایی یافت نشد: " + english.substring("Asset class not found:".length()).trim();
        }
        if (english.startsWith("Duplicate code:")) {
            return "کد تکراری: " + english.substring("Duplicate code:".length()).trim();
        }
        if (english.startsWith("Duplicate username:")) {
            return "نام کاربری تکراری: " + english.substring("Duplicate username:".length()).trim();
        }
        if (english.startsWith("Invalid active value:")) {
            return "مقدار active نامعتبر: " + english.substring("Invalid active value:".length()).trim();
        }
        if (english.startsWith("Invalid staff role:")) {
            return "نقش پرسنلی نامعتبر: " + english.substring("Invalid staff role:".length()).trim();
        }
        if (english.startsWith("Invalid field type:")) {
            return "نوع فیلد نامعتبر: " + english.substring("Invalid field type:".length()).trim();
        }
        if (english.startsWith("Row is empty.")) {
            return "ردیف خالی است.";
        }
        return switch (english) {
            case "Asset name is required." -> "نام دارایی اجباری است.";
            case "Username and password are required." -> "نام کاربری و رمز عبور اجباری هستند.";
            case "Unit code, role type and username are required." -> "کد واحد، نوع نقش و نام کاربری اجباری هستند.";
            case "Code and name are required." -> "کد و نام اجباری هستند.";
            case "Code is required." -> "کد اجباری است.";
            case "Name is required." -> "نام اجباری است.";
            case "Username is required." -> "نام کاربری اجباری است.";
            case "Password is required for new users." -> "رمز عبور برای کاربر جدید اجباری است.";
            case "Asset class code is required." -> "کد کلاس دارایی اجباری است.";
            case "Sub function code is required." -> "کد زیرتابع اجباری است.";
            case "Location code is required." -> "کد مکان اجباری است.";
            case "Unit code is required." -> "کد واحد اجباری است.";
            case "System code is required." -> "کد سیستم اجباری است.";
            case "Main function code is required." -> "کد تابع اصلی اجباری است.";
            case "Could not read Excel file." -> "خواندن فایل اکسل ممکن نشد.";
            case "Excel file has no data rows." -> "فایل اکسل فاقد ردیف داده است.";
            case "Excel sheet is empty." -> "برگه اکسل خالی است.";
            default -> english;
        };
    }
}
