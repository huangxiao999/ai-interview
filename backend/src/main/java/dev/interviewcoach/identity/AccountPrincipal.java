package dev.interviewcoach.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

record AccountPrincipal(UUID accountId, String loginName, long credentialVersion,
        Set<AccountAuthority> authorities) implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    AccountPrincipal {
        authorities = Set.copyOf(authorities);
    }

    @Override
    public String getName() {
        return accountId.toString();
    }
}
