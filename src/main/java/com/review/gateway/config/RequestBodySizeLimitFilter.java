package com.review.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;

/**
 * SR-11: rejects an oversized request body with {@code 413} before Spring MVC/Jackson ever reads or
 * buffers it — fail-fast at the edge (requirement), sized from {@code max-diff-tokens × chars-per-token
 * + margin} for {@code POST /reviews} (§9 {@code gateway.diff.max-request-body-bytes}) and from
 * {@code max-raw-response-length + margin} for {@code POST /jobs/{id}/result}
 * (§9 {@code gateway.publish.max-request-body-bytes}).
 *
 * <p>Checked against the declared {@code Content-Length} header, which covers the overwhelmingly
 * common case (every realistic CI/Worker HTTP client sends a known-length JSON body). A client that
 * both omits {@code Content-Length} and streams an unbounded chunked body would bypass this specific
 * check; that residual gap is accepted at this project's scale/threat model (internal CI + Worker
 * clients, not an open public API) rather than adding a byte-counting stream wrapper.
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestBodySizeLimitFilter.class);

    private static final PathPattern REVIEWS_PATTERN = new PathPatternParser().parse("/reviews");
    private static final PathPattern RESULT_PATTERN = new PathPatternParser().parse("/jobs/{id}/result");

    private final GatewayProperties properties;

    public RequestBodySizeLimitFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Long limit = limitFor(request);
        if (limit != null) {
            long contentLength = request.getContentLengthLong();
            if (contentLength > limit) {
                log.info("Rejecting oversized request body: {} {} contentLength={} limit={}",
                        request.getMethod(), request.getRequestURI(), contentLength, limit);
                response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body exceeds the configured size limit\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Long limitFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if (REVIEWS_PATTERN.matches(org.springframework.http.server.PathContainer.parsePath(path))) {
            return properties.getDiff().getMaxRequestBodyBytes();
        }
        if (RESULT_PATTERN.matches(org.springframework.http.server.PathContainer.parsePath(path))) {
            return properties.getPublish().getMaxRequestBodyBytes();
        }
        return null;
    }
}
