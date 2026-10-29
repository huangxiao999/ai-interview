package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class LoginFrontendIT {

    private static ConfigurableApplicationContext context;
    private static Process vite;
    private static JdbcTemplate jdbc;
    private static URI frontend;
    private static String login;
    private static String password;
    private static UUID accountId;

    @BeforeAll
    static void startWithIsolatedDatabase() throws Exception {
        String database = System.getenv("DT05_TEST_DATABASE");
        if (database == null || !database.matches("dt05_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("Run scripts/Verify-LoginFrontend.ps1 with an isolated database.");
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
        accountId = UUID.randomUUID();
        login = "dt05." + accountId;
        password = "synthetic-" + UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(accountId, login, new PasswordHashes().encode(password),
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
        viteCommand.redirectOutput(Path.of("../.cache/dt05-vite.log").toFile());
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
    }

    @AfterAll
    static void stop() throws Exception {
        if (vite != null) {
            vite.destroy();
            if (!vite.waitFor(5, TimeUnit.SECONDS)) vite.destroyForcibly();
        }
        if (context != null) context.close();
    }

    @Test
    void actualVuePageLogsInAndOutAgainstPostgresql() throws Exception {
        var command = new ProcessBuilder("node", Path.of("../scripts/Verify-LoginBrowser.mjs")
                .toAbsolutePath().normalize().toString());
        command.environment().put("DT05_FRONTEND_BASE", frontend.toString());
        command.environment().put("DT05_BROWSER_USER", login);
        command.environment().put("DT05_BROWSER_PASSWORD", password);
        command.environment().put("DT05_BROWSER_ACCOUNT_ID", accountId.toString());
        command.redirectErrorStream(true);
        Process browser = command.start();
        boolean finished = browser.waitFor(90, TimeUnit.SECONDS);
        if (!finished) browser.destroyForcibly();
        String output = new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("Chrome frontend verification timed out").isTrue();
        assertThat(browser.exitValue()).as(output).isZero();
        assertThat(output).contains("DT-05 real Chrome login frontend checks passed.")
                .doesNotContain(password);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account WHERE id=?",
                Integer.class, accountId)).isEqualTo(1);
    }
}
