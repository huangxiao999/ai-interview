package dev.interviewcoach.identity;

import java.io.Console;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component
@Profile("local & reset")
final class TrialAccountPasswordResetCommand implements ApplicationRunner {

    private final TrialAccountPasswordResetter resetter;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    TrialAccountPasswordResetCommand(TrialAccountPasswordResetter resetter, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.resetter = resetter;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Console console = System.console();
        AccountMaintenancePreflight.requireAuthorized(arguments.getSourceArgs(), console,
                Path.of("").toAbsolutePath().normalize(), AccountMaintenancePreflight.Operation.RESET);
        List<String> values = arguments.getOptionValues("app.reset.account-id");
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Provide exactly one --app.reset.account-id value.");
        }
        ResetTrialAccount reset = resetter.reset(UUID.fromString(values.getFirst()));
        // Deliver the committed credential before optional cleanup, which can be slow or unavailable.
        console.printf("Trial account reset: %s%n", reset.accountId());
        console.printf("New password (shown once; deliver through the approved offline channel): %s%n",
                reset.newPassword());
        int removed = -1;
        try (var cleaner = new OldCredentialSessionCleaner(jdbc, transactionManager)) {
            removed = cleaner.clean(reset.accountId(), reset.credentialVersion());
        } catch (RuntimeException cleanupFailure) {
            // The password and version already committed. Never hide the new credential after cleanup fails.
            console.printf("Old session row cleanup could not be confirmed; logical revocation remains active.%n");
        }
        console.printf("All previous login sessions are invalid. Old session rows removed now: %s.%n",
                removed < 0 ? "unknown" : Integer.toString(removed));
    }
}
