package com.hnp.backendofflinefirst.controller.integration;

import com.hnp.backendofflinefirst.dto.integration.IntegrationErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors for {@code /integration/**}, in English JSON with a machine-readable code.
 *
 * <p><b>Why this exists at all, given {@code ApiExceptionHandler} already covers
 * {@code com.hnp.backendofflinefirst.controller}.</b> That handler answers in Persian, through
 * {@code ErrorTranslator} — correct for the mobile app, whose messages a Persian-speaking
 * operator reads on a tablet, and wrong for a machine client whose code has to branch on what
 * went wrong. A localised sentence is not a contract.
 *
 * <p><b>Why {@code @Order(HIGHEST_PRECEDENCE)}.</b> This package sits <em>inside</em> the one
 * {@code ApiExceptionHandler} declares, so both advices apply to these controllers. Spring
 * resolves an exception against advices in order and takes the first with a matching handler,
 * so the order annotation is the whole of what makes this one win. It cannot affect any other
 * controller: {@code basePackages} confines it to this package.
 *
 * <p>The exception text is passed through for a 400 — it names the parameter the caller got
 * wrong, which is the only way they will fix it — and never for a 500, where it could carry
 * a table name, a constraint name or a fragment of SQL.
 */
@RestControllerAdvice(basePackages = "com.hnp.backendofflinefirst.controller.integration")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class IntegrationApiExceptionHandler {

    @ExceptionHandler(IntegrationNotFoundException.class)
    public ResponseEntity<IntegrationErrorResponse> notFound(IntegrationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(IntegrationErrorResponse.notFound(e.getMessage()));
    }

    /** Everything {@code IntegrationLogSheetQuery.parse} rejects. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<IntegrationErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(IntegrationErrorResponse.invalidRequest(e.getMessage()));
    }

    /**
     * A required parameter was not sent at all.
     *
     * <p>Spring's default answer is a 400 with a Boot error page shape ({@code timestamp},
     * {@code path}, {@code error}) that shares no field with this API's error object, so a
     * client that parses one cannot parse the other. Naming the parameter is the useful part.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<IntegrationErrorResponse> missingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(IntegrationErrorResponse.invalidRequest(
                "'" + e.getParameterName() + "' is required."));
    }

    /** {@code unitId=abc} — a parameter of the wrong type. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<IntegrationErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(IntegrationErrorResponse.invalidRequest(
                "'" + e.getName() + "' has an invalid value."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IntegrationErrorResponse> internalError(Exception e) {
        log.error("Unhandled integration API error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(IntegrationErrorResponse.internalError());
    }
}
