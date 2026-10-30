package dev.interviewcoach.identity;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Provides the session identity verified by the existing account filter to business modules. */
@Component
public final class CurrentActor {

    public Actor require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new IllegalStateException("A verified account session is required.");
        }
        return new Actor(principal.accountId(), principal.authorities(), principal.credentialVersion());
    }
}
