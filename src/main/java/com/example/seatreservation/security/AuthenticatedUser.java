package com.example.seatreservation.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUser {

    public long userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Principal principal)) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user is available");
        }
        return principal.userId();
    }

    public record Principal(long userId) {
    }
}
