package com.hnp.backendofflinefirst.controller.advice;

import com.hnp.backendofflinefirst.dto.ApiErrorResponse;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import com.hnp.backendofflinefirst.ui.FaMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Maps service-layer English exceptions to Persian JSON for {@code /api/**} controllers.
 *
 * <h2>Why a malformed request must not be a 500</h2>
 *
 * <p>{@link #internalError(Exception)} catches {@code Exception}, and an {@code @ExceptionHandler}
 * on a {@code @RestControllerAdvice} is consulted <b>before</b> Spring's own
 * {@code DefaultHandlerExceptionResolver}. So every request-binding failure — a missing
 * parameter, a non-numeric id, a truncated JSON body — used to be answered as
 * {@code 500 خطای نامشخص} even though nothing on the server had gone wrong. Found by sending
 * {@code POST /api/attachments} without its {@code id} parameter during the production review.
 *
 * <p><b>On this system that is not cosmetic, it is a retry loop.</b> The PWA's
 * {@code isPermanentFailure} parks a 4xx and keeps retrying a 5xx — deliberately, because a 5xx
 * is supposed to mean "the server had a problem, try later". A permanently malformed upload
 * answered with 500 is therefore re-sent on <b>every sync pass for the life of the tablet</b>,
 * and the operator sees «خطای نامشخص» each time with nothing they can act on. The worst case was
 * a file over {@code spring.servlet.multipart.max-file-size}: {@link MaxUploadSizeExceededException}
 * is not an {@code IllegalArgumentException}, so unlike the application's own 25 MB ceiling it
 * fell through to 500 and looped forever.
 *
 * <p>The handlers below therefore exist to give the client the status its retry logic needs.
 * {@link #internalError(Exception)} stays as the last resort and keeps logging at ERROR, which is
 * now meaningful: what reaches it really is unexpected.
 *
 * <p>None of them echoes the exception's text to the caller. A binding failure names parameters
 * and types, and an unauthenticated caller can reach {@code /api/auth/login}.
 */
@RestControllerAdvice(basePackages = "com.hnp.backendofflinefirst.controller")
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(ErrorTranslator.toFa(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(ErrorTranslator.toFa(e.getMessage())));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse(ErrorTranslator.toFa(e.getMessage())));
    }

    /**
     * The request never bound: a parameter or multipart part is absent, an id is not a number,
     * the JSON body is unreadable, or a validated body failed its constraints. All of them are
     * faults in what was sent, and none of them becomes true later — so 400, which the client
     * parks instead of retrying.
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<ApiErrorResponse> malformedRequest(Exception e) {
        // WARN, not ERROR: a client sent something wrong, the server is fine. At ERROR these
        // would sit in the log beside real faults and train whoever reads it to skip them.
        log.warn("Malformed API request: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.badRequest().body(new ApiErrorResponse(FaMessages.apiMalformedRequest()));
    }

    /**
     * Past {@code spring.servlet.multipart.max-file-size}. The application's own, lower ceiling
     * ({@code app.attachments.max-file-size-bytes}) throws {@link IllegalArgumentException} and
     * is already a 400; this is the outer one, and it reached the tablet as a 500 that the
     * attachment queue then retried forever.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> payloadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("Upload refused: larger than the multipart limit ({})", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiErrorResponse(FaMessages.apiPayloadTooLarge()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiErrorResponse(FaMessages.apiMalformedRequest()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("Unsupported media type: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiErrorResponse(FaMessages.apiMalformedRequest()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> internalError(Exception e) {
        log.error("Unhandled API error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(FaMessages.genericError()));
    }
}
