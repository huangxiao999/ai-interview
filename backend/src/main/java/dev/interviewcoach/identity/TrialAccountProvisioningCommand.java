package dev.interviewcoach.identity;

import java.io.Console;
import java.util.List;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local & provision")
final class TrialAccountProvisioningCommand implements ApplicationRunner {

    private final TrialAccountProvisioner provisioner;

    TrialAccountProvisioningCommand(TrialAccountProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Console console = System.console();
        AccountMaintenancePreflight.requireAuthorized(arguments.getSourceArgs(), console,
                Path.of("").toAbsolutePath().normalize(), AccountMaintenancePreflight.Operation.PROVISION);
        List<String> loginNames = arguments.getOptionValues("app.provision.login-name");
        if (loginNames == null || loginNames.size() != 1) {
            throw new IllegalArgumentException("Provide exactly one --app.provision.login-name value.");
        }

        ProvisionedTrialAccount account = provisioner.provision(loginNames.getFirst());
        console.printf("Trial account created: %s%n", account.accountId());
        console.printf("Initial password (shown once; deliver through the approved offline channel): %s%n",
                account.initialPassword());
    }
}
