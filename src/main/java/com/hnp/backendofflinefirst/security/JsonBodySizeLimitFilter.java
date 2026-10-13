package com.hnp.backendofflinefirst.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * A ceiling on how many bytes of JSON the server will parse for one request.
 *
 * <h2>Why</h2>
 *
 * <p>There was none. Measured on a running instance: a <b>5 MB JSON body sent with no
 * authentication at all</b> to {@code /api/auth/login} was accepted in full and deserialised by
 * Jackson before the credentials were even looked at — the 401 came back <em>after</em> the work
 * was done. {@code spring.servlet.multipart.*} bounds multipart uploads only, and Tomcat's
 * {@code maxPostSize} governs form encoding, so {@code application/json} had no limit anywhere in
 * the stack.
 *
 * <p>That is the path the Jackson advisories in {@code roadmap.md} §16 need — several of them are
 * denial of service by parser (quadratic backtracking in {@code NumberInput.PATTERN_FLOAT},
 * {@code _reportInvalidToken} with no error-token cap, unbounded {@code Duration} parsing) — and
 * request rate limiting (§2) is not built, so nothing else bounded it either.
 *
 * <h2>Two ceilings, because one number cannot be both safe and workable</h2>
 *
 * <p>A login body is a username, a password and a device label. A <b>sync batch</b> is up to
 * {@code app.sync.batch-max-items} (500) log sheets, each carrying up to
 * {@code app.log-sheets.max-assets-per-sheet} (300) entries with their {@code form_data}. One
 * number low enough to protect the login endpoint would break offline sync, which is the one
 * failure this system exists to prevent. So:
 *
 * <ul>
 *   <li>paths reachable <b>without authentication</b> ({@code app.http.anonymous-json-paths})
 *       get {@code max-anonymous-json-body-bytes} — tight, because nothing legitimate needs
 *       more;</li>
 *   <li>everything else gets {@code max-json-body-bytes}, which is <b>deliberately loose</b>.
 *       The PWA pushes every pending sheet in one batch with no client-side chunking, so the
 *       body grows with however long a tablet was offline — and a tablet refused a 413 keeps
 *       those sheets pending and retries the identical body forever. Bricking a tablet is a
 *       worse failure than the one this filter exists to prevent, so the general ceiling is a
 *       backstop against "unbounded", not a tight bound.</li>
 * </ul>
 *
 * <h2>What it must never touch, and why it cannot</h2>
 *
 * <p><b>Attachments.</b> {@code AttachmentController} consumes {@code multipart/form-data} and
 * nothing in this system uploads a file as base64 inside JSON. This filter applies <em>only</em>
 * when the content type is JSON, so a photograph, a voice note or a video is not examined by it
 * at all — their limits remain {@code app.attachments.max-file-size-bytes} and
 * {@code spring.servlet.multipart.*}, untouched. Form posts from the admin panel
 * ({@code application/x-www-form-urlencoded}) are likewise outside it.
 *
 * <h2>Chunked bodies</h2>
 *
 * <p>A {@code Content-Length} is what every client here actually sends — {@code fetch} with a
 * string body sets it, and so does Capacitor. It is checked first and answered with a clean
 * {@code 413} before a byte of the body is read.
 *
 * <p>A chunked request declares no length, so it cannot be refused up front; the stream is
 * wrapped instead and fails once the limit is passed. That path is deliberately <em>not</em> the
 * pretty one — it exists so that "send it chunked" is not a way around the ceiling, not because
 * any client in this system needs it.
 *
 * <h2>Registration</h2>
 *
 * <p>Not a {@code @Component}: a filter bean that writes a response gets auto-registered into the
 * servlet chain <em>and</em> added to the Spring Security chains, so it runs twice and writes
 * twice (AGENTS.md, and the note on {@code integrationApiKeyFilterRegistration}). It is
 * registered once, explicitly, in {@code WebSecurityConfig}.
 */
@Slf4j
public class JsonBodySizeLimitFilter extends OncePerRequestFilter {

    /** Persian, because this reaches an operator through the PWA's error display. */
    static final String TOO_LARGE_MESSAGE = "حجم درخواست بیش از حد مجاز است.";

    private final long maxBytes;
    private final long maxAnonymousBytes;
    private final List<String> anonymousPaths;

    public JsonBodySizeLimitFilter(long maxBytes, long maxAnonymousBytes, List<String> anonymousPaths) {
        this.maxBytes = maxBytes;
        this.maxAnonymousBytes = maxAnonymousBytes;
        this.anonymousPaths = anonymousPaths == null ? List.of() : List.copyOf(anonymousPaths);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isJson(request.getContentType())) {
            // multipart (attachments), form posts, and every bodyless method leave here untouched.
            chain.doFilter(request, response);
            return;
        }

        long limit = limitFor(request);
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            log.warn("Refused a {} byte JSON body on {} {} (limit {})",
                    declared, request.getMethod(), request.getRequestURI(), limit);
            reject(response);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }
        // No Content-Length: the size only becomes known as it arrives.
        chain.doFilter(new LimitedBodyRequest(request, limit), response);
    }

    /** The tight ceiling for anything a caller can reach before authenticating. */
    private long limitFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return maxAnonymousBytes;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        for (String anonymous : anonymousPaths) {
            if (path.equals(anonymous)) {
                return maxAnonymousBytes;
            }
        }
        return maxBytes;
    }

    /**
     * {@code application/json}, {@code application/merge-patch+json}, and anything else whose
     * subtype ends in {@code +json}. Parsed leniently rather than through {@link MediaType}: a
     * malformed content type on a hostile request must not throw before the size is checked.
     */
    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) {
            type = type.substring(0, semicolon);
        }
        type = type.trim();
        return type.equals(MediaType.APPLICATION_JSON_VALUE) || type.endsWith("+json");
    }

    private static void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONNECTION, "close");
        // Written by hand rather than through Jackson: this filter exists because Jackson is
        // what we are trying not to hand an unbounded body to.
        response.getWriter().write("{\"message\":\"" + TOO_LARGE_MESSAGE + "\"}");
    }

    /** Wraps a chunked body so it cannot exceed the ceiling while being read. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long limit;

        private LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedServletInputStream(super.getInputStream(), limit);
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        private LimitedServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private void count(long n) throws IOException {
            if (n <= 0) {
                return;
            }
            read += n;
            if (read > limit) {
                throw new IOException("JSON request body exceeded " + limit + " bytes");
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
