package com.hnp.backendofflinefirst.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingAspectTest {

    @Test
    void conciseError_extractsPostgresMessage() {
        String msg = LoggingAspect.conciseError(new RuntimeException(
                "could not execute statement [ERROR: update or delete on table \"asset_classes\" "
                        + "violates RESTRICT setting of foreign key constraint \"fk_asset_entries_class\" "
                        + "on table \"asset_entries\"\n"
                        + "  Detail: Key (id)=(1) is referenced from table \"asset_entries\".] "
                        + "[delete from asset_classes where id=?]"));

        assertTrue(msg.contains("ERROR:"));
        assertTrue(msg.contains("asset_entries"));
        assertTrue(msg.contains("Detail: Key (id)=(1)"));
    }

    // -----------------------------------------------------------------------
    // What may be written into a log line
    //
    // The aspect wraps every controller, service and repository call and serialises the
    // arguments with Jackson. That is fine for the values this application passes around and
    // catastrophic for framework objects, which is not hypothetical: an MVC interceptor under
    // `web..*` received the request handler as an argument, and for a static resource that
    // handler is a `ResourceHttpRequestHandler`. Jackson walked it, called
    // `Resource.getContentAsByteArray()`, and base64-encoded the file into the log — every CSS,
    // font and script request, until the heap ran out and the login page stopped loading.
    //
    // Truncation did not save it: `MAX_JSON_LENGTH` is applied to the finished string, long
    // after the memory has been spent. The rule has to be about what is serialised at all.
    // -----------------------------------------------------------------------

    private final LoggingAspect aspect = new LoggingAspect(new ObjectMapper());

    private String formatArgs(Object... args) {
        return (String) ReflectionTestUtils.invokeMethod(aspect, "formatArgs", (Object) args);
    }

    @Test
    void aResourceHandlerIsNamedRatherThanRead() {
        // The exact object that took the application down.
        String line = formatArgs(new ResourceHttpRequestHandler());

        assertThat(line).isEqualTo("[ResourceHttpRequestHandler]");
    }

    @Test
    void aResourceIsNeverSerialisedIntoTheLog() {
        String line = formatArgs(new ByteArrayResource("pretend this is a 200KB font".getBytes()));

        assertThat(line).doesNotContain("pretend this is");
        assertThat(line).contains("ByteArrayResource");
    }

    @Test
    void aModelAndViewIsNamedRatherThanExpanded() {
        ModelAndView mv = new ModelAndView("redirect:/asset-entries");
        mv.addObject("assetEntries", List.of("a", "b"));

        assertThat(formatArgs(mv)).isEqualTo("[ModelAndView]");
    }

    @Test
    void rawBytesAreReportedByLengthNotByContent() {
        // An attachment upload passes the file's bytes into the service layer. Serialising those
        // is a base64 copy of the image on every call.
        String line = formatArgs(new byte[]{1, 2, 3, 4, 5});

        assertThat(line).isEqualTo("[byte[5]]");
    }

    @Test
    void anAuthenticationIsNamedRatherThanSerialised() {
        // This one would leak rather than exhaust: serialising a principal writes out whatever
        // it holds.
        var auth = new UsernamePasswordAuthenticationToken("ali", "s3cr3t-password");

        String line = formatArgs(auth);

        assertThat(line).doesNotContain("s3cr3t-password");
        assertThat(line).isEqualTo("[UsernamePasswordAuthenticationToken]");
    }

    @Test
    void servletObjectsStayOutOfTheLineEntirely() {
        // Skipped rather than named: they are on every single web call and say nothing.
        assertThat(formatArgs(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isEqualTo("[]");
    }

    @Test
    void ordinaryBusinessArgumentsAreStillLoggedInFull() {
        // The whole point of the aspect. Narrowing it must not make the logs useless: ids,
        // strings, nulls, application DTOs and paging all still read as they did.
        String line = formatArgs(42L, "Pic", null, PageRequest.of(2, 25),
                new SelectOptionDto("7", "پمپ ۱", null));

        assertThat(line).contains("42").contains("\"Pic\"").contains("null");
        assertThat(line).contains("پمپ ۱");
    }

    @Test
    void anExceptionArgumentKeepsItsConciseMessage() {
        String line = formatArgs(new IllegalStateException("This log sheet cannot be completed."));

        assertThat(line).contains("IllegalStateException");
        assertThat(line).contains("cannot be completed");
    }

    // -----------------------------------------------------------------------
    // What may be written as a result
    // -----------------------------------------------------------------------

    private String formatResult(Object result) {
        return (String) ReflectionTestUtils.invokeMethod(aspect, "formatResult", result);
    }

    @Test
    void anAttachmentDownloadLogsItsSizeNotItsContent() {
        // GET /api/attachments/{id} answers with the file itself. Serialising that meant
        // base64-encoding up to 25 MB to build a line truncated to 4,000 characters.
        byte[] file = new byte[2_000_000];

        String line = formatResult(org.springframework.http.ResponseEntity.ok(file));

        assertThat(line).isEqualTo("{status:200,body:byte[2000000]}");
    }

    @Test
    void aResponseEntityIsSummarisedRatherThanExpanded() {
        // And the headers object, which Jackson used to expand into forty mostly-null fields on
        // every API call, is gone from the line entirely.
        var body = new SelectOptionDto("7", "پمپ ۱", null);

        String line = formatResult(org.springframework.http.ResponseEntity.status(201).body(body));

        assertThat(line).startsWith("{status:201,body:");
        assertThat(line).contains("پمپ ۱");
        assertThat(line).doesNotContain("headers");
    }

    @Test
    void anEmptyResponseKeepsItsStatus() {
        assertThat(formatResult(org.springframework.http.ResponseEntity.noContent().build()))
                .isEqualTo("{status:204,body:null}");
    }

    @Test
    void aViewNameIsStillLoggedAsItself() {
        // Panel controllers return a view name, and that is the single most useful thing in the
        // line — narrowing the rule must not lose it.
        assertThat(formatResult("redirect:/asset-entries?q=pump&page=3"))
                .contains("redirect:/asset-entries");
    }

    @Test
    void aStreamingOrFrameworkResultIsNamedRatherThanConsumed() {
        assertThat(formatResult(new ByteArrayResource("bytes".getBytes())))
                .isEqualTo("ByteArrayResource");
    }

    /** An application record that happens to carry a file — the real one is DownloadedAttachment. */
    record Downloaded(String id, byte[] content) {}

    @Test
    void bytesNestedInsideABusinessObjectAreNotSerialisedEither() {
        // The top-level guards cannot see this one: the record IS a business value, and the file
        // is a field on it. Only the serialiser can refuse at arbitrary depth.
        byte[] file = new byte[1_500_000];

        String line = formatResult(new Downloaded("att-1", file));

        assertThat(line).contains("att-1");
        assertThat(line).contains("byte[1500000]");
        assertThat(line.length()).isLessThan(200);
    }

    @Test
    void bytesNestedInsideAnArgumentAreNotSerialisedEither() {
        String line = formatArgs(new Downloaded("att-2", new byte[900_000]));

        assertThat(line).contains("byte[900000]");
        assertThat(line.length()).isLessThan(200);
    }
}
