package com.hnp.backendofflinefirst.domain;

import org.springframework.http.HttpStatus;

/**
 * Every way enrolment or a device-proofed login can be refused.
 *
 * <p>Each carries three things, and all three are load-bearing. The HTTP status, for anything
 * that only looks at that. The {@link #name()}, as a stable machine code the tablet branches on
 * — a Persian sentence is not something to {@code switch} over. And the sentence itself, in the
 * response's {@code message} field, which is the one field an <b>older</b> PWA build reads: a
 * tablet that has never heard of device enrolment still shows the operator the right words when
 * an administrator turns it on.
 */
public enum DeviceAuthError {

    /** The switch in Settings is off; the enrolment endpoints refuse rather than half-work. */
    ENROLLMENT_DISABLED(HttpStatus.CONFLICT,
            "ثبت دستگاه در این سرور فعال نیست."),

    /** Enrolment is on and this login carried no device proof. */
    ENROLLMENT_REQUIRED(HttpStatus.FORBIDDEN,
            "این دستگاه برای ورود ثبت نشده است. ابتدا دستگاه را ثبت کنید."),

    INVALID_KEY(HttpStatus.BAD_REQUEST,
            "کلید دستگاه معتبر نیست."),

    TOO_MANY_PENDING(HttpStatus.TOO_MANY_REQUESTS,
            "تعداد درخواست‌های ثبت در انتظار زیاد است. کمی بعد دوباره تلاش کنید یا با مدیر سامانه تماس بگیرید."),

    REQUEST_CONFLICT(HttpStatus.CONFLICT,
            "درخواست همزمان دیگری برای این دستگاه در جریان بود. دوباره تلاش کنید."),

    REQUEST_UNKNOWN(HttpStatus.NOT_FOUND,
            "درخواست ثبت این دستگاه در سرور پیدا نشد. دوباره درخواست ثبت بدهید."),

    REQUEST_EXPIRED(HttpStatus.GONE,
            "مهلت این درخواست ثبت تمام شده است. دوباره درخواست ثبت بدهید."),

    CODE_NOT_ISSUED(HttpStatus.CONFLICT,
            "هنوز کدی برای این درخواست صادر نشده است. شمارهٔ درخواست را به مدیر سامانه بگویید."),

    CODE_EXPIRED(HttpStatus.CONFLICT,
            "مهلت این کد تمام شده است. کد تازه بگیرید."),

    CODE_INVALID(HttpStatus.BAD_REQUEST,
            "کد ثبت درست نیست."),

    CODE_LOCKED(HttpStatus.CONFLICT,
            "این کد به دلیل چند بار ورود اشتباه باطل شد. کد تازه بگیرید."),

    SIGNATURE_INVALID(HttpStatus.FORBIDDEN,
            "امضای دستگاه معتبر نیست."),

    CHALLENGE_INVALID(HttpStatus.FORBIDDEN,
            "اعتبار این درخواست ورود تمام شد. دوباره وارد شوید."),

    DEVICE_UNKNOWN(HttpStatus.FORBIDDEN,
            "این دستگاه در سرور شناخته نمی‌شود. دستگاه را دوباره ثبت کنید."),

    DEVICE_NOT_APPROVED(HttpStatus.FORBIDDEN,
            "این دستگاه هنوز تأیید نشده است."),

    DEVICE_REVOKED(HttpStatus.FORBIDDEN,
            "مجوز این دستگاه لغو شده است. برای استفادهٔ دوباره، دستگاه باید از نو ثبت شود.");

    private final HttpStatus status;
    private final String messageFa;

    DeviceAuthError(HttpStatus status, String messageFa) {
        this.status = status;
        this.messageFa = messageFa;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageFa() {
        return messageFa;
    }
}
