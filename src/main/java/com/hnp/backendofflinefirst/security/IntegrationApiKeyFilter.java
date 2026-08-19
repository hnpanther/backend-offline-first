package com.hnp.backendofflinefirst.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ApiKeyUsageOutcome;
import com.hnp.backendofflinefirst.dto.integration.IntegrationErrorResponse;
import com.hnp.backendofflinefirst.entity.ApiKey;
import com.hnp.backendofflinefirst.entity.ApiKeyUsage;
import com.hnp.backendofflinefirst.logging.RequestMdcFilter;
import com.hnp.backendofflinefirst.service.ApiKeyUsageWriteService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The whole of authentication for {@code /integration/**}: an {@code X-API-Key} header, and
 * nothing else.
 *
 * <p>No session, no JWT, no form login, no user. This is the concrete meaning of "third-party
 * endpoints must be separate from normal user authentication APIs" — the separation is a
 * different filter chain with a different credential and a different principal type, not a
 * different URL prefix over the same machinery.
 *
 * <p><b>It also records usage</b>, and that is not an accident of convenience. The filter is
 * the only place that sees every request including the ones it refuses, and the refusals are
 * the rows worth having: a run of {@code INVALID_KEY} from one address is the only evidence
 * anybody will get that somebody is guessing keys. A recorder placed in the controller, or an
 * interceptor behind the security chain, would see none of them.
 *
 * <p><b>The header is never logged, in any branch.</b> {@code MISSING_KEY} and
 * {@code INVALID_KEY} rows record that a bad key arrived and where from, never what it was —
 * an audit trail full of near-miss credentials is a credential store.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrationApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    /**
     * Where a handler leaves the number of rows it returned, for the usage row.
     *
     * <p>A request attribute rather than a return value because the count is known in the
     * controller and needed in the filter, and threading it back would mean either a
     * {@code ThreadLocal} or every handler returning a wrapper. The attribute is optional:
     * a handler that sets nothing simply records a null count.
     */
    public static final String RESULT_COUNT_ATTRIBUTE = "integration.resultCount";

    private static final int MAX_QUERY_LENGTH = 1000;
    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final int MAX_PATH_LENGTH = 512;

    private final ApiKeyAuthenticator apiKeyAuthenticator;
    private final ApiKeyUsageWriteService usageWriteService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        ApiKeyAuthenticator.Result result =
                apiKeyAuthenticator.authenticate(request.getHeader(HEADER), startedAt);

        if (!result.isAuthenticated()) {
            // One response for every rejection reason. The real reason goes to the usage row,
            // where an administrator can read it and the caller cannot — telling somebody
            // holding a key they should not have that it is "merely disabled" is telling them
            // what to try next.
            writeError(response, HttpStatus.UNAUTHORIZED, IntegrationErrorResponse.unauthorized());
            record(request, response, result, startedAt, null);
            log.warn("Integration API request refused ({}) from {} for {} {}",
                    result.outcome(), clientIp(request), request.getMethod(), request.getRequestURI());
            return;
        }

        ApiKey key = result.key();
        IntegrationClient client = IntegrationClient.from(key);
        SecurityContextHolder.getContext().setAuthentication(new IntegrationAuthenticationToken(client));
        // The MDC "user" slot names the calling system, so every log line this request produces
        // is attributable in exactly the way a user's request is. UserMdcFilter does not run on
        // this chain — it reads AppUserDetails, which an integration principal deliberately is
        // not — so the value is set here.
        MDC.put(RequestMdcFilter.MDC_USER, client.clientName());
        try {
            filterChain.doFilter(request, response);
            record(request, response, result, startedAt, resultCountOf(request));
        } finally {
            MDC.remove(RequestMdcFilter.MDC_USER);
            SecurityContextHolder.clearContext();
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                            IntegrationErrorResponse body) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private void record(HttpServletRequest request, HttpServletResponse response,
                        ApiKeyAuthenticator.Result result, long startedAt, Integer resultCount) {
        ApiKeyUsage usage = new ApiKeyUsage();
        ApiKey key = result.key();
        if (key != null) {
            usage.setApiKeyId(key.getId());
            usage.setKeyId(key.getKeyId());
            usage.setClientName(key.getClientName());
        }
        usage.setMethod(request.getMethod());
        usage.setPath(trim(request.getRequestURI(), MAX_PATH_LENGTH));
        // The filters the caller asked for. The key is a header, so it is structurally
        // impossible for it to appear here — which is the other reason it is a header.
        usage.setQueryString(trim(request.getQueryString(), MAX_QUERY_LENGTH));
        usage.setStatusCode(response.getStatus());
        usage.setOutcome(outcomeFor(result, response.getStatus()));
        usage.setResultCount(resultCount);
        usage.setDurationMs(System.currentTimeMillis() - startedAt);
        usage.setIpAddress(trim(clientIp(request), 64));
        usage.setUserAgent(trim(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH));
        usage.setRequestedAt(startedAt);
        usageWriteService.save(usage);
    }

    /**
     * An authentication rejection already knows why. A served request does not, so its outcome
     * is read back off the status the handler produced — which is what makes a 400 from a bad
     * date range distinguishable from a 200 in the usage log.
     */
    private static ApiKeyUsageOutcome outcomeFor(ApiKeyAuthenticator.Result result, int status) {
        if (!result.isAuthenticated()) {
            return result.outcome();
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            return ApiKeyUsageOutcome.NOT_FOUND;
        }
        if (status >= 500) {
            return ApiKeyUsageOutcome.ERROR;
        }
        if (status >= 400) {
            return ApiKeyUsageOutcome.BAD_REQUEST;
        }
        return ApiKeyUsageOutcome.OK;
    }

    private static Integer resultCountOf(HttpServletRequest request) {
        Object value = request.getAttribute(RESULT_COUNT_ATTRIBUTE);
        return value instanceof Integer count ? count : null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
