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
 */
@Aspect
@Component
@RequiredArgsConstructor
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);
    private static final int MAX_JSON_LENGTH = 4000;

    private final ObjectMapper objectMapper;

    @Around("within(com.hnp.backendofflinefirst.controller..*)")
    public Object logApiController(ProceedingJoinPoint pjp) throws Throwable {
        return logLayer("API", pjp, true);
    }

    @Around("within(com.hnp.backendofflinefirst.web..*)")
    public Object logWebController(ProceedingJoinPoint pjp) throws Throwable {
        return logLayer("WEB", pjp, true);
    }

    @Around("within(com.hnp.backendofflinefirst.service..*)")
    public Object logService(ProceedingJoinPoint pjp) throws Throwable {
        return logLayer("SVC", pjp, false);
    }

    @Around("within(com.hnp.backendofflinefirst.repository..*)")
    public Object logRepository(ProceedingJoinPoint pjp) throws Throwable {
        String label = signature(pjp);
        long start = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug(">>> [REPO] {} | args={}", label, argSummary(pjp.getArgs()));
        }
        try {
            Object result = pjp.proceed();
            if (log.isDebugEnabled()) {
                log.debug("<<< [REPO] {} | {}ms | result={}", label, elapsed(start), resultSummary(result));
            }
            return result;
        } catch (Throwable t) {
            log.error("!!! [REPO] {} | {}ms | {}", label, elapsed(start), t.getMessage(), t);
            throw t;
        }
    }

    private Object logLayer(String layer, ProceedingJoinPoint pjp, boolean infoLevel) throws Throwable {
        enrichUserMdc();
        String label = signature(pjp);
        String httpInfo = infoLevel ? httpInfo() : "";
        String args = formatArgs(pjp.getArgs());
        long start = System.currentTimeMillis();

        if (infoLevel) {
            log.info(">>> [{}] {} | {} | args={}", layer, label, httpInfo, args);
        } else if (log.isDebugEnabled()) {
            log.debug(">>> [{}] {} | args={}", layer, label, args);
        }

        try {
            Object result = pjp.proceed();
            String out = infoLevel ? truncate(sanitize(toJson(result))) : resultSummary(result);
            if (infoLevel) {
                log.info("<<< [{}] {} | {}ms | result={}", layer, label, elapsed(start), out);
            } else if (log.isDebugEnabled()) {
                log.debug("<<< [{}] {} | {}ms | result={}", layer, label, elapsed(start), out);
            }
            return result;
        } catch (Throwable t) {
            log.error("!!! [{}] {} | {}ms | {}", layer, label, elapsed(start), t.getMessage(), t);
            throw t;
        }
    }

    private void enrichUserMdc() {
        AppUserDetails user = SecurityUtils.currentUser();
        if (user != null) {
            MDC.put(RequestMdcFilter.MDC_USER, user.getUsername());
        }
    }

    private String signature(ProceedingJoinPoint pjp) {
        return pjp.getTarget().getClass().getSimpleName() + "." + pjp.getSignature().getName();
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
