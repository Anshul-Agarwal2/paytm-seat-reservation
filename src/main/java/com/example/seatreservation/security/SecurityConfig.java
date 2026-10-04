package com.example.seatreservation.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class SecurityConfig {

    @Bean
    public SecretKey jwtSecretKey(@Value("${app.security.jwt.secret}") String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) ->
                response.sendError(org.springframework.http.HttpStatus.FORBIDDEN.value());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
                        .requestMatchers("/reservations/**", "/shows/*/reserve").authenticated()
                        .requestMatchers(
                                "/health/**", "/actuator/health/**", "/actuator/prometheus", "/dev/tokens")
                        .permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtDecoder, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
