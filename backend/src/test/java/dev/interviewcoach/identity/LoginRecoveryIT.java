package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRecoveryIT {

    private static ConfigurableApplicationContext context;
    private static Process vite;
    private static HttpServer control;
    private static JdbcTemplate jdbc;
    private static URI frontend;
    private static String loginA;
    private static String passwordA;
    private static String loginB;
    private static String passwordB;
    private static UUID accountA;
    private static UUID accountB;
    private static final AtomicBoolean TABLE_RENAMED = new AtomicBoolean();
    private static String controlToken;

    @BeforeAll
    static void startWithIsolatedDatabase() throws Exception {
        String database = System.getenv("DT06_TEST_DATABASE");
        if (database == null || !database.matches("dt06_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("Run scripts/Verify-LoginRecovery.ps1 with an isolated database.");
        }
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String dbPassword = System.getenv("DB_PASSWORD");
        var administrative = new JdbcTemplate(new DriverManagerDataSource(url, username, dbPassword));
        assertThat(administrative.queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        assertThat(administrative.queryForObject("SHOW server_version_num", Integer.class))
                .isBetween(160000, 169999);
        assertThat(administrative.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'",
                Integer.class)).isZero();
        Flyway.configure().dataSource(url, username, dbPassword).defaultSchema("app").schemas("app")
                .createSchemas(true).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=app", username, dbPassword));
        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();
        loginA = "dt06.a." + accountA;
        loginB = "dt06.b." + accountB;
        passwordA = "synthetic-" + UUID.randomUUID();
        passwordB = "synthetic-" + UUID.randomUUID();
        var accounts = new JdbcAccountRepository(jdbc);
        accounts.insert(accountA, loginA, new PasswordHashes().encode(passwordA),
                Set.of(AccountAuthority.TRIAL_USER));
        accounts.insert(accountB, loginB, new PasswordHashes().encode(passwordB),
                Set.of(AccountAuthority.TRIAL_USER));
        context = new SpringApplicationBuilder(InterviewCoachApplication.class)
                .profiles("local").run("--server.port=0");
        String backend = "http://127.0.0.1:"
                + context.getEnvironment().getRequiredProperty("local.server.port");
        int vitePort;
        try (var socket = new ServerSocket(0)) {
            vitePort = socket.getLocalPort();
        }
        frontend = URI.create("http://127.0.0.1:" + vitePort);
        var viteCommand = new ProcessBuilder("node", "node_modules/vite/bin/vite.js", "--host",
                "127.0.0.1", "--port", Integer.toString(vitePort), "--strictPort");
        viteCommand.directory(Path.of("../frontend").toFile());
        viteCommand.environment().put("BACKEND_URL", backend);
        viteCommand.redirectErrorStream(true);
        viteCommand.redirectOutput(Path.of("../.cache/dt06-vite.log").toFile());
        vite = viteCommand.start();
        boolean ready = false;
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!vite.isAlive()) throw new IllegalStateException("Vite exited before browser verification.");
            try {
                var connection = (HttpURLConnection) frontend.resolve("/login").toURL()
                        .openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                int status = connection.getResponseCode();
                connection.disconnect();
                if (status == 200) {
                    ready = true;
                    break;
                }
            } catch (java.io.IOException ignored) {
                // The loopback server may not have bound its port yet.
            }
            Thread.sleep(100);
        }
        assertThat(ready).as("Vite did not become ready").isTrue();
        controlToken = UUID.randomUUID().toString();
        control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        control.createContext("/" + controlToken, exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String action = exchange.getRequestURI().getPath().substring(controlToken.length() + 2);
            int status = 204;
            try {
                switch (action) {
                    case "expire" -> {
                        if (jdbc.update("UPDATE spring_session SET last_access_time=0, expiry_time=0") == 0) {
                            status = 409;
                        }
                    }
                    case "unavailable" -> {
                        jdbc.execute("ALTER TABLE identity_account RENAME TO identity_account_unavailable");
                        TABLE_RENAMED.set(true);
                    }
                    case "restore" -> restoreAccountTable();
                    default -> status = 404;
                }
            } catch (RuntimeException failure) {
                status = 500;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        control.setExecutor(command -> Thread.ofVirtual().start(command));
        control.start();
    }

    private static void restoreAccountTable() {
        if (TABLE_RENAMED.get()) {
            jdbc.execute("ALTER TABLE identity_account_unavailable RENAME TO identity_account");
            TABLE_RENAMED.set(false);
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (control != null) control.stop(0);
        if (jdbc != null) restoreAccountTable();
        if (vite != null) {
            vite.destroy();
            if (!vite.waitFor(5, TimeUnit.SECONDS)) vite.destroyForcibly();
        }
        if (context != null) context.close();
    }

    @Test
    void browserRecoveryAgainstPostgresql() throws Exception {
        var command = new ProcessBuilder("node", Path.of("../scripts/Verify-RecoveryBrowser.mjs")
                .toAbsolutePath().normalize().toString());
        command.environment().put("DT06_FRONTEND_BASE", frontend.toString());
        command.environment().put("DT06_USER_A", loginA);
        command.environment().put("DT06_PASSWORD_A", passwordA);
        command.environment().put("DT06_ACCOUNT_A", accountA.toString());
        command.environment().put("DT06_USER_B", loginB);
        command.environment().put("DT06_PASSWORD_B", passwordB);
        command.environment().put("DT06_ACCOUNT_B", accountB.toString());
        command.environment().put("DT06_CONTROL_URL", "http://127.0.0.1:" + control.getAddress().getPort()
                + "/" + controlToken + "/");
        command.redirectErrorStream(true);
        Process browser = command.start();
        boolean finished = browser.waitFor(120, TimeUnit.SECONDS);
        if (!finished) browser.destroyForcibly();
        String output = new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("Chrome recovery verification timed out").isTrue();
        assertThat(browser.exitValue()).as(output).isZero();
        assertThat(output).contains("DT-06 real Chrome recovery checks passed.")
                .doesNotContain(passwordA).doesNotContain(passwordB);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account WHERE id=?",
                Integer.class, accountA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account WHERE id=?",
                Integer.class, accountB)).isEqualTo(1);
    }
}
