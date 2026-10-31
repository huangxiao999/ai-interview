package dev.interviewcoach.material;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 与页面套件同一个新隔离库，使用独立 schema；调用真实生产仓储，不替代 PG 语义。 */
class CleanupRepositoryIT {
    static JdbcTemplate jdbc;
    static UploadRepository repository;
    static UUID owner;
    @BeforeAll
    static void prepare() {
        String name = System.getenv("P2_DT01_TEST_DATABASE");
        if (name == null || !name.matches("p2dt01_verify_[a-f0-9]{16}")) throw new IllegalStateException("Isolated database required");
        String url = System.getenv("DB_URL");
        var ds = new DriverManagerDataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        assertThat(new JdbcTemplate(ds).queryForObject("SELECT current_database()", String.class)).isEqualTo(name);
        Flyway.configure().dataSource(ds).defaultSchema("cleanup_case").schemas("cleanup_case").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=cleanup_case", System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD")));
        repository = new UploadRepository(jdbc);
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_account(id,login_name,password_hash) VALUES(?,?,?)", owner, "synthetic-cleanup", "unused-fixture-no-login");
    }
    UUID item(String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material_upload(id,owner_account_id,operation_id,original_name,file_type,byte_size,sha256,state,deadline_at) "
                + "VALUES(?,?,?,'synthetic.pdf','PDF',10,?,?,clock_timestamp()-interval '1 minute')",
                id, owner, UUID.randomUUID(), "a".repeat(64), state);
        return id;
    }
    @Test
    void failedBatchRotatesToUnattemptedTargets() {
        var ids = new java.util.HashSet<UUID>();
        for (int n = 0; n < 101; n++) ids.add(item("DELETE_PENDING"));
        var first = repository.cleanup();
        assertThat(first).hasSize(100);
        for (var u : first) { repository.cleanupAttempt(u.id()); repository.cleanupFailed(u.id()); }
        UUID remaining = ids.stream().filter(id -> first.stream().noneMatch(u -> u.id().equals(id))).findFirst().orElseThrow();
        assertThat(repository.cleanup().getFirst().id()).isEqualTo(remaining);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_upload WHERE cleanup_error_code='FILE_IO'", Integer.class)).isEqualTo(100);
        for (UUID id : ids) repository.cleaned(id);
    }
    @Test
    void lateResultCannotRestoreDeletedOrExpiredUpload() {
        UUID deleted = item("PARSING");
        var old = repository.find(deleted).orElseThrow();
        repository.cancel(deleted);
        repository.result(old, new DocumentParser.Result("READY", "late synthetic body"));
        assertThat(repository.find(deleted).orElseThrow().state()).isEqualTo("DELETE_PENDING");
        assertThat(repository.find(deleted).orElseThrow().body()).isNull();
        repository.cleaned(deleted);
        repository.result(old, new DocumentParser.Result("READY", "late synthetic body"));
        assertThat(repository.find(deleted).orElseThrow().state()).isEqualTo("CLEANED");
        UUID expired = item("PARSING");
        repository.result(repository.find(expired).orElseThrow(), new DocumentParser.Result("READY", "late"));
        assertThat(repository.find(expired).orElseThrow().state()).isEqualTo("FAILED");
        assertThat(repository.find(expired).orElseThrow().body()).isNull();
        repository.cleaned(expired);
    }
    @Test
    void deadlineBreachIsObservedWithoutPretendingCleanupSucceeded() {
        UUID root = UUID.randomUUID();
        jdbc.update("INSERT INTO material_resume(id,owner_account_id,create_operation_id) VALUES(?,?,?)", root, owner, UUID.randomUUID());
        UUID request = UUID.randomUUID();
        jdbc.update("WITH stamp AS (SELECT clock_timestamp()-interval '25 hours' AS t) "
                + "INSERT INTO material_delete_request(id,resume_id,requested_at,cleanup_deadline_at,state) "
                + "SELECT ?,?,t,t+interval '24 hours','PENDING' FROM stamp", request, root);
        assertThat(repository.overdueDeletions()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM material_delete_request WHERE id=?", String.class, request)).isEqualTo("PENDING");
    }
}
