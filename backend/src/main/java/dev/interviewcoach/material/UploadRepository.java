package dev.interviewcoach.material;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
class UploadRepository {
    private final JdbcTemplate jdbc;
    UploadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<Upload> ROW = (rs, n) -> new Upload(rs.getObject("id", UUID.class),
            rs.getObject("owner_account_id", UUID.class), rs.getObject("operation_id", UUID.class),
            rs.getObject("resume_id", UUID.class), rs.getString("original_name"), rs.getString("file_type"),
            rs.getLong("byte_size"), rs.getString("sha256"), rs.getString("state"), rs.getLong("generation"),
            rs.getString("parsed_body"), rs.getString("error_code"), rs.getObject("deadline_at", OffsetDateTime.class),
            rs.getObject("version_id", UUID.class), rs.getObject("confirm_operation_id", UUID.class));

    Optional<Upload> byOperation(UUID owner, UUID operation) {
        return jdbc.query("SELECT * FROM material_upload WHERE owner_account_id=? AND operation_id=?", ROW,
                owner, operation).stream().findFirst();
    }
    Optional<Upload> find(UUID id) {
        return jdbc.query("SELECT * FROM material_upload WHERE id=?", ROW, id).stream().findFirst();
    }
    Optional<Upload> forVersion(UUID owner, UUID versionId) {
        return jdbc.query("SELECT * FROM material_upload WHERE owner_account_id=? AND version_id=? "
                + "AND state='CONFIRMED'", ROW, owner, versionId).stream().findFirst();
    }
    List<Upload> drafts(UUID owner) {
        return jdbc.query("SELECT u.* FROM material_upload u LEFT JOIN material_resume r ON r.id=u.resume_id "
                + "WHERE u.owner_account_id=? AND u.state IN ('RECEIVING','QUEUED','PARSING','READY','FAILED') "
                + "AND (u.resume_id IS NULL OR r.state='ACTIVE') ORDER BY u.created_at", ROW, owner);
    }
    void insert(Upload u) {
        jdbc.update("INSERT INTO material_upload(id,owner_account_id,operation_id,resume_id,target_resume_id,original_name,"
                + "file_type,byte_size,sha256,state) VALUES (?,?,?,?,?,?,?,?,?,'RECEIVING')", u.id(), u.ownerId(),
                u.operationId(), u.resumeId(), u.resumeId(), u.name(), u.type(), u.size(), u.sha());
    }
    boolean hasOriginalTarget(UUID id, UUID root) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT target_resume_id IS NOT DISTINCT FROM ?::uuid "
                + "FROM material_upload WHERE id=?", Boolean.class, root, id));
    }
    boolean queue(UUID id) {
        return jdbc.update("UPDATE material_upload SET state='QUEUED',updated_at=clock_timestamp() "
                + "WHERE id=? AND state='RECEIVING'", id) == 1;
    }
    List<Upload> queued() {
        return jdbc.query("SELECT * FROM material_upload WHERE state='QUEUED' ORDER BY created_at LIMIT 2", ROW);
    }
    List<Upload> receiving() {
        return jdbc.query("SELECT * FROM material_upload WHERE state='RECEIVING' LIMIT 100", ROW);
    }
    boolean claim(UUID id) {
        return jdbc.update("UPDATE material_upload SET state='PARSING',deadline_at=clock_timestamp()+interval '30 seconds',"
                + "updated_at=clock_timestamp() WHERE id=? AND state='QUEUED'", id) == 1;
    }
    void result(Upload u, DocumentParser.Result result) {
        // 删除/取消会推进代次；旧解析结果不能复活正文。超时用数据库截止再次裁决。
        jdbc.update("UPDATE material_upload SET state=CASE WHEN deadline_at < clock_timestamp() THEN 'FAILED' "
                + "WHEN ?='READY' THEN 'READY' ELSE 'FAILED' END, "
                + "parsed_body=CASE WHEN deadline_at >= clock_timestamp() AND ?='READY' THEN ? ELSE NULL END, "
                + "error_code=CASE WHEN deadline_at < clock_timestamp() THEN 'PARSE_TIMEOUT' ELSE ? END, "
                + "updated_at=clock_timestamp() WHERE id=? AND state='PARSING' AND generation=?",
                result.code(), result.code(), result.body(), result.code().equals("READY") ? null : result.code(),
                u.id(), u.generation());
    }
    void confirm(UUID id, ResumeRepository.Detail detail, UUID operation) {
        jdbc.update("UPDATE material_upload SET state='CONFIRMED',resume_id=?,version_id=?,confirm_operation_id=?,"
                + "parsed_body=NULL,updated_at=clock_timestamp() WHERE id=? AND state='READY'",
                detail.resumeId(), detail.versionId(), operation, id);
    }
    void cancel(UUID id) {
        jdbc.update("UPDATE material_upload SET state='DELETE_PENDING',generation=generation+1,parsed_body=NULL,"
                + "file_cleaned=false,updated_at=clock_timestamp() WHERE id=? AND state NOT IN ('CLEANED','DELETE_PENDING','CONFIRMED')", id);
    }
    void recover() {
        jdbc.update("UPDATE material_upload SET state='FAILED',parsed_body=NULL,error_code='PARSE_INTERRUPTED',"
                + "generation=generation+1,updated_at=clock_timestamp() WHERE state='PARSING'");
        // RECEIVING 在进程重启后没有活跃写入者；持久意图允许安全清理半发布文件。
        jdbc.update("UPDATE material_upload SET state='DELETE_PENDING',generation=generation+1 "
                + "WHERE state='RECEIVING'");
    }
    List<Upload> cleanup() {
        return jdbc.query("SELECT * FROM material_upload WHERE state IN ('DELETE_PENDING','FAILED') "
                + "AND NOT file_cleaned AND (deadline_at IS NULL OR deadline_at < clock_timestamp()-interval '5 seconds') "
                + "ORDER BY cleanup_attempted_at NULLS FIRST, id LIMIT 100", ROW);
    }
    void cleanupAttempt(UUID id) {
        jdbc.update("UPDATE material_upload SET cleanup_attempted_at=clock_timestamp() WHERE id=? "
                + "AND state IN ('DELETE_PENDING','FAILED') AND NOT file_cleaned", id);
    }
    void cleanupFailed(UUID id) {
        jdbc.update("UPDATE material_upload SET cleanup_error_code='FILE_IO' WHERE id=? "
                + "AND state IN ('DELETE_PENDING','FAILED') AND NOT file_cleaned", id);
    }
    int overdueDeletions() {
        return jdbc.queryForObject("SELECT count(*) FROM material_delete_request WHERE state='PENDING' "
                + "AND cleanup_deadline_at <= clock_timestamp()", Integer.class);
    }
    void cleaned(UUID id) {
        jdbc.update("UPDATE material_upload SET file_cleaned=true,cleanup_error_code=NULL,original_name=CASE WHEN state='DELETE_PENDING' THEN 'removed' ELSE original_name END,"
                + "sha256=CASE WHEN state='DELETE_PENDING' THEN '' ELSE sha256 END,state=CASE WHEN state='DELETE_PENDING' "
                + "THEN 'CLEANED' ELSE state END,parsed_body=NULL WHERE id=? AND state IN ('DELETE_PENDING','FAILED')", id);
        jdbc.update("UPDATE material_cleanup_item SET state='CLEANED' WHERE target_type='PRIVATE_FILE' AND target_id=?", id);
        jdbc.update("UPDATE material_delete_request d SET state='CLEANED',cleaned_at=clock_timestamp() "
                + "WHERE state='PENDING' AND NOT EXISTS (SELECT 1 FROM material_cleanup_item c "
                + "WHERE c.delete_request_id=d.id AND c.state='PENDING')");
    }
    void expireParsers() {
        jdbc.update("UPDATE material_upload SET state='FAILED',parsed_body=NULL,error_code='PARSE_TIMEOUT',"
                + "generation=generation+1 WHERE state='PARSING' AND deadline_at < clock_timestamp()");
    }
    record Upload(UUID id, UUID ownerId, UUID operationId, UUID resumeId, String name, String type, long size,
            String sha, String state, long generation, String body, String error, OffsetDateTime deadline,
            UUID versionId, UUID confirmOperationId) { }
}
