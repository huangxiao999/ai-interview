package dev.interviewcoach.identity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Best-effort physical cleanup; credential_version is the immediate revocation authority. */
final class OldCredentialSessionCleaner implements AutoCloseable {

    private final JdbcIndexedSessionRepository repository;

    OldCredentialSessionCleaner(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        var repository = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactionManager));
        repository.afterPropertiesSet();
        this.repository = repository;
    }

    int clean(UUID accountId, long currentVersion) {
        FindByIndexNameSessionRepository<? extends Session> sessions = repository;
        int removed = 0;
        for (Session session : sessions.findByPrincipalName(accountId.toString()).values()) {
            SecurityContext context = session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context == null || context.getAuthentication() == null
                    || !(context.getAuthentication().getPrincipal() instanceof AccountPrincipal principal)
                    || !accountId.equals(principal.accountId())
                    || principal.credentialVersion() >= currentVersion) {
                continue;
            }
            // New sessions have new IDs and a new credential version; never delete by principal alone.
            sessions.deleteById(session.getId());
            removed++;
        }
        return removed;
    }

    @Override
    public void close() {
        repository.destroy();
    }
}
