package com.hnp.backendofflinefirst.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.logging.LogSanitizer;
import com.hnp.backendofflinefirst.logging.RequestMdcFilter;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class LoggingAspect {

    public static final String MDC_LAYER = "layer";
    public static final String MDC_FAILED_AT = "failedAt";
    private static final String MDC_ERROR_LOGGED = "errorLogged";

    private static final int MAX_JSON_LENGTH = 4000;
    private static final int MAX_ERROR_MSG_LENGTH = 400;

    private static final String APP_REPO_PACKAGE = "com.hnp.backendofflinefirst.repository";

    private final ObjectMapper objectMapper;

    @Around("within(com.hnp.backendofflinefirst.controller..*)")
    public Object logApiController(ProceedingJoinPoint pjp) throws Throwable {
        return logLayer("API", pjp, true);
    }

    @Around("within(com.hnp.backendofflinefirst.web..*) && !within(com.hnp.backendofflinefirst.web.advice..*)")
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
                String out = compactOutput ? resultSummary(result) : truncate(sanitize(toJson(result)));
                if (infoLevel) {
                    log.info("<<< [{}] {} | {}ms | result={}", layer, site, elapsed(start), out);
                } else {
                    log.debug("<<< [{}] {} | {}ms | result={}", layer, site, elapsed(start), out);
                }
            }
            return result;
        } catch (Throwable t) {
            MDC.put(MDC_FAILED_AT, site);
            if (MDC.get(MDC_ERROR_LOGGED) == null) {
                log.error("!!! [{}] {} | {}ms | {} | {}", layer, site, elapsed(start),
                        t.getClass().getSimpleName(), conciseError(t), t);
                MDC.put(MDC_ERROR_LOGGED, "true");
            } else {
                log.warn("!!! [{}] {} | {}ms | propagating from {} | {}: {}",
                        layer, site, elapsed(start), MDC.get(MDC_FAILED_AT),
                        t.getClass().getSimpleName(), conciseError(t));
            }
            throw t;
        } finally {
            restoreMdcLayer(previousLayer);
        }
    }

    private void logRepoFailure(ProceedingJoinPoint pjp, Class<?> appRepo, Throwable t) throws Throwable {
        String previousLayer = MDC.get(MDC_LAYER);
        MDC.put(MDC_LAYER, "REPO");
        enrichUserMdc();
        try {
            Logger log = LoggerFactory.getLogger(appRepo);
            String site = appRepo.getSimpleName() + "." + pjp.getSignature().getName();
            MDC.put(MDC_FAILED_AT, site);
            if (MDC.get(MDC_ERROR_LOGGED) == null) {
                log.error("!!! [REPO] {} | {} | {}", site, t.getClass().getSimpleName(), conciseError(t), t);
                MDC.put(MDC_ERROR_LOGGED, "true");
            } else {
                log.warn("!!! [REPO] {} | propagating from {} | {}: {}",
                        site, MDC.get(MDC_FAILED_AT), t.getClass().getSimpleName(), conciseError(t));
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
            } else {
                parts.add(truncate(sanitize(toJson(arg))));
            }
        }
        return "[" + String.join(", ", parts) + "]";
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
