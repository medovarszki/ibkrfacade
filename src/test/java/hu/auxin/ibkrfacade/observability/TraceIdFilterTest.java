package hu.auxin.ibkrfacade.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void usesTheW3cTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/positions");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader("X-Trace-Id", "ignored");
        String[] mdc = run(request);
        assertThat(mdc[0]).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(mdc[1]).isEqualTo("00f067aa0ba902b7");
        assertThat(mdc[2]).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    void fallsBackToXTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/positions");
        request.addHeader("traceparent", "00-00000000000000000000000000000000-00f067aa0ba902b7-01");
        request.addHeader("X-Trace-Id", "auxin-run-42");
        assertThat(run(request)[0]).isEqualTo("auxin-run-42");
    }

    @Test
    void generatesATraceIdForInvalidHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/positions");
        request.addHeader("X-Trace-Id", "bad id\nwith newline");
        String[] mdc = run(request);
        assertThat(mdc[0]).matches("[0-9a-f]{32}");
        assertThat(mdc[1]).isNull();
    }

    /**
     * @return trace id and parent span id seen in the MDC during the request, and the X-Trace-Id response header
     */
    private String[] run(MockHttpServletRequest request) throws Exception {
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> spanId = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            traceId.set(MDC.get(TraceIdFilter.MDC_TRACE_ID));
            spanId.set(MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID));
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return new String[]{traceId.get(), spanId.get(), response.getHeader("X-Trace-Id")};
    }
}
