package com.hnp.backendofflinefirst.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which failure gets a stack trace, and which gets one line.
 *
 * <p>One exception travelling REPO → SVC → WEB passes through this advice at every layer.
 * Writing the trace three times is useless; writing it nowhere is worse. The rule is that the
 * first advice to see an exception owns it, and the rest log a single propagation line naming
 * where it came from.
 *
 * <p>That rule used to be implemented with a boolean MDC flag that survived until the request
 * ended, which meant a <em>second, unrelated</em> exception in the same request was mistaken
 * for a propagation of the first: logged at WARN, with no stack trace, and therefore never
 * written to {@code error.log} at all — its threshold is ERROR. The second failure simply
 * disappeared. These tests pin the identity-based behaviour that replaced it.
 */
class LoggingAspectErrorAttributionTest {

    private final LoggingAspect aspect = new LoggingAspect(new ObjectMapper());

    private Logger targetLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        MDC.clear();
        targetLogger = (Logger) LoggerFactory.getLogger(FakeService.class);
        appender = new ListAppender<>();
        appender.start();
        targetLogger.addAppender(appender);
        targetLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        targetLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void theFirstLayerToSeeAnExceptionLogsItWithAStackTrace() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(boom))).isSameAs(boom);

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).isNotNull();
    }

    @Test
    void thatSameExceptionSeenAgainHigherUpGetsOneLineAndNoTrace() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(boom))).isSameAs(boom);
        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(boom))).isSameAs(boom);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(1).getThrowableProxy()).isNull();
        assertThat(events.get(1).getFormattedMessage()).contains("propagating from");
    }

    @Test
    void aSecondUNRELATEDExceptionInTheSameRequestStillGetsItsOwnFullEntry() throws Throwable {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");

        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(first))).isSameAs(first);
        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(second))).isSameAs(second);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        // The regression: this used to be WARN with no trace, so it never reached error.log
        // and the second failure was invisible.
        assertThat(events.get(1).getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.get(1).getThrowableProxy()).isNotNull();
        assertThat(events.get(1).getFormattedMessage()).contains("second");
    }

    @Test
    void twoDistinctExceptionsGetDistinctErrorIds() throws Throwable {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");

        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(first))).isSameAs(first);
        String firstId = MDC.get(LoggingAspect.MDC_ERROR_ID);
        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(second))).isSameAs(second);
        String secondId = MDC.get(LoggingAspect.MDC_ERROR_ID);

        assertThat(firstId).isNotNull();
        assertThat(secondId).isNotNull().isNotEqualTo(firstId);
    }

    @Test
    void theErrorIdAppearsInTheMessageSoErrorLogAndAppLogLineUp() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(boom))).isSameAs(boom);
        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(boom))).isSameAs(boom);

        String id = MDC.get(LoggingAspect.MDC_ERROR_ID);
        // Present on the full entry (error.log) and on the propagation line (app.log) — the
        // pattern only prints the MDC copy in error.log, so the message text is what carries
        // it across files.
        assertThat(appender.list.get(0).getFormattedMessage()).contains("errorId=" + id);
        assertThat(appender.list.get(1).getFormattedMessage()).contains("errorId=" + id);
    }

    @Test
    void failedAtRecordsWhereTheExceptionOriginated() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> aspect.logService(throwingJoinPoint(boom))).isSameAs(boom);

        assertThat(MDC.get(LoggingAspect.MDC_FAILED_AT)).isEqualTo("FakeService.doWork");
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst();
    }

    private static ProceedingJoinPoint throwingJoinPoint(Throwable toThrow) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        // MethodSignature, not Signature: the aspect casts to it for getDeclaringType().
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getName()).thenReturn("doWork");
        when(signature.getDeclaringType()).then(invocation -> FakeService.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(new FakeService());
        when(pjp.getArgs()).thenReturn(new Object[0]);
        when(pjp.proceed()).thenThrow(toThrow);
        return pjp;
    }

    /** Target of the advice; its class name is the logger the aspect writes to. */
    static class FakeService {
        void doWork() {
        }
    }
}
