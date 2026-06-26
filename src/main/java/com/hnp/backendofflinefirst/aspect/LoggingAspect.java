package com.hnp.backendofflinefirst.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Aspect
@Component
@RequiredArgsConstructor
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);
    private static final int MAX_JSON_LENGTH = 3000;

    private final ObjectMapper objectMapper;

    // ── REST API controllers ───────────────────────────────────────────────────

    @Around("within(com.hnp.backendofflinefirst.controller..*)")
    public Object logApiController(ProceedingJoinPoint pjp) throws Throwable {
        String label = className(pjp) + "." + pjp.getSignature().getName();
        String httpInfo = httpInfo();
        String args = formatArgs(pjp.getArgs());
        long start = System.currentTimeMillis();

        log.info(">>> [API] {} | {} | request: {}", httpInfo, label, args);

        try {
            Object result = pjp.proceed();
            log.info("<<< [API] {} | {}ms | response: {}", label,
                    elapsed(start), truncate(toJson(result)));
            return result;
        } catch (Throwable t) {
            log.error("!!! [API] {} | {}ms | exception: {}", label, elapsed(start), t.getMessage(), t);
            throw t;
        }
    }

    // ── Service layer ──────────────────────────────────────────────────────────

    @Around("within(com.hnp.backendofflinefirst.service..*)")
    public Object logService(ProceedingJoinPoint pjp) throws Throwable {
        String label = className(pjp) + "." + pjp.getSignature().getName();
        String args = formatArgs(pjp.getArgs());
        long start = System.currentTimeMillis();

        log.debug(">>> [SVC] {} | args: {}", label, args);

        try {
            Object result = pjp.proceed();
            log.debug("<<< [SVC] {} | {}ms | return: {}", label,
                    elapsed(start), truncate(toJson(result)));
            return result;
        } catch (Throwable t) {
            log.error("!!! [SVC] {} | {}ms | exception: {}", label, elapsed(start), t.getMessage(), t);
            throw t;
        }
    }

    // ── Web (Thymeleaf) controllers ────────────────────────────────────────────

    @Around("within(com.hnp.backendofflinefirst.web..*)")
    public Object logWebController(ProceedingJoinPoint pjp) throws Throwable {
        String label = className(pjp) + "." + pjp.getSignature().getName();
        String httpInfo = httpInfo();
        String args = formatArgs(pjp.getArgs());
        long start = System.currentTimeMillis();

        log.info(">>> [WEB] {} | {} | args: {}", httpInfo, label, args);

        try {
            Object result = pjp.proceed();
            log.info("<<< [WEB] {} | {}ms | result: {}", label, elapsed(start), result);
            return result;
        } catch (Throwable t) {
            log.error("!!! [WEB] {} | {}ms | exception: {}", label, elapsed(start), t.getMessage(), t);
            throw t;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String className(ProceedingJoinPoint pjp) {
        return pjp.getTarget().getClass().getSimpleName();
    }

    private String httpInfo() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "";
            HttpServletRequest req = attrs.getRequest();
            return req.getMethod() + " " + req.getRequestURI();
        } catch (Exception e) {
            return "";
        }
    }

    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        List<String> parts = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Model
                    || arg instanceof RedirectAttributes
                    || arg instanceof HttpServletRequest
                    || arg instanceof HttpServletResponse) {
                continue; // skip Spring infrastructure objects
            }
            if (arg instanceof MultipartFile f) {
                parts.add("{file:\"" + f.getOriginalFilename() + "\", size:" + f.getSize() + "}");
            } else {
                parts.add(truncate(toJson(arg)));
            }
        }
        return "[" + String.join(", ", parts) + "]";
    }

    private String toJson(Object obj) {
        if (obj == null) return "null";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private String truncate(String s) {
        if (s == null) return "null";
        return s.length() > MAX_JSON_LENGTH
                ? s.substring(0, MAX_JSON_LENGTH) + "...[truncated]"
                : s;
    }

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }
}
