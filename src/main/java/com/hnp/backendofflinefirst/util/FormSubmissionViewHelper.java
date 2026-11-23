package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.domain.FormSubmissionActionType;
import com.hnp.backendofflinefirst.domain.FormSubmissionStatus;
import org.springframework.stereotype.Component;

/** Persian labels for ad-hoc form submission status and review actions. */
@Component("formSubmissionView")
public class FormSubmissionViewHelper {

    public String statusLabel(FormSubmissionStatus status) {
        if (status == null) {
            return "—";
        }
        return switch (status) {
            case DRAFT -> "پیش‌نویس";
            case SUBMITTED -> "ثبت نهایی";
            case APPROVED -> "تأییدشده";
            case VOIDED -> "ابطال‌شده";
        };
    }

    public String statusBadgeClass(FormSubmissionStatus status) {
        if (status == null) {
            return "bg-secondary";
        }
        return switch (status) {
            case DRAFT -> "bg-warning text-dark";
            case SUBMITTED -> "bg-primary";
            case APPROVED -> "bg-success";
            case VOIDED -> "bg-dark";
        };
    }

    public String actionLabel(FormSubmissionActionType action) {
        if (action == null) {
            return "—";
        }
        return switch (action) {
            case APPROVE -> "تأیید";
            case UNAPPROVE -> "لغو تأیید";
            case VOID -> "ابطال";
            case UNVOID -> "لغو ابطال";
            case REOPEN -> "باز کردن مجدد";
            case SUBMIT -> "ثبت نهایی";
        };
    }

    public boolean canReopen(FormSubmissionStatus status) {
        return status == FormSubmissionStatus.SUBMITTED;
    }

    public boolean canApprove(FormSubmissionStatus status) {
        return status == FormSubmissionStatus.SUBMITTED;
    }

    public boolean canVoid(FormSubmissionStatus status) {
        return status == FormSubmissionStatus.SUBMITTED;
    }

    public boolean canUnapprove(FormSubmissionStatus status) {
        return status == FormSubmissionStatus.APPROVED;
    }

    public boolean canUnvoid(FormSubmissionStatus status) {
        return status == FormSubmissionStatus.VOIDED;
    }

    public boolean isDraft(FormSubmissionStatus status) {
        return status == FormSubmissionStatus.DRAFT;
    }
}
