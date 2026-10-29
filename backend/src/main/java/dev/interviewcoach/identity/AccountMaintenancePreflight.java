package dev.interviewcoach.identity;

import com.sun.security.auth.module.NTSystem;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Profiles;

/** Rejects unauthorized account maintenance before Spring creates a datasource or runs Flyway. */
public final class AccountMaintenancePreflight
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Pattern LOGIN_NAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        boolean provision = event.getEnvironment().acceptsProfiles(Profiles.of("provision"));
        boolean reset = event.getEnvironment().acceptsProfiles(Profiles.of("reset"));
        if (!provision && !reset) {
            return;
        }
        if (provision && reset) {
            throw new IllegalStateException("Choose exactly one account maintenance operation.");
        }
        if (!event.getEnvironment().acceptsProfiles(Profiles.of("local"))
                || !"none".equalsIgnoreCase(event.getEnvironment()
                        .getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("Account maintenance requires local non-web mode.");
        }
        requireAuthorized(event.getArgs(), System.console(), Path.of("").toAbsolutePath().normalize(),
                provision ? Operation.PROVISION : Operation.RESET);
    }

    enum Operation { PROVISION, RESET }

    static void requireAuthorized(String[] args, Console console, Path workingDirectory,
            Operation operation) {
        if (console == null) {
            throw new IllegalStateException("Account maintenance requires an interactive console.");
        }
        var options = new DefaultApplicationArguments(args);
        if (operation == Operation.PROVISION) {
            List<String> names = options.getOptionValues("app.provision.login-name");
            if (names == null || names.size() != 1 || !LOGIN_NAME.matcher(names.getFirst()).matches()
                    || options.containsOption("app.reset.account-id")) {
                throw new IllegalArgumentException("Provide one valid --app.provision.login-name value.");
            }
        } else {
            List<String> ids = options.getOptionValues("app.reset.account-id");
            if (ids == null || ids.size() != 1 || options.containsOption("app.provision.login-name")) {
                throw new IllegalArgumentException("Provide one --app.reset.account-id UUID value.");
            }
            try {
                UUID.fromString(ids.getFirst());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Provide a valid --app.reset.account-id UUID value.");
            }
        }
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || workingDirectory.getFileName() == null
                || !"backend".equals(workingDirectory.getFileName().toString())) {
            throw new IllegalStateException("Account maintenance requires the protected Windows project checkout.");
        }

        Path root = workingDirectory.getParent();
        var currentUser = new NTSystem();
        String operator = currentUser.getDomain() + "\\" + currentUser.getName();
        Path commandScript = root.resolve("scripts").resolve(operation == Operation.PROVISION
                ? "Provision-TrialAccount.ps1" : "Reset-TrialAccountPassword.ps1");
        for (Path path : List.of(root, root.resolve(".env"), workingDirectory,
                workingDirectory.resolve("target"),
                workingDirectory.resolve("target/interview-coach-backend-0.0.1-SNAPSHOT.jar"),
                root.resolve("scripts"), commandScript)) {
            requirePrivateOwnerAndAcl(path, operator);
        }
    }

    private static void requirePrivateOwnerAndAcl(Path path, String operator) {
        try {
            if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName()
                            .equalsIgnoreCase(operator)) {
                throw new IllegalStateException("Maintenance files must be owned by the current operator.");
            }
            var view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IllegalStateException("Windows ACL verification is unavailable.");
            }
            for (var entry : view.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW) {
                    continue;
                }
                String principal = entry.principal().getName().toLowerCase(Locale.ROOT);
                if (!principal.equals(operator.toLowerCase(Locale.ROOT))
                        && !principal.equals("builtin\\administrators")
                        && !principal.equals("nt authority\\system")
                        && !principal.equals("s-1-5-32-544")
                        && !principal.equals("s-1-5-18")) {
                    throw new IllegalStateException("Maintenance files grant access beyond the operator.");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot verify maintenance file permissions.", exception);
        }
    }
}
