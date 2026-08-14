/**
 * CSRF helper for fetch() calls from the admin panel.
 *
 * The web filter chain has CSRF enabled — WebSecurityConfig disables it only for the /api/**
 * chain — so every state-changing fetch() must carry the token that fragments/layout.html
 * publishes as <meta name="_csrf"> / <meta name="_csrf_header">. A plain
 * `fetch(url, {method: 'POST'})` fails in a way that is almost impossible to notice:
 * MissingCsrfTokenException is an AccessDeniedException, WebAccessDeniedHandler answers it
 * with a redirect rather than a 403, fetch follows the redirect by default and hands back an
 * HTML page with status 200, and the caller's res.json() then throws a SyntaxError inside an
 * async function nobody awaited. The button simply does nothing, silently.
 *
 * postJson() closes every one of those holes: it sends the token, refuses to follow the
 * redirect, and turns any non-JSON answer into a thrown Error with a Persian message.
 */
(function (window, document) {
    'use strict';

    function token() {
        var tokenMeta = document.querySelector('meta[name="_csrf"]');
        var headerMeta = document.querySelector('meta[name="_csrf_header"]');
        if (!tokenMeta || !headerMeta) return null;
        var header = headerMeta.getAttribute('content');
        var value = tokenMeta.getAttribute('content');
        if (!header || !value) return null;
        return { header: header, token: value };
    }

    /** Returns `base` with the CSRF header added when one is available. */
    function headers(base) {
        var result = base ? Object.assign({}, base) : {};
        var c = token();
        if (c) result[c.header] = c.token;
        return result;
    }

    /**
     * POSTs and resolves with the parsed JSON body.
     *
     * Throws (never resolves) when the session/token is stale or the server answers with
     * anything but JSON, so callers can show the user something instead of failing silently.
     */
    async function postJson(url, options) {
        var opts = options || {};
        var res = await fetch(url, {
            method: 'POST',
            headers: headers(opts.headers),
            body: opts.body,
            credentials: 'same-origin',
            // Do not chase the access-denied redirect: following it turns a rejected request
            // into an HTML 200 that only fails later, at JSON.parse, with a useless message.
            redirect: 'manual'
        });

        // 'manual' surfaces a cross-origin-style opaque redirect as type 'opaqueredirect'
        // with status 0. Either way, a redirect answering a POST means the session expired
        // or the token was rejected — a reload sends the user to the login page.
        if (res.type === 'opaqueredirect' || res.status === 0 || (res.status >= 300 && res.status < 400)) {
            throw new Error('نشست شما منقضی شده است. صفحه را تازه‌سازی کرده و دوباره تلاش کنید.');
        }

        var text = await res.text();
        var body = null;
        try {
            body = text ? JSON.parse(text) : null;
        } catch (e) {
            throw new Error(res.ok
                ? 'پاسخ نامعتبر از سرور دریافت شد.'
                : 'خطای سرور (' + res.status + ').');
        }
        if (!res.ok) {
            throw new Error((body && body.message) ? body.message : 'خطای سرور (' + res.status + ').');
        }
        return body;
    }

    window.AppCsrf = { token: token, headers: headers, postJson: postJson };
})(window, document);
