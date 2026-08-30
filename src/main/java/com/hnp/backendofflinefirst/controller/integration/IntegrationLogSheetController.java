package com.hnp.backendofflinefirst.controller.integration;

import com.hnp.backendofflinefirst.domain.IntegrationLogSheetQuery;
import com.hnp.backendofflinefirst.dto.integration.IntegrationLogSheetDetail;
import com.hnp.backendofflinefirst.dto.integration.IntegrationLogSheetSummary;
import com.hnp.backendofflinefirst.dto.integration.IntegrationPage;
import com.hnp.backendofflinefirst.security.IntegrationApiKeyFilter;
import com.hnp.backendofflinefirst.service.IntegrationLogSheetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * The third-party integration API: finished log sheets, by date range and by id.
 *
 * <p><b>There is no {@code @PreAuthorize} here, and its absence is the design.</b> Everywhere
 * else in this application an endpoint is gated by a {@code METHOD:/path} authority that a role
 * grants — see docs/security.md. That model presumes a user, and here there is none. The gate
 * is the {@code /integration/**} filter chain, which no user of any role can pass and which
 * these handlers cannot be reached without. Adding a permission row would suggest a role could
 * be given this access, which is exactly what must not be true.
 *
 * <p>Both handlers are GET, both are read-only, and neither can reach an unfinished log sheet:
 * the terminal-status filter is written into the repository queries themselves.
 */
@RestController
@RequestMapping("/integration/v1/log-sheets")
@RequiredArgsConstructor
@Tag(name = "Integration API",
        description = "Read-only access to finished log sheets for third-party systems. "
                + "Authenticated with an API key in the X-API-Key header — never a user session or a JWT.")
public class IntegrationLogSheetController {

    private final IntegrationLogSheetService integrationLogSheetService;

    /**
     * The zone a date with no offset is interpreted in.
     *
     * <p>Configuration rather than {@code ZoneId.systemDefault()} on purpose: the meaning of
     * {@code from=2026-08-01} must not change because somebody fixed the server's clock
     * settings or moved the application to a container that runs in UTC. Default matches the
     * attachment sweep's zone, which is the plant's.
     */
    @Value("${app.integration.default-zone:Asia/Tehran}")
    private String defaultZone;

    /**
     * How many rows one page may carry, and how many it carries when unasked.
     *
     * <p>Configurable because the right number depends on the integration: a nightly bulk pull
     * and a minute-by-minute poller want very different pages, and neither is worth a redeploy
     * to change. {@code PageLimits.of} refuses a configured value that would defeat the cap
     * altogether — see {@code ABSOLUTE_MAX_PAGE_SIZE}.
     */
    private IntegrationLogSheetQuery.PageLimits pageLimits;

    @Value("${app.integration.max-page-size:200}")
    private int configuredMaxPageSize;

    @Value("${app.integration.default-page-size:50}")
    private int configuredDefaultPageSize;

    @jakarta.annotation.PostConstruct
    void resolvePageLimits() {
        pageLimits = IntegrationLogSheetQuery.PageLimits.of(
                configuredDefaultPageSize, configuredMaxPageSize);
        if (pageLimits.maxSize() != configuredMaxPageSize
                || pageLimits.defaultSize() != configuredDefaultPageSize) {
            // Said out loud: a silently clamped limit is a setting the operator believes is in
            // force and is not.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Integration page limits clamped: configured default={} max={}, applied default={} max={}",
                    configuredDefaultPageSize, configuredMaxPageSize,
                    pageLimits.defaultSize(), pageLimits.maxSize());
        }
    }

    @Operation(summary = "List finished log sheets in a date range",
            description = """
                    Returns log sheets whose completion instant falls in the half-open range
                    [from, to). Which timestamp that is depends on the status — completedAt for
                    SUBMITTED and VOIDED, expiredAt for EXPIRED, cancelledAt for CANCELLED — and
                    the one that matched is echoed as `finalizedAt` on every row.

                    Only finished sheets are ever returned. Requesting PENDING, ASSIGNED or
                    IN_PROGRESS is a 400, not an empty page.
                    """)
    @GetMapping
    public IntegrationPage<IntegrationLogSheetSummary> list(
            @Parameter(description = "Start of the range, inclusive. ISO-8601 (2026-08-01T00:00:00Z), "
                    + "or a plain date (2026-08-01) meaning midnight in the plant zone.", required = true)
            @RequestParam String from,

            @Parameter(description = "End of the range, EXCLUSIVE. Same formats as 'from'.", required = true)
            @RequestParam String to,

            @Parameter(description = "Comma-separated statuses. Allowed: SUBMITTED, APPROVED, VOIDED, "
                    + "EXPIRED, CANCELLED. Omit for completed rounds — SUBMITTED *and* APPROVED, since "
                    + "approval is a review step on top of completion, not a different outcome.")
            @RequestParam(required = false) String statuses,

            @Parameter(description = "Restrict to one operational unit. Omit for every unit.")
            @RequestParam(required = false) Long unitId,

            @Parameter(description = "Restrict to one template. Omit for every template.")
            @RequestParam(required = false) Long templateId,

            @Parameter(description = "Zero-based page index. Default 0.")
            @RequestParam(required = false) Integer page,

            @Parameter(description = "Rows per page. Default and maximum are configured on the server "
                    + "(app.integration.default-page-size / max-page-size; 50 and 200 out of the box). "
                    + "A larger request is clamped and the effective size is returned in the response.")
            @RequestParam(required = false) Integer size,

            HttpServletRequest request) {

        IntegrationLogSheetQuery query = IntegrationLogSheetQuery.parse(
                from, to, statuses, unitId, templateId, page, size, ZoneId.of(defaultZone), pageLimits);
        IntegrationPage<IntegrationLogSheetSummary> result = integrationLogSheetService.search(query);
        recordResultCount(request, result.items().size());
        return result;
    }

    @Operation(summary = "Full detail of one finished log sheet",
            description = """
                    Returns the sheet, its frozen parameter schema, every asset on it and the
                    values recorded against each.

                    Attachments appear as metadata only — id, kind, size, duration. The bytes are
                    not served through this API.

                    404 for an id that does not exist and for one whose sheet is not finished;
                    the two are deliberately indistinguishable.
                    """)
    @GetMapping("/{id}")
    public IntegrationLogSheetDetail detail(@PathVariable Long id, HttpServletRequest request) {
        IntegrationLogSheetDetail detail = integrationLogSheetService.findDetail(id)
                .orElseThrow(() -> new IntegrationNotFoundException(
                        "No finished log sheet with id " + id + "."));
        recordResultCount(request, detail.assets().size());
        return detail;
    }

    /**
     * Leaves the row count where {@link IntegrationApiKeyFilter} will find it for the usage log.
     *
     * <p>A request attribute because the count is known here and needed there, on the far side
     * of the handler. The alternative is a ThreadLocal, which has to be cleared on every exit
     * path including the exceptional ones — a request attribute dies with the request.
     */
    private static void recordResultCount(HttpServletRequest request, int count) {
        request.setAttribute(IntegrationApiKeyFilter.RESULT_COUNT_ATTRIBUTE, count);
    }
}
