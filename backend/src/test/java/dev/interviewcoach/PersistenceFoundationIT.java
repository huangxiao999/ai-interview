package dev.interviewcoach;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceFoundationIT {

    private static JdbcTemplate administrativeJdbc;

    @BeforeAll
    static void requireFreshIsolatedPostgresqlDatabase() {
        String expectedDatabase = System.getenv("DT01_TEST_DATABASE");
        if (expectedDatabase == null || !expectedDatabase.matches("dt01_verify_[a-f0-9]{16}")) {
            throw new IllegalStateException("Run scripts/Verify-Persistence.ps1; an isolated DT-01 database is required.");
        }
        var source = new DriverManagerDataSource(System.getenv("DB_URL"),
                System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        administrativeJdbc = new JdbcTemplate(source);
        assertThat(administrativeJdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(expectedDatabase);
        assertThat(administrativeJdbc.queryForObject("SHOW server_version_num", Integer.class))
                .isBetween(160000, 169999);
        assertThat(administrativeJdbc.queryForObject("""
                SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                """, Integer.class)).isZero();
    }

    @Test
    void migratesEmptySchemaAndRestartsWithoutChangingHistory() throws Exception {
        List<?> history;
        try (var context = start("app", "classpath:db/migration")) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("app");
            assertThat(jdbc.queryForList("""
                    SELECT tablename FROM pg_tables WHERE schemaname='app' ORDER BY tablename
                    """, String.class)).containsExactly("flyway_schema_history", "identity_account",
                    "identity_account_authority", "knowledge_document", "knowledge_review", "knowledge_version",
                    "material_cleanup_item", "material_delete_request",
                    "material_jd_version", "material_resume", "material_resume_version", "material_upload",
                    "spring_session", "spring_session_attributes");
            assertThat(jdbc.queryForList("""
                    SELECT version FROM flyway_schema_history WHERE type='SQL' AND success ORDER BY installed_rank
                    """, String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
            assertThat(context.getBeanFactory().getBeanDefinition("sessionRepository").getDependsOn())
                    .contains("flywayInitializer");
            assertThat(jdbc.queryForObject("""
                    SELECT CAST('[1,0,0]' AS public.vector) OPERATOR(public.<->) CAST('[0,1,0]' AS public.vector)
                    """, Double.class)).isCloseTo(Math.sqrt(2), org.assertj.core.data.Offset.offset(1e-12));
            assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'",
                    String.class)).isNotBlank();
            history = jdbc.queryForList("SELECT installed_rank, version, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank");
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
        }
        try (var context = start("app", "classpath:db/migration")) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForList("SELECT installed_rank, version, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank"))
                    .isEqualTo(history);
            assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void enforcesAccountAndAuthorityConstraints() {
        try (var context = start("constraints_case", "classpath:db/migration")) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            UUID account = UUID.randomUUID();
            // Isolated structural fixture only; this marker is not an encoded password or a usable account.
            String insert = "INSERT INTO identity_account(id, login_name, password_hash) VALUES (?, ?, ?)";
            jdbc.update(insert, account, "dt01-fixture", "dt01-noncredential-marker");
            assertThat(jdbc.queryForObject("SELECT credential_version FROM identity_account WHERE id=?",
                    Long.class, account)).isEqualTo(1L);
            assertSqlState("23505", () -> jdbc.update(insert, UUID.randomUUID(), "dt01-fixture", "marker"));
            assertSqlState("23505", () -> jdbc.update(insert, account, "different-fixture", "marker"));
            assertSqlState("23502", () -> jdbc.update(insert, UUID.randomUUID(), "null-hash", null));
            assertSqlState("23514", () -> jdbc.update(insert, UUID.randomUUID(), " \t\r\n", "marker"));
            assertSqlState("23514", () -> jdbc.update(insert, UUID.randomUUID(), "empty-hash", ""));
            assertSqlState("23514", () -> jdbc.update("UPDATE identity_account SET credential_version=0 WHERE id=?", account));
            assertSqlState("23502", () -> jdbc.update("UPDATE identity_account SET created_at=NULL WHERE id=?", account));
            String grant = "INSERT INTO identity_account_authority(account_id, authority) VALUES (?, ?)";
            jdbc.update(grant, account, "TRIAL_USER");
            assertSqlState("23505", () -> jdbc.update(grant, account, "TRIAL_USER"));
            assertSqlState("23503", () -> jdbc.update(grant, UUID.randomUUID(), "TRIAL_USER"));
            assertSqlState("23514", () -> jdbc.update(grant, account, " \t"));
        }
    }

    @Test
    void realSessionRepositoryPersistsIndexesUpdatesRotatesAndDeletes() {
        try (var context = start("session_case", "classpath:db/migration")) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            verifySessionOperations(context.getBean(JdbcIndexedSessionRepository.class), jdbc);
        }
    }

    // Capture the repository's package-private concrete session type through its public Session bound.
    private static <S extends Session> void verifySessionOperations(
            FindByIndexNameSessionRepository<S> repository, JdbcTemplate jdbc) {
        var anonymous = repository.createSession();
        assertThat(anonymous.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        anonymous.setAttribute("dt01-fixture", "before");
        repository.save(anonymous);
        assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id=?",
                String.class, anonymous.getId())).isNull();
        assertSqlState("23505", () -> jdbc.update("""
                INSERT INTO spring_session
                SELECT ?, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name
                FROM spring_session WHERE session_id=?
                """, UUID.randomUUID().toString(), anonymous.getId()));
        assertSqlState("23505", () -> jdbc.update("""
                INSERT INTO spring_session
                SELECT primary_id, ?, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name
                FROM spring_session WHERE session_id=?
                """, UUID.randomUUID().toString(), anonymous.getId()));
        var loaded = repository.findById(anonymous.getId());
        assertThat(loaded).isNotNull();
        assertThat((String) loaded.getAttribute("dt01-fixture")).isEqualTo("before");
        loaded.setAttribute("dt01-fixture", "after");
        repository.save(loaded);
        assertThat((String) repository.findById(anonymous.getId()).getAttribute("dt01-fixture"))
                .isEqualTo("after");

        var indexed = repository.createSession();
        String principal = UUID.randomUUID().toString();
        indexed.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal);
        indexed.setAttribute("dt01-fixture", "synthetic-principal-not-authentication");
        repository.save(indexed);
        assertThat(repository.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal)).containsKey(indexed.getId());
        String previousId = indexed.getId();
        indexed.changeSessionId();
        repository.save(indexed);
        assertThat(repository.findById(previousId)).isNull();
        assertThat(repository.findById(indexed.getId())).isNotNull();
        String primaryId = jdbc.queryForObject("SELECT primary_id FROM spring_session WHERE session_id=?",
                String.class, indexed.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id=?",
                Integer.class, primaryId)).isPositive();
        assertSqlState("23505", () -> jdbc.update("""
                INSERT INTO spring_session_attributes(session_primary_id, attribute_name, attribute_bytes)
                SELECT session_primary_id, attribute_name, attribute_bytes FROM spring_session_attributes LIMIT 1
                """));
        assertSqlState("23503", () -> jdbc.update("""
                INSERT INTO spring_session_attributes VALUES (?, ?, ?)
                """, UUID.randomUUID().toString(), "orphan-fixture", new byte[]{1}));
        repository.deleteById(indexed.getId());
        assertThat(repository.findById(indexed.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id=?",
                Integer.class, primaryId)).isZero();
        repository.deleteById(anonymous.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes", Integer.class)).isZero();
    }

    @Test
    void failedMigrationBlocksStartupAndRollsBackBeforeRetry(@TempDir Path migrations) throws Exception {
        Files.writeString(migrations.resolve("V1__create_identity_tables.sql"), resource("db/migration/V1__create_identity_tables.sql"));
        Path faulty = migrations.resolve("V2__create_spring_session_tables.sql");
        String validSql = resource("db/migration/V2__create_spring_session_tables.sql");
        Files.writeString(faulty, validSql + "\nCREATE TABLE dt01_failure_probe(id INTEGER);\nSELECT 1 / 0;\n");
        String location = "filesystem:" + migrations.toAbsolutePath().toString().replace('\\', '/');
        assertThatThrownBy(() -> start("failure_case", location)).satisfies(error -> {
            assertThat(sqlState(error)).isEqualTo("22012");
        });
        assertThat(administrativeJdbc.queryForList("""
                SELECT version FROM failure_case.flyway_schema_history WHERE type='SQL' ORDER BY installed_rank
                """, String.class)).containsExactly("1");
        assertThat(administrativeJdbc.queryForObject("""
                SELECT count(*) FROM pg_tables WHERE schemaname='failure_case'
                AND tablename IN ('spring_session', 'spring_session_attributes', 'dt01_failure_probe')
                """, Integer.class)).isZero();
        // V2 did not commit. Only this temporary failure fixture is corrected; released migrations stay immutable.
        Files.writeString(faulty, validSql);
        // 当前应用需要完整迁移集；只修正隔离夹具的 V2，然后继续其后的正式迁移。
        for (String name : List.of("V3__create_resume_material_tables.sql", "V4__create_jd_versions.sql", "V5__create_private_uploads.sql", "V6__track_cleanup_attempts.sql", "V7__create_reviewed_knowledge.sql")) {
            Files.writeString(migrations.resolve(name), resource("db/migration/" + name));
        }
        try (var context = start("failure_case", location)) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE type='SQL' AND success ORDER BY installed_rank",
                    String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_account", Integer.class)).isZero();
        }
    }

    @Test
    void refusesUnknownNonemptySchemaWithoutAutomaticBaseline() {
        administrativeJdbc.execute("CREATE SCHEMA unknown_case");
        administrativeJdbc.execute("CREATE TABLE unknown_case.unowned_fixture(id INTEGER PRIMARY KEY)");
        administrativeJdbc.update("INSERT INTO unknown_case.unowned_fixture VALUES (?)", 1);
        assertThatThrownBy(() -> start("unknown_case", "classpath:db/migration"))
                .hasStackTraceContaining("non-empty schema");
        assertThat(administrativeJdbc.queryForObject("SELECT count(*) FROM unknown_case.unowned_fixture", Integer.class))
                .isEqualTo(1);
        assertThat(administrativeJdbc.queryForObject("""
                SELECT count(*) FROM pg_tables WHERE schemaname='unknown_case'
                AND tablename IN ('identity_account','spring_session','flyway_schema_history')
                """, Integer.class)).isZero();
    }

    @Test
    void sessionMigrationMatchesManagedOfficialPostgresqlSchema() throws Exception {
        String migration = resource("db/migration/V2__create_spring_session_tables.sql");
        String upstream = resource("org/springframework/session/jdbc/schema-postgresql.sql");
        assertThat(migration.replaceAll("\\s+", "")).isEqualTo(upstream.replaceAll("\\s+", ""));
    }

    private static ConfigurableApplicationContext start(String schema, String location) {
        return new SpringApplicationBuilder(InterviewCoachApplication.class)
                .web(WebApplicationType.SERVLET).registerShutdownHook(false).logStartupInfo(false)
                .run("--spring.profiles.active=local", "--server.port=0",
                        "--material.private-root=" + Path.of("../.cache", System.getenv("DT01_TEST_DATABASE"), "private").toAbsolutePath().normalize(),
                        "--spring.datasource.hikari.schema=" + schema,
                        "--spring.flyway.default-schema=" + schema,
                        "--spring.flyway.schemas=" + schema,
                        "--spring.flyway.locations=" + location);
    }

    private static String resource(String name) throws Exception {
        try (var stream = PersistenceFoundationIT.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing verification resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertSqlState(String expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo(expected));
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) return sqlException.getSQLState();
        }
        return null;
    }
}
