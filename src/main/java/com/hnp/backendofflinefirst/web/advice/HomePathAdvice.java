package com.hnp.backendofflinefirst.web.advice;

import com.hnp.backendofflinefirst.security.SecurityUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts {@code homePath} on every page's model, so "home" links somewhere the reader may go.
 *
 * <p><b>The bug this exists to remove.</b> The navbar brand — the logo and «مدیریت ثبت داده های
 * میدانی», present on every page — was a fixed {@code href="/"}. So was the «خانه» step of every
 * breadcrumb. But {@code GET:/} belongs to {@code ADMIN} and {@code HIGH_USER} only: a
 * supervisor or an operator clicking the logo asked for a page they cannot open and got an
 * access-denied message. The sidebar had this right all along —
 * {@code sec:authorize="hasAuthority('GET:/')"} hides its dashboard entry — which is why the
 * problem was invisible in review: the one navigation element that was *not* permission-aware
 * was the one nobody thinks of as navigation.
 *
 * <p><b>Why a model attribute and not {@code sec:authorize} in the template.</b> Hiding the
 * brand, or making it plain text, answers "don't break" but not "take me back" — every user has
 * somewhere that is home to them, and the logo is the control they expect to reach it with.
 * Computing the destination once here also means the 35 breadcrumbs and the brand cannot drift
 * apart, and a new page inherits the behaviour by using {@code ${homePath}} rather than by
 * remembering a rule.
 *
 * <p>Scoped to the {@code web} package for the same reason as {@link ListFilterAdvice}: the JSON
 * controllers render no templates, and this would be pure overhead on every API call. Note that
 * {@code @ControllerAdvice}'s selectors are OR-ed, not AND-ed — see that class for the trap in
 * trying to narrow it further.
 */
@ControllerAdvice(basePackages = "com.hnp.backendofflinefirst.web")
public class HomePathAdvice {

    @ModelAttribute("homePath")
    public String homePath() {
        return SecurityUtils.homePath();
    }
}
