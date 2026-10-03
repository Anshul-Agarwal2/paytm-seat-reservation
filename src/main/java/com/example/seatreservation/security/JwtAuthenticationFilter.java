package com.example.seatreservation.security;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtDecoder jwtDecoder,
            AuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtDecoder = jwtDecoder;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication;
        try {
            Jwt jwt = jwtDecoder.decode(authorization.substring(BEARER_PREFIX.length()).trim());
            long userId = userIdFrom(jwt);
            authentication = new UsernamePasswordAuthenticationToken(
                    new AuthenticatedUser.Principal(userId),
                    jwt,
                    authoritiesFrom(jwt));
        } catch (JwtException | AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    exception instanceof AuthenticationException authenticationException
                            ? authenticationException
                            : new BadCredentialsException("Invalid bearer token", exception));
            return;
        }
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private static long userIdFrom(Jwt jwt) {
        Object claim = jwt.getClaims().get("user_id");
        if (!(claim instanceof Number number)) {
            throw new BadCredentialsException("JWT must contain a numeric user_id claim");
        }
        try {
            long userId = new BigDecimal(number.toString()).longValueExact();
            if (userId <= 0) {
                throw new BadCredentialsException("JWT user_id must be positive");
            }
            return userId;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new BadCredentialsException("JWT user_id must be an integer", exception);
        }
    }

    private static List<SimpleGrantedAuthority> authoritiesFrom(Jwt jwt) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        addAuthority(authorities, jwt.getClaims().get("role"));
        Object roles = jwt.getClaims().get("roles");
        if (roles instanceof Collection<?> roleCollection) {
            roleCollection.forEach(role -> addAuthority(authorities, role));
        }
        return authorities;
    }

    private static void addAuthority(List<SimpleGrantedAuthority> authorities, Object role) {
        if (role instanceof String roleName && !roleName.isBlank()) {
            String normalizedRole = roleName.toUpperCase(Locale.ROOT);
            if (!normalizedRole.startsWith("ROLE_")) {
                normalizedRole = "ROLE_" + normalizedRole;
            }
            authorities.add(new SimpleGrantedAuthority(normalizedRole));
        }
    }
}
