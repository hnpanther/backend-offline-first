package com.hnp.backendofflinefirst;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error-page hardening in {@code application.properties} is actually bound.
 *
 * <h2>Why this needs a test at all</h2>
 *
 * <p>These four settings spent this project's whole life doing nothing. They were written with
 * Boot 3's {@code server.error.*} prefix, and Boot 4.0 moved the binding from
 * {@code ServerProperties} to {@link WebProperties} — {@code ServerProperties} has no
 * {@code getError()} any more, so the old names bind to no target. Spring's own metadata marks
 * them deprecated at {@code level=error}, which means removed rather than discouraged.
 *
 * <p><b>Nothing failed.</b> An unbound property is not an error in Spring Boot; it is simply
 * ignored. Three of the four restated a default and so were invisible either way, but
 * {@code whitelabel.enabled=false} did not — the Whitelabel error page was enabled in production
 * while the comment above the block said it was off. A configuration file cannot be reviewed for
 * this: the line looks exactly like a line that works.
 *
 * <p>So this asserts the <em>effect</em> — the values Spring actually resolved — rather than the
 * text of the file. A future rename (Boot has now done it once) fails here instead of silently
 * turning the hardening off again.
 */
class ErrorPagePropertiesBindIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebProperties webProperties;

    @Test
    void theErrorPageHardeningIsBoundAndNotJustWrittenDown() {
        ErrorProperties error = webProperties.getError();

        assertThat(error.getWhitelabel().isEnabled())
                .as("the Whitelabel error page must be off — this is the one of the four that was "
                        + "silently doing nothing under the old server.error.* prefix")
                .isFalse();

        assertThat(error.getIncludeStacktrace())
                .as("a stack trace must never reach an HTML error page")
                .isEqualTo(ErrorProperties.IncludeAttribute.NEVER);

        assertThat(error.getIncludeMessage())
                .as("raw exception text must never reach an HTML error page")
                .isEqualTo(ErrorProperties.IncludeAttribute.NEVER);

        assertThat(error.isIncludeException())
                .as("the exception class name must not be exposed")
                .isFalse();
    }
}
