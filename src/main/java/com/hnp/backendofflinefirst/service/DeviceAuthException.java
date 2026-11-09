package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.DeviceAuthError;
import com.hnp.backendofflinefirst.logging.ExpectedRefusal;

/**
 * A refusal from enrolment or a device-proofed login, carrying its {@link DeviceAuthError}.
 *
 * <p>Thrown inside transactions declared {@code noRollbackFor} it, deliberately: a wrong code
 * must still count as a failed attempt, and a consumed login nonce must stay consumed, even
 * though the call that consumed it is refused.
 *
 * <p>An {@link ExpectedRefusal}: with enrolment on, every login from an unenrolled tablet ends
 * in one of these, so it is logged as one INFO line rather than an ERROR with a trace. Its
 * message is the error's name and nothing the caller sent.
 */
public class DeviceAuthException extends RuntimeException implements ExpectedRefusal {

    private final DeviceAuthError error;
    private final Integer attemptsLeft;

    public DeviceAuthException(DeviceAuthError error) {
        this(error, null);
    }

    public DeviceAuthException(DeviceAuthError error, Integer attemptsLeft) {
        super(error.name());
        this.error = error;
        this.attemptsLeft = attemptsLeft;
    }

    public DeviceAuthError getError() {
        return error;
    }

    /** Only for {@link DeviceAuthError#CODE_INVALID}: how many tries remain before the code dies. */
    public Integer getAttemptsLeft() {
        return attemptsLeft;
    }
}
