package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import org.springframework.stereotype.Component;

/** Thymeleaf helper for rendering log-sheet enums as Persian labels/badges. */
@Component("logSheetView")
public class LogSheetViewHelper {

    public String statusLabel(LogSheetStatus status) {
        if (status == null) return "—";
        return switch (status) {
            case PENDING -> "در انتظار پیک‌آپ";
            case ASSIGNED -> "انتساب‌شده";
            case IN_PROGRESS -> "در حال انجام";
            case SUBMITTED -> "تکمیل‌شده";
            case VOIDED -> "ابطال‌شده";
            case EXPIRED -> "منقضی";
            case CANCELLED -> "لغو‌شده";
        };
    }

    public String statusBadge(LogSheetStatus status) {
        if (status == null) return "bg-secondary";
        return switch (status) {
            case PENDING -> "bg-warning text-dark";
            case ASSIGNED -> "bg-info text-dark";
            case IN_PROGRESS -> "bg-primary";
            case SUBMITTED -> "bg-success";
            case VOIDED -> "bg-dark";
            case EXPIRED -> "bg-danger";
            case CANCELLED -> "bg-secondary";
        };
    }

    public String assignmentLabel(AssignmentType type) {
        if (type == null) return "—";
        return switch (type) {
            case SELF_CLAIMED -> "پیک‌آپ توسط اپراتور";
            case SUPERVISOR_ASSIGNED -> "انتساب توسط سرپرست";
        };
    }

    public String actionLabel(LogSheetActionType action) {
        if (action == null) return "—";
        return switch (action) {
            case GENERATE -> "تولید";
            case CLAIM -> "پیک‌آپ";
            case RELEASE -> "برگرداندن";
            case ASSIGN -> "انتساب";
            case REASSIGN -> "بازانتساب";
            case TAKEOVER -> "تصاحب توسط سرپرست";
            case EXTEND -> "تمدید مهلت";
            case ADMIN_REOPEN -> "باز کردن مجدد";
            case VOID -> "ابطال";
            case UNVOID -> "لغو ابطال";
            case CANCEL -> "لغو کار";
            case START -> "شروع";
            case COMPLETE -> "تکمیل";
            case SUBMIT -> "ارسال/سینک";
            case EXPIRE -> "انقضا";
            case SUPERSEDE -> "ابطال (تکمیل توسط دیگری)";
        };
    }

    public String entrySourceLabel(LogSheetEntrySource source) {
        if (source == null) return "—";
        return switch (source) {
            case WEB -> "وب";
            case PWA_NFC -> "موبایل (اسکن NFC)";
            case PWA_MANUAL -> "موبایل (دستی)";
        };
    }

    /** Why an expired sheet was left incomplete, for reporting. */
    public String incompleteReason(LogSheet sheet) {
        if (sheet == null || sheet.getStatus() != LogSheetStatus.EXPIRED) return "";
        return sheet.getAssigneeUserId() == null
                ? "کسی برنداشت"
                : "برداشته شد ولی تکمیل نشد";
    }
}
