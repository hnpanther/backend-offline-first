package com.hnp.backendofflinefirst.aspect;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.hnp.backendofflinefirst.logging.LogSanitizer;
import com.hnp.backendofflinefirst.logging.RequestMdcFilter;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-cutting request/service/repository logging with sanitized payloads and MDC context.
 * <ul>
 *   <li>WEB/API — entry/exit at INFO (request boundary)</li>
 *   <li>SVC/REPO — entry/exit at DEBUG (avoids Import/bulk spam); serialize only when enabled</li>
 *   <li>Errors — always WARN/ERROR</li>
 * </ul>
 * Intentional {@code log.info} and {@code BusinessEventLogger} summaries are unchanged.
 */
@Aspect
@Component
public class LoggingAspect {

    public static final String MDC_LAYER = "layer";
    public static final String MDC_FAILED_AT = "failedAt";
    /** Short id shared by the full-stack entry and every propagation line for one exception. */
    public static final String MDC_ERROR_ID = "errorId";

    /**
     * Identity of the exception whose stack trace has already been written.
     * <p>
     * As one exception travels REPO → SVC → WEB it passes through this advice at every layer.
     * Only the innermost writes the trace; the outer layers log a single line naming where it
     * came from. That is what stops one failure producing three identical stack traces.
     * <p>
     * <b>It holds the exception's identity, not a boolean.</b> It used to be a plain
     * "errorLogged=true" flag that lived until the request ended — so a <em>second, unrelated</em>
     * exception in the same request was treated as a propagation of the first: logged at WARN,
     * with no stack trace, and therefore never written to {@code error.log} at all, whose
     * threshold is ERROR. The second failure vanished. Keyed on identity, a genuinely new
     * exception is recognised as new and logged in full.
     */
    private static final String MDC_LOGGED_THROWABLE = "loggedThrowableId";

    private static final int MAX_JSON_LENGTH = 4000;
    private static final int MAX_ERROR_MSG_LENGTH = 400;

    private static final String APP_REPO_PACKAGE = "com.hnp.backendofflinefirst.repository";

    /**
     * A private copy of the application's mapper that can never write a byte array's contents.
     *
     * <p>{@link #formatArgs} and {@link #formatResult} already refuse a {@code byte[]} they can
     * see, but they only see the top level. {@code AttachmentService.DownloadedAttachment} is an
     * ordinary application record — exactly the kind of value this aspect exists to log — that
     * happens to hold the file inside it, and Jackson would base64 that field while serialising
     * the record. Fixing it at the serialiser instead of at the call site is what makes the depth
     * irrelevant, now and for whatever record holds bytes next.
     *
     * <p>A copy, not the shared bean: the same mapper serialises API responses, where a byte array
     * genuinely is the payload.
     */
    private final ObjectMapper objectMapper;

    public LoggingAspect(ObjectMapper applicationObjectMapper) {
        SimpleModule logSafety = new SimpleModule();
        logSafety.addSerializer(byte[].class, new JsonSerializer<>() {
            @Override
            public void serialize(byte[] value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                gen.writeString("byte[" + value.length + "]");
            }
        });
        this.objectMapper = applicationObjectMapper.copy().registerModule(logSafety);
    }

    @Around("within(com.hnp.backendofflinefirst.controller..*)")
    public Object logApiController(ProceedingJoinPoint pjp) throws Throwable {
        return logLayer("API", pjp, true);
    }

    /**
     * Panel controllers only.
     *
     * <p>{@code web.support} is excluded because it holds MVC infrastructure — interceptors and
     * helpers — rather than request handlers. An interceptor's arguments are the servlet request,
     * the response, the <b>handler</b> and the {@code ModelAndView}, none of which is a business
     * value, and one of which is capable of dragging a whole file into a log line: for a static
     * resource the handler is a {@code ResourceHttpRequestHandler}, and Jackson happily calls
     * {@code Resource.getContentAsByteArray()} while serialising it. That turned every CSS, font
     * and script request into a base64 copy of the file in memory and took the login page down
     * with {@code OutOfMemoryError}. {@link #formatArgs} refuses those types now as well; this
     * exclusion is the other half, because infrastructure entry/exit is noise even when it is
     * cheap.
     */
    @Around("within(com.hnp.backendofflinefirst.web..*) "
            + "&& !within(com.hnp.backendofflinefirst.web.advice..*) "
            + "&& !within(com.hnp.backendofflinefirst.web.support..*)")
    public Object logWebController(ProceedingJoinPoint pjp) throws Throwable {
        return logLayer("WEB", pjp, true);
    }

    /**
     * Service entry/exit is DEBUG: Import and other bulk paths call many services per row.
     * Explicit {@code log.info} / {@link com.hnp.backendofflinefirst.logging.BusinessEventLogger}
     * summaries stay on INFO.
     */
    @Around("within(com.hnp.backendofflinefirst.service..*)")
    public Object logService(ProceedingJoinPoint pjp) throws Throwable {
        return logLayer("SVC", pjp, false);
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object logRepository(ProceedingJoinPoint pjp) throws Throwable {
        Class<?> appRepo = resolveAppRepositoryInterface(pjp);
        if (appRepo == null) {
            return pjp.proceed();
        }
        if (isAppRepositoryMethod(pjp)) {
            return logLayer("REPO", pjp, false, true, appRepo);
        }
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            logRepoFailure(pjp, appRepo, t);
            throw t;
        }
    }

    private Object logLayer(String layer, ProceedingJoinPoint pjp, boolean infoLevel) throws Throwable {
        return logLayer(layer, pjp, infoLevel, false, null);
    }

    private Object logLayer(String layer, ProceedingJoinPoint pjp, boolean infoLevel, boolean compactOutput)
            throws Throwable {
        return logLayer(layer, pjp, infoLevel, compactOutput, null);
    }

    private Object logLayer(String layer, ProceedingJoinPoint pjp, boolean infoLevel, boolean compactOutput,
            Class<?> loggerType) throws Throwable {
        Logger log = loggerFor(pjp, loggerType);
        String previousLayer = MDC.get(MDC_LAYER);
        MDC.put(MDC_LAYER, layer);
        enrichUserMdc();

        String site = callSite(pjp, loggerType);
        boolean logVerbose = infoLevel ? log.isInfoEnabled() : log.isDebugEnabled();
        String httpInfo = (infoLevel && logVerbose) ? httpInfo() : "";
        long start = System.currentTimeMillis();

        try {
            if (logVerbose) {
                String args = compactOutput ? argSummary(pjp.getArgs()) : formatArgs(pjp.getArgs());
                if (infoLevel) {
                    log.info(">>> [{}] {} | {} | args={}", layer, site, httpInfo, args);
                } else {
                    log.debug(">>> [{}] {} | args={}", layer, site, args);
                }
            }

            Object result = pjp.proceed();

            if (logVerbose) {
                String out = compactOutput ? resultSummary(result) : formatResult(result);
                if (infoLevel) {
                    log.info("<<< [{}] {} | {}ms | result={}", layer, site, elapsed(start), out);
                } else {
                    log.debug("<<< [{}] {} | {}ms | result={}", layer, site, elapsed(start), out);
                }
            }
            return result;
        } catch (Throwable t) {
            String origin = MDC.get(MDC_FAILED_AT);
            String errorId = errorIdOf(t);
            if (claimThrowable(t)) {
                MDC.put(MDC_FAILED_AT, site);
                log.error("!!! [{}] {} | {}ms | errorId={} | {} | {}", layer, site, elapsed(start),
                        errorId, t.getClass().getSimpleName(), conciseError(t), t);
            } else {
                log.warn("!!! [{}] {} | {}ms | errorId={} | propagating from {} | {}: {}",
                        layer, site, elapsed(start), errorId, origin,
                        t.getClass().getSimpleName(), conciseError(t));
            }
            throw t;
        } finally {
            restoreMdcLayer(previousLayer);
        }
    }

    /**
     * A short, stable handle for one exception instance.
     * <p>
     * It appears in the message text of both the full entry in {@code error.log} and every
     * propagation line the same failure leaves in {@code app.log}, so the two files line up on
     * something better than a timestamp — which stops working the moment two requests fail in
     * the same second. It is also short enough for a person to quote in a support message.
     * The identity hash is not unique for all time, but it does not need to be: it only has to
     * distinguish the exceptions alive during one request.
     */
    private static String errorIdOf(Throwable t) {
        return Integer.toHexString(System.identityHashCode(t));
    }

    /**
     * True when this advice is the first to see {@code t}, and therefore the one that logs its
     * stack trace. Also publishes {@link #MDC_ERROR_ID} so the JSON layout gets it as a field.
     */
    private static boolean claimThrowable(Throwable t) {
        String id = errorIdOf(t);
        if (id.equals(MDC.get(MDC_LOGGED_THROWABLE))) {
            return false;
        }
        MDC.put(MDC_LOGGED_THROWABLE, id);
        MDC.put(MDC_ERROR_ID, id);
        return true;
    }

    private void logRepoFailure(ProceedingJoinPoint pjp, Class<?> appRepo, Throwable t) throws Throwable {
        String previousLayer = MDC.get(MDC_LAYER);
        MDC.put(MDC_LAYER, "REPO");
        enrichUserMdc();
        try {
            Logger log = LoggerFactory.getLogger(appRepo);
            String site = appRepo.getSimpleName() + "." + pjp.getSignature().getName();
            String origin = MDC.get(MDC_FAILED_AT);
            String errorId = errorIdOf(t);
            if (claimThrowable(t)) {
                MDC.put(MDC_FAILED_AT, site);
                log.error("!!! [REPO] {} | errorId={} | {} | {}",
                        site, errorId, t.getClass().getSimpleName(), conciseError(t), t);
            } else {
                log.warn("!!! [REPO] {} | errorId={} | propagating from {} | {}: {}",
                        site, errorId, origin, t.getClass().getSimpleName(), conciseError(t));
            }
        } finally {
            restoreMdcLayer(previousLayer);
        }
    }

    private static Class<?> resolveAppRepositoryInterface(ProceedingJoinPoint pjp) {
        for (Class<?> iface : pjp.getTarget().getClass().getInterfaces()) {
            if (APP_REPO_PACKAGE.equals(iface.getPackageName())) {
                return iface;
            }
        }
        return null;
    }

    private static boolean isAppRepositoryMethod(ProceedingJoinPoint pjp) {
        return APP_REPO_PACKAGE.equals(pjp.getSignature().getDeclaringType().getPackageName());
    }

    private static Logger loggerFor(ProceedingJoinPoint pjp, Class<?> overrideType) {
        if (overrideType != null) {
            return LoggerFactory.getLogger(overrideType);
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        return LoggerFactory.getLogger(signature.getDeclaringType());
    }

    private static String callSite(ProceedingJoinPoint pjp, Class<?> overrideType) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String typeName = overrideType != null
                ? overrideType.getSimpleName()
                : signature.getDeclaringType().getSimpleName();
        return typeName + "." + signature.getName();
    }

    static String conciseError(Throwable t) {
        Throwable root = rootCause(t);
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        int errorIdx = msg.indexOf("ERROR:");
        if (errorIdx >= 0) {
            msg = msg.substring(errorIdx);
        }
        int detailIdx = msg.indexOf("Detail:");
        if (detailIdx > 0) {
            int lineEnd = msg.indexOf('\n', detailIdx);
            if (lineEnd > 0) {
                msg = msg.substring(0, lineEnd).trim();
            }
        }
        int bracketIdx = msg.indexOf("] [");
        if (bracketIdx > 0 && msg.startsWith("could not execute")) {
            msg = msg.substring(0, bracketIdx).trim();
        }
        return msg.length() > MAX_ERROR_MSG_LENGTH
                ? msg.substring(0, MAX_ERROR_MSG_LENGTH) + "..."
                : msg;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void restoreMdcLayer(String previousLayer) {
        if (previousLayer != null) {
            MDC.put(MDC_LAYER, previousLayer);
        } else {
            MDC.remove(MDC_LAYER);
        }
    }

    private void enrichUserMdc() {
        AppUserDetails user = SecurityUtils.currentUser();
        if (user != null) {
            MDC.put(RequestMdcFilter.MDC_USER, user.getUsername());
        }
    }

    private String httpInfo() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "";
            }
            HttpServletRequest req = attrs.getRequest();
            return req.getMethod() + " " + req.getRequestURI();
        } catch (Exception e) {
            return "";
        }
    }

    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Model
                    || arg instanceof RedirectAttributes
                    || arg instanceof HttpServletRequest
                    || arg instanceof HttpServletResponse) {
                continue;
            }
            if (arg instanceof Throwable throwable) {
                parts.add("{" + throwable.getClass().getSimpleName() + ": " + conciseError(throwable) + "}");
                continue;
            }
            if (arg instanceof MultipartFile f) {
                parts.add("{file:\"" + f.getOriginalFilename() + "\",size:" + f.getSize() + "}");
                continue;
            }
            if (arg instanceof byte[] bytes) {
                // Never the bytes themselves: an attachment upload would base64 the whole image
                // into the log line, at DEBUG, on every call.
                parts.add("byte[" + bytes.length + "]");
                continue;
            }
            if (arg != null && !isBusinessValue(arg)) {
                parts.add(arg.getClass().getSimpleName());
                continue;
            }
            parts.add(truncate(sanitize(toJson(arg))));
        }
        return "[" + String.join(", ", parts) + "]";
    }

    /**
     * Whether an argument is safe and useful to serialise in full.
     *
     * <p><b>An allowlist, not a denylist, and deliberately so.</b> The failure this prevents was
     * not a type anybody had thought about and dismissed — it was a framework object nobody
     * expected to reach here at all, whose getters read files. Naming the types that *are*
     * business values (this application's own DTOs and entities, JDK value types, Spring Data's
     * paging types) keeps every future framework object out by default, including the ones that
     * would leak rather than exhaust: a Spring Security {@code Authentication} serialises its
     * principal, and a {@code Resource} serialises the file.
     *
     * <p>Anything rejected is still logged — by class name — so the call is not silently thinner
     * than it looks.
     */
    private boolean isBusinessValue(Object arg) {
        Class<?> type = arg.getClass();
        if (type.isEnum() || type.isPrimitive()) {
            return true;
        }
        Package pkg = type.getPackage();
        String name = pkg == null ? "" : pkg.getName();
        return name.startsWith("com.hnp.backendofflinefirst")
                || name.equals("java.lang")
                || name.equals("java.util")
                || name.equals("java.time")
                || name.equals("java.math")
                || name.startsWith("org.springframework.data.domain");
    }

    private String argSummary(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null) {
                parts.add("null");
            } else if (arg instanceof Long || arg instanceof Integer || arg instanceof String) {
                parts.add(arg.getClass().getSimpleName() + "=" + arg);
            } else {
                parts.add(arg.getClass().getSimpleName());
            }
        }
        return parts.toString();
    }

    /**
     * The return value, rendered so that logging it can never cost more than the call itself.
     *
     * <p>The argument side had this wrong and took the application down; the return side had the
     * same hole and a worse one to fall into. {@code GET /api/attachments/{id}} answers with a
     * {@code ResponseEntity<byte[]>}, so serialising the result meant base64-encoding **the whole
     * attachment** — up to the 25 MB ceiling — to build a line that {@link #truncate} then cut to
     * 4,000 characters. The memory is spent before the truncation is reached, which is why
     * truncating a finished string is not a safety measure.
     *
     * <p>Summarising a {@code ResponseEntity} rather than serialising it also removes a great deal
     * of noise: Jackson expanded its headers object into forty mostly-null fields on every single
     * API call, which is what those unreadable `result={"headers":{"empty":true,...}}` lines were.
     */
    private String formatResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        if (result instanceof org.springframework.http.ResponseEntity<?> response) {
            return "{status:" + response.getStatusCode().value()
                    + ",body:" + formatResult(response.getBody()) + "}";
        }
        if (!isBusinessValue(result)) {
            return result.getClass().getSimpleName();
        }
        return truncate(sanitize(toJson(result)));
    }

    private String resultSummary(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof Iterable<?> iterable) {
            long count = 0;
            for (Object ignored : iterable) {
                count++;
            }
            return iterable.getClass().getSimpleName() + "(size=" + count + ")";
        }
        if (result instanceof java.util.Optional<?> opt) {
            return "Optional[" + (opt.isPresent() ? opt.get().getClass().getSimpleName() : "empty") + "]";
        }
        return result.getClass().getSimpleName();
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private String sanitize(String s) {
        return LogSanitizer.sanitize(s);
    }

    private String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > MAX_JSON_LENGTH
                ? s.substring(0, MAX_JSON_LENGTH) + "...[truncated]"
                : s;
    }

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }
}
