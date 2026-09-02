package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.ActionSource;
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
            case APPROVED -> "تأییدشده";
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
            // A darker green than SUBMITTED, not a different hue: approval is the same work one
            // step further along, and a colour from another family would read as another kind of
            // outcome.
            case APPROVED -> "bg-success-dark";
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
            case APPROVE -> "تأیید";
            case UNAPPROVE -> "لغو تأیید";
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

    /**
     * Whether this round was completed — {@code SUBMITTED} or {@code APPROVED}.
     *
     * <p>Exists so a template can ask the question without naming either status. Every
     * {@code status.name() == 'SUBMITTED'} in a Thymeleaf expression was a place that silently
     * stopped being true the day approval was added: the action buttons, the operations column
     * and the entry table's column count all vanished on an approved sheet.
     */
    public boolean isCompleted(LogSheetStatus status) {
        return status != null && status.isCompleted();
    }

    /**
     * A completed round that nobody has reviewed yet.
     *
     * <p>The precondition for approving, voiding and reopening — all three refuse an approved
     * round, so a supervisor has to withdraw the approval first and that step shows in the action
     * log. Named for the business question rather than the status so the template does not have
     * to know that "awaiting approval" happens to be spelled {@code SUBMITTED}, and so the
     * completed-status guard has nothing to catch here.
     */
    public boolean isAwaitingApproval(LogSheetStatus status) {
        return status == LogSheetStatus.SUBMITTED;
    }

    /** A round a supervisor has accepted. The precondition for withdrawing that approval. */
    public boolean isApproved(LogSheetStatus status) {
        return status == LogSheetStatus.APPROVED;
    }

    /**
     * Which surface performed an action, for the value-history panel.
     *
     * <p>Deliberately separate from {@link #entrySourceLabel}: {@code entry_source} says how a
     * reading was <em>captured</em> (a scan, a manual entry, a desk), while this says where a
     * request came <em>from</em>. They overlap on «وب» and diverge everywhere else, and one
     * label serving both would eventually claim a reading was NFC-scanned by the scheduler.
     */
    public String actionSourceLabel(ActionSource source) {
        if (source == null) return "—";
        return switch (source) {
            case WEB -> "وب";
            case MOBILE -> "موبایل";
            case SERVER -> "سامانه";
        };
    }

    /**
     * Why a submission was voided, in Persian.
     *
     * <h2>Translated on the way out, never on the way in</h2>
     *
     * <p>{@code log_sheet_void_submissions.reason} is written in English by
     * {@code LogSheetService.voidSubmission} and the same sentence is handed to the tablet as the
     * submission's error — it is part of the mobile contract and part of a stored record, so it
     * is not the string to localise. This maps it for display only; the row keeps what it always
     * said.
     *
     * <p>An unrecognised reason is <b>returned as it stands</b> rather than replaced with
     * "unknown". A voided submission is the record of somebody's lost work, and a reason nobody
     * has translated yet is still the truth about why — swallowing it would leave the reader with
     * less than they had before.
     */
    public String voidReasonLabel(String reason) {
        if (reason == null || reason.isBlank()) return "—";
        return switch (reason.trim()) {
            case "This log sheet was already completed by someone else." ->
                    "این لاگ‌شیت را پیش‌تر شخص دیگری تکمیل کرده بود.";
            case "This log sheet is no longer assigned to you." ->
                    "این لاگ‌شیت دیگر به این اپراتور تخصیص نداشت.";
            case "This log sheet was cancelled." ->
                    "این لاگ‌شیت لغو شده بود.";
            case "This log sheet completion deadline has passed." ->
                    "مهلت تکمیل این لاگ‌شیت گذشته بود.";
            default -> reason;
        };
    }

    /**
     * Whether {@link #voidReasonLabel} actually translated the reason.
     *
     * <p>The page shows the original English underneath a translated one, so a supervisor and
     * whoever reads the server log are looking at the same sentence. Repeating it under an
     * untranslated reason would just print the same line twice.
     */
    public boolean voidReasonWasTranslated(String reason) {
        return reason != null && !reason.isBlank() && !voidReasonLabel(reason).equals(reason.trim());
    }

    /** Why an expired sheet was left incomplete, for reporting. */
    public String incompleteReason(LogSheet sheet) {
        if (sheet == null || sheet.getStatus() != LogSheetStatus.EXPIRED) return "";
        return sheet.getAssigneeUserId() == null
                ? "کسی برنداشت"
                : "برداشته شد ولی تکمیل نشد";
    }
}
