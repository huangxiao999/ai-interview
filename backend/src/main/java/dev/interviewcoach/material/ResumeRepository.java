package dev.interviewcoach.material;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!bootstrap")
class ResumeRepository {

    private final JdbcTemplate jdbc;

    ResumeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Root> findByCreateOperation(UUID ownerId, UUID operationId) {
        return jdbc.query("SELECT id, owner_account_id, state FROM material_resume "
                + "WHERE owner_account_id = ? AND create_operation_id = ?",
                (rs, n) -> new Root(rs.getObject("id", UUID.class),
                        rs.getObject("owner_account_id", UUID.class), rs.getString("state")),
                ownerId, operationId).stream().findFirst();
    }

    Optional<Root> lockRoot(UUID id) {
        return jdbc.query("SELECT id, owner_account_id, state FROM material_resume WHERE id = ? FOR UPDATE",
                (rs, n) -> new Root(rs.getObject("id", UUID.class),
                        rs.getObject("owner_account_id", UUID.class), rs.getString("state")),
                id).stream().findFirst();
    }

    void insertRoot(UUID id, UUID ownerId, UUID operationId) {
        jdbc.update("INSERT INTO material_resume(id, owner_account_id, create_operation_id) VALUES (?, ?, ?)",
                id, ownerId, operationId);
    }

    void insertVersion(UUID id, UUID resumeId, int versionNo, UUID operationId,
            ResumeText.Normalized body) {
        jdbc.update("INSERT INTO material_resume_version"
                + "(id, resume_id, version_no, operation_id, body, codepoint_count) VALUES (?, ?, ?, ?, ?, ?)",
                id, resumeId, versionNo, operationId, body.body(), body.codepointCount());
    }

    int nextVersion(UUID resumeId) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 "
                + "FROM material_resume_version WHERE resume_id = ?", Integer.class, resumeId);
        return next == null ? 1 : next;
    }

    Optional<Detail> detail(UUID ownerId, UUID resumeId, UUID versionId) {
        String sql = "SELECT r.id AS resume_id, v.id AS version_id, v.version_no, v.body, "
                + "v.codepoint_count, v.confirmed_at FROM material_resume r "
                + "JOIN material_resume_version v ON v.resume_id = r.id "
                + "WHERE r.owner_account_id = ? AND r.id = ? AND r.state = 'ACTIVE' "
                + (versionId == null ? "ORDER BY v.version_no DESC LIMIT 1"
                        : "AND v.id = ?");
        Object[] parameters = versionId == null ? new Object[] {ownerId, resumeId}
                : new Object[] {ownerId, resumeId, versionId};
        return jdbc.query(sql, (rs, n) -> new Detail(rs.getObject("resume_id", UUID.class),
                rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                rs.getString("body"), rs.getInt("codepoint_count"),
                rs.getObject("confirmed_at", OffsetDateTime.class)), parameters).stream().findFirst();
    }

    Optional<Detail> detailByOperation(UUID ownerId, UUID resumeId, UUID operationId) {
        return jdbc.query("SELECT r.id AS resume_id, v.id AS version_id, v.version_no, v.body, "
                + "v.codepoint_count, v.confirmed_at FROM material_resume r "
                + "JOIN material_resume_version v ON v.resume_id = r.id "
                + "WHERE r.owner_account_id = ? AND r.id = ? AND r.state = 'ACTIVE' "
                + "AND v.operation_id = ?",
                (rs, n) -> new Detail(rs.getObject("resume_id", UUID.class),
                        rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                        rs.getString("body"), rs.getInt("codepoint_count"),
                        rs.getObject("confirmed_at", OffsetDateTime.class)),
                ownerId, resumeId, operationId).stream().findFirst();
    }

    List<Summary> list(UUID ownerId) {
        return jdbc.query("SELECT r.id AS resume_id, v.id AS version_id, v.version_no, "
                + "v.codepoint_count, v.confirmed_at FROM material_resume r "
                + "JOIN LATERAL (SELECT id, version_no, codepoint_count, confirmed_at "
                + "FROM material_resume_version WHERE resume_id = r.id "
                + "ORDER BY version_no DESC LIMIT 1) v ON TRUE "
                + "WHERE r.owner_account_id = ? AND r.state = 'ACTIVE' "
                + "ORDER BY r.created_at DESC, r.id",
                (rs, n) -> new Summary(rs.getObject("resume_id", UUID.class),
                        rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                        rs.getInt("codepoint_count"), rs.getObject("confirmed_at", OffsetDateTime.class)),
                ownerId);
    }

    List<UUID> versionIds(UUID resumeId) {
        return jdbc.queryForList("SELECT id FROM material_resume_version WHERE resume_id = ?",
                UUID.class, resumeId);
    }

    List<Summary> versions(UUID ownerId, UUID resumeId, boolean jdVersion) {
        // 表名只由服务端布尔选择；资源身份与归属仍使用绑定参数。
        String table = jdVersion ? "material_jd_version" : "material_resume_version";
        return jdbc.query("SELECT r.id AS resume_id, v.id AS version_id, v.version_no, "
                + "v.codepoint_count, v.confirmed_at FROM " + table + " v "
                + "JOIN material_resume r ON r.id = v.resume_id "
                + "WHERE r.owner_account_id = ? AND r.id = ? AND r.state = 'ACTIVE' "
                + "ORDER BY v.version_no DESC",
                (rs, n) -> new Summary(rs.getObject("resume_id", UUID.class),
                        rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                        rs.getInt("codepoint_count"), rs.getObject("confirmed_at", OffsetDateTime.class)),
                ownerId, resumeId);
    }

    Optional<Detail> jdDetail(UUID ownerId, UUID resumeId, UUID versionId, UUID operationId) {
        return jdbc.query("SELECT r.id AS resume_id, v.id AS version_id, v.version_no, v.body, "
                + "v.codepoint_count, v.confirmed_at FROM material_jd_version v "
                + "JOIN material_resume r ON r.id = v.resume_id "
                + "WHERE r.owner_account_id = ? AND r.id = ? AND r.state = 'ACTIVE' "
                + (operationId != null ? "AND v.operation_id = ?" : "AND v.id = ?"),
                (rs, n) -> new Detail(rs.getObject("resume_id", UUID.class),
                        rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                        rs.getString("body"), rs.getInt("codepoint_count"),
                        rs.getObject("confirmed_at", OffsetDateTime.class)),
                ownerId, resumeId, operationId != null ? operationId : versionId).stream().findFirst();
    }

    void insertJdVersion(UUID id, UUID resumeId, UUID operationId, ResumeText.Normalized body) {
        // 调用者必须持有账号锁及根行锁，序号分配与版本接受共同提交。
        jdbc.update("INSERT INTO material_jd_version"
                + "(id, resume_id, version_no, operation_id, body, codepoint_count) "
                + "SELECT ?, ?, COALESCE(MAX(version_no), 0) + 1, ?, ?, ? "
                + "FROM material_jd_version WHERE resume_id = ?",
                id, resumeId, operationId, body.body(), body.codepointCount(), resumeId);
    }

    int countJdVersions(UUID resumeId) {
        return jdbc.queryForObject("SELECT count(*) FROM material_jd_version WHERE resume_id = ?",
                Integer.class, resumeId);
    }

    int countVersions(UUID resumeId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM material_resume_version WHERE resume_id = ?",
                Integer.class, resumeId);
        return count == null ? 0 : count;
    }

    OffsetDateTime databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    void recordDeletion(UUID requestId, UUID resumeId, OffsetDateTime now, List<UUID> versions) {
        jdbc.update("INSERT INTO material_delete_request"
                + "(id, resume_id, requested_at, cleanup_deadline_at, state, cleaned_at) "
                + "VALUES (?, ?, ?, ? , 'CLEANED', ?)",
                requestId, resumeId, now, now.plusHours(24), now);
        for (UUID versionId : versions) {
            jdbc.update("INSERT INTO material_cleanup_item"
                    + "(id, delete_request_id, target_type, target_id, state) "
                    + "VALUES (?, ?, 'RESUME_VERSION', ?, 'CLEANED')",
                    UUID.randomUUID(), requestId, versionId);
        }
        for (UUID versionId : jdbc.queryForList("SELECT id FROM material_jd_version WHERE resume_id = ?",
                UUID.class, resumeId)) {
            jdbc.update("INSERT INTO material_cleanup_item"
                    + "(id, delete_request_id, target_type, target_id, state) "
                    + "VALUES (?, ?, 'JD_VERSION', ?, 'CLEANED')", UUID.randomUUID(), requestId, versionId);
        }
        jdbc.update("DELETE FROM material_jd_version WHERE resume_id = ?", resumeId);
        jdbc.update("DELETE FROM material_resume_version WHERE resume_id = ?", resumeId);
        jdbc.update("UPDATE material_resume SET state = 'DELETED', generation = generation + 1, "
                + "deleted_at = ? WHERE id = ? AND state = 'ACTIVE'", now, resumeId);
    }

    Optional<Deletion> deletion(UUID ownerId, UUID resumeId) {
        return jdbc.query("SELECT d.id, d.requested_at, d.cleanup_deadline_at, d.state, "
                + "d.cleaned_at FROM material_delete_request d "
                + "JOIN material_resume r ON r.id = d.resume_id "
                + "WHERE r.owner_account_id = ? AND r.id = ?",
                (rs, n) -> new Deletion(rs.getObject("id", UUID.class),
                        rs.getObject("requested_at", OffsetDateTime.class),
                        rs.getObject("cleanup_deadline_at", OffsetDateTime.class),
                        rs.getString("state"), rs.getObject("cleaned_at", OffsetDateTime.class)),
                ownerId, resumeId).stream().findFirst();
    }

    record Root(UUID id, UUID ownerId, String state) {
    }

    record Summary(UUID resumeId, UUID versionId, int versionNo, int codepointCount,
            OffsetDateTime confirmedAt) {
    }

    record Detail(UUID resumeId, UUID versionId, int versionNo, String body,
            int codepointCount, OffsetDateTime confirmedAt) {
    }

    record Deletion(UUID requestId, OffsetDateTime requestedAt, OffsetDateTime deadlineAt,
            String state, OffsetDateTime cleanedAt) {
    }
}
