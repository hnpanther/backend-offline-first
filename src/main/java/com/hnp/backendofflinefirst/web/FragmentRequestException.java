package com.hnp.backendofflinefirst.web;

/**
 * A refusal on an endpoint whose caller is <b>JavaScript expecting an HTML fragment</b>, not a
 * browser following a redirect.
 *
 * <h2>Why it exists</h2>
 *
 * <p>{@code WebExceptionHandler} answers every other failure with a flash message and a
 * redirect, which is exactly right for a form submission: the page reloads and shows the
 * sentence. The fill page's small saves are not form submissions — they are {@code fetch} calls
 * that replace one section of the page with the server's re-render, and they use
 * {@code redirect: 'manual'} because the web chain answers an expired session with a redirect
 * too. A redirect therefore reaches the operator as «درخواست پذیرفته نشد. ممکن است نشست شما
 * منقضی شده باشد», whatever actually went wrong.
 *
 * <p>That message was harmless while nothing on those endpoints could be refused for a reason
 * the operator could fix. A <b>required</b> round-level question can: the fix is one field away,
 * and telling them to refresh the page sends them in the opposite direction. So this carries the
 * real sentence and is answered with a status plus a body the caller can show in place.
 *
 * <p><b>Already in Persian.</b> The thrower translates, because the handler cannot tell a
 * message meant for an operator from one meant for a log.
 */
public class FragmentRequestException extends RuntimeException {

    public FragmentRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
