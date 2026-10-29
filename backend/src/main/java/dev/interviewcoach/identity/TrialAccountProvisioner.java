package dev.interviewcoach.identity;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!bootstrap")
final class TrialAccountProvisioner {

    private static final Pattern LOGIN_NAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private final JdbcAccountRepository accounts;
    private final PasswordHashes passwordHashes;
    private final TransactionTemplate transaction;

    TrialAccountProvisioner(JdbcAccountRepository accounts, PasswordHashes passwordHashes,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.passwordHashes = passwordHashes;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    ProvisionedTrialAccount provision(String loginName) {
        if (loginName == null || !LOGIN_NAME.matcher(loginName).matches()) {
            throw new IllegalArgumentException("Login name must be 3-64 ASCII letters, digits, '.', '_' or '-'.");
        }

        String initialPassword = RandomAccountPassword.generate();
        String passwordHash = passwordHashes.encode(initialPassword);
        UUID accountId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> accounts.insert(accountId, loginName, passwordHash,
                Set.of(AccountAuthority.TRIAL_USER)));
        return new ProvisionedTrialAccount(accountId, initialPassword);
    }
}
