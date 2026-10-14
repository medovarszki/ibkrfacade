package hu.auxin.ibkrfacade.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the trace id of the caller into the logging context (MDC key trace_id), so every log line of a request can
 * be correlated with the calling agent. Sources, in order: W3C traceparent header, X-Trace-Id header, or a new
 * random id. The trace id is returned in the X-Trace-Id response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_PARENT_SPAN_ID = "parent_span_id";

    private static final Pattern TRACEPARENT = Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String INVALID_TRACE_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = null;
        String parentSpanId = null;
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        if (traceparent != null) {
            Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
            if (matcher.matches() && !"ff".equals(matcher.group(1)) && !INVALID_TRACE_ID.equals(matcher.group(2))
                    && !INVALID_SPAN_ID.equals(matcher.group(3))) {
                traceId = matcher.group(2);
                parentSpanId = matcher.group(3);
            }
        }
        if (traceId == null) {
            String header = request.getHeader(TRACE_ID_HEADER);
            if (header != null && TRACE_ID.matcher(header.trim()).matches()) {
                traceId = header.trim();
            }
        }
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(MDC_TRACE_ID, traceId);
        if (parentSpanId != null) {
            MDC.put(MDC_PARENT_SPAN_ID, parentSpanId);
        }
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_PARENT_SPAN_ID);
        }
    }
}
