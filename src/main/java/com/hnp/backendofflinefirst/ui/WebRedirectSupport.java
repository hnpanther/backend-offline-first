package com.hnp.backendofflinefirst.ui;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

/** Resolves a safe redirect target after a failed web form POST. */
public final class WebRedirectSupport {

    private WebRedirectSupport() {}

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
            return uri.replaceAll("/(claim|release|assign|reassign|takeover|extend|admin-reopen|save-draft|complete)$", "");
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
