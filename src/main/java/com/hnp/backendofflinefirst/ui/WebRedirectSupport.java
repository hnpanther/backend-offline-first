package com.hnp.backendofflinefirst.ui;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

/**
 * Resolves safe redirect targets for the web panel: back to where a failed form POST came from
 * ({@link #backUrl}), and to a caller-supplied {@code returnTo} ({@link #safeReturn}).
 */
public final class WebRedirectSupport {

    private WebRedirectSupport() {}

    /**
     * {@code returnTo} when it is a path inside this application, otherwise {@code fallback}
     * (issue B-11).
     *
     * <p>Several panel actions take {@code returnTo} so the user lands back where they started.
     * Taken verbatim it is an open redirect: a crafted link could finish a supervisor's
     * legitimate action and then send them to another site. Every handler that honours it goes
     * through here — one rule, so it cannot be right in one controller and missing from the one
     * beside it, which is how B-11 was found.
     *
     * <p>Inside means: starts with a single {@code /}, and contains neither {@code //} at the
     * start (a protocol-relative URL), nor a backslash (which some browsers read as a slash), nor
     * {@code ://} anywhere.
     */
    public static String safeReturn(String returnTo, String fallback) {
        if (returnTo != null
                && returnTo.startsWith("/")
                && !returnTo.startsWith("//")
                && !returnTo.contains("\\")
                && !returnTo.contains("://")) {
            return returnTo;
        }
        return fallback;
    }

    /** The redirect view name for {@link #safeReturn}. */
    public static String redirectTo(String returnTo, String fallback) {
        return "redirect:" + safeReturn(returnTo, fallback);
    }

    /**
     * Prefer same-origin Referer; otherwise strip the action suffix from the request URI
     * (e.g. {@code /log-sheets/5/claim} → {@code /log-sheets/5}).
     */
    public static String backUrl(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URI uri = URI.create(referer);
                if (isSameHost(request, uri)) {
                    String path = uri.getRawPath();
                    if (path != null && !path.isBlank()) {
                        return path + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        String uri = request.getRequestURI();
        if (uri != null && uri.matches(".*/log-sheets/\\d+/[^/]+$")) {
            return uri.replaceAll("/(claim|release|assign|reassign|takeover|extend|admin-reopen|reopen|void|unvoid|cancel|save-draft|complete)$", "");
        }
        if (uri != null && uri.matches(".*/log-sheets/\\d+/fill$")) {
            return uri.replace("/fill", "");
        }
        return "/";
    }

    private static boolean isSameHost(HttpServletRequest request, URI refererUri) {
        String host = request.getServerName();
        int port = request.getServerPort();
        String refererHost = refererUri.getHost();
        int refererPort = refererUri.getPort();
        if (refererHost == null || !refererHost.equalsIgnoreCase(host)) {
            return false;
        }
        if (refererPort == -1) {
            return port == 80 || port == 443;
        }
        return refererPort == port;
    }
}
