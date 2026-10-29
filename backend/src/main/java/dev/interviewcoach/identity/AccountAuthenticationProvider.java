package dev.interviewcoach.identity;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("local")
final class AccountAuthenticationProvider implements AuthenticationProvider {

    // Valid encoded value for the unknown-account path; the raw password is deliberately not a credential.
    private final String unknownAccountHash;
    private final JdbcAccountRepository accounts;
    private final PasswordHashes hashes;
    private final TransactionTemplate transaction;

    AccountAuthenticationProvider(JdbcAccountRepository accounts, PasswordHashes hashes,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.hashes = hashes;
        this.transaction = new TransactionTemplate(transactionManager);
        this.unknownAccountHash = hashes.encode(UUID.randomUUID().toString());
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String loginName = authentication.getName();
        String password = authentication.getCredentials().toString();
        try {
            var first = accounts.findByLoginName(loginName);
            boolean matches = hashes.matches(password,
                    first.map(StoredAccount::passwordHash).orElse(unknownAccountHash));
            if (!matches || first.isEmpty()) {
                throw new BadCredentialsException("Invalid credentials");
            }
            StoredAccount observed = first.orElseThrow();
            return transaction.execute(status -> authenticateUnderLock(observed));
        } catch (DataAccessException | IllegalArgumentException ex) {
            throw new AuthenticationServiceException("Account state unavailable", ex);
        }
    }

    private Authentication authenticateUnderLock(StoredAccount observed) {
        // The expensive hash check stays outside the lock; the final account facts are checked under it.
        accounts.lockById(observed.id());
        StoredAccount current = accounts.findById(observed.id())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (current.credentialVersion() != observed.credentialVersion()
                || !current.passwordHash().equals(observed.passwordHash())
                || !current.authorities().equals(observed.authorities())
                || !current.loginName().equals(observed.loginName())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        var principal = new AccountPrincipal(current.id(), current.loginName(),
                current.credentialVersion(), current.authorities());
        var authorities = current.authorities().stream()
                .map(authority -> new SimpleGrantedAuthority(authority.name())).toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
