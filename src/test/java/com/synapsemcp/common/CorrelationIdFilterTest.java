package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Guards against the exact regression found live during an audit pass (`plan.md` §9, 2026-07-17):
 * the filter populated MDC and echoed the header correctly, but Spring Boot's default console
 * pattern never interpolates MDC keys unless told to - so the correlation ID never actually
 * appeared in a rendered log line, silently defeating the entire point of this filter (tracing a
 * request's log lines, including across `@Async` boundaries). A test asserting only {@code
 * MDC.get(...)} inside the filter (as {@link ContextPropagatingTaskDecoratorTest} does for the
 * async-propagation half of this feature) cannot catch this class of bug - it has to inspect what a
 * real Logback appender actually renders.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void echoesAClientSuppliedCorrelationIdBackInTheResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/x");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "client-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("client-supplied-id");
    }

    @Test
    void generatesAUuidWhenNoCorrelationIdHeaderIsSupplied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(java.util.UUID.fromString(generated)).isNotNull();
    }

    @Test
    void generatesAUuidWhenTheCorrelationIdHeaderIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/x");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isNotEqualTo("   ");
    }

    @Test
    void clearsMdcAfterTheChainCompletesEvenWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/x");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "will-not-leak");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                        throw new ServletException("downstream failure");
                    });
        } catch (ServletException | IOException expected) {
            // expected - asserting the finally-block cleanup below, not the propagation itself
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    /**
     * The regression test: attaches a real Logback {@link ListAppender} to the root logger (the
     * same appender machinery {@code logging.pattern.level} renders through) and asserts the
     * correlation ID actually shows up in a log line emitted while the filter's MDC entry is active
     * - not just that {@code MDC.get(...)} returns the right value in isolation.
     */
    @Test
    void aLogLineEmittedDuringTheRequestActuallyContainsTheCorrelationId() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        org.slf4j.Logger probeLogger = LoggerFactory.getLogger("test.correlation.probe");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/x");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "trace-me-explicitly");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(
                    request,
                    response,
                    (req, res) -> probeLogger.warn("probe log line during request"));

            boolean anyEventCarriedTheCorrelationId =
                    appender.list.stream()
                            .anyMatch(
                                    event ->
                                            "trace-me-explicitly"
                                                    .equals(
                                                            event.getMDCPropertyMap()
                                                                    .get(
                                                                            CorrelationIdFilter
                                                                                    .MDC_KEY)));
            assertThat(anyEventCarriedTheCorrelationId)
                    .as(
                            "MDC must actually be attached to the logging event during the"
                                    + " request, not just settable/gettable in isolation - this is"
                                    + " what a rendered log line's pattern reads from")
                    .isTrue();
        } finally {
            rootLogger.detachAppender(appender);
        }
    }
}
