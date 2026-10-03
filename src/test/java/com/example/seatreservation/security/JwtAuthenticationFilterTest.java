package com.example.seatreservation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class JwtAuthenticationFilterTest {

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
    private final AuthenticationEntryPoint authenticationEntryPoint = mock(AuthenticationEntryPoint.class);

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedUserComesFromJwtClaimNotRequestBody() throws Exception {
        Jwt jwt = new Jwt(
                "signed-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "HS256"),
                Map.of("user_id", 42L, "role", "USER"));
        when(jwtDecoder.decode("signed-token")).thenReturn(jwt);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer signed-token");
        request.setContent("{\"user_id\":999}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticatedUser authenticatedUser = new AuthenticatedUser();
        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(authenticatedUser.userId()).isEqualTo(42L);

        new JwtAuthenticationFilter(jwtDecoder, authenticationEntryPoint)
                .doFilter(request, response, chain);

        verify(jwtDecoder).decode("signed-token");
    }
}
