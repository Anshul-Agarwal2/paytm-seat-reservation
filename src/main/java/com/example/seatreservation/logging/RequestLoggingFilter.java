package com.example.seatreservation.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String REQUEST_ID_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".requestId";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = validRequestId(request.getHeader(REQUEST_ID_HEADER))
                ? request.getHeader(REQUEST_ID_HEADER)
                : UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("request_id", requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.put("http_method", request.getMethod());
            MDC.put("endpoint", request.getRequestURI());
            MDC.put("response_status", Integer.toString(response.getStatus()));
            MDC.put("duration_ms", Long.toString((System.nanoTime() - startedAt) / 1_000_000));
            try {
                LOGGER.info("http_request_completed");
            } finally {
                MDC.remove("http_method");
                MDC.remove("endpoint");
                MDC.remove("response_status");
                MDC.remove("duration_ms");
                MDC.remove("request_id");
            }
        }
    }

    private static boolean validRequestId(String requestId) {
        return requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches();
    }
}
