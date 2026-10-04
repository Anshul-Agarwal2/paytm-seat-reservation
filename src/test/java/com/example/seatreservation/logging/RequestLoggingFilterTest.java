package com.example.seatreservation.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void echoesValidRequestIdAndMakesItAvailableDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health/live");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "client-request_123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(MDC.get("request_id")).isEqualTo("client-request_123");
            ((MockHttpServletResponse) servletResponse).setStatus(204);
        };
        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER))
                .isEqualTo("client-request_123");
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(MDC.get("request_id")).isNull();
    }

    @Test
    void generatesRequestIdWhenHeaderIsMissingOrUnsafe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shows");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "bad request\nid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        String requestId = response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank().matches("[A-Za-z0-9._-]{1,128}");
        assertThat(requestId).isNotEqualTo("bad request\nid");
    }
}
