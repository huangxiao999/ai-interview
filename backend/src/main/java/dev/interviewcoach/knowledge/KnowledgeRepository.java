package dev.interviewcoach.knowledge;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!bootstrap")
class KnowledgeRepository {
    private final JdbcTemplate jdbc;

    KnowledgeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SELECT = """
            SELECT v.*, r.decision, r.reason, r.reviewer_id, r.reviewed_at
            FROM knowledge_version v LEFT JOIN knowledge_review r ON r.version_id=v.id
            """;
    private static final RowMapper<Version> MAPPER = (rs, n) -> new Version(
            rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getInt("version_no"),
            rs.getObject("input_document_id", UUID.class),
            new KnowledgeInput(rs.getString("title"), rs.getString("body"), rs.getString("source_url"),
                    rs.getString("source_anchor"), rs.getString("applicability"), rs.getString("usage_note"),
                    rs.getString("verification_note"), rs.getObject("verified_on", LocalDate.class)),
            rs.getString("digest"), rs.getObject("submitted_at", OffsetDateTime.class),
            rs.getString("decision"), rs.getString("reason"), rs.getObject("reviewer_id", UUID.class),
            rs.getObject("reviewed_at", OffsetDateTime.class));

    Optional<Version> findVersion(UUID documentId, UUID versionId) {
        return jdbc.query(SELECT + " WHERE v.document_id=? AND v.id=?", MAPPER, documentId, versionId)
                .stream().findFirst();
    }

    Optional<Operation> findOperation(UUID actorId, UUID operationId) {
        var submitted = jdbc.query(SELECT + " WHERE v.submitted_by=? AND v.operation_id=?",
                MAPPER, actorId, operationId);
        if (!submitted.isEmpty()) return Optional.of(new Operation("SUBMIT", submitted.getFirst()));
        return jdbc.query(SELECT + " WHERE r.reviewer_id=? AND r.operation_id=?", MAPPER, actorId, operationId)
                .stream().findFirst().map(v -> new Operation("REVIEW", v));
    }

    List<Version> list() {
        return jdbc.query(SELECT + " ORDER BY v.submitted_at DESC, v.id", MAPPER);
    }

    void createRoot(UUID id) { jdbc.update("INSERT INTO knowledge_document(id) VALUES(?)", id); }

    void lockRoot(UUID id) {
        if (jdbc.queryForList("SELECT id FROM knowledge_document WHERE id=? FOR UPDATE", UUID.class, id).isEmpty()) {
            throw new KnowledgeUnavailable();
        }
    }

    void insert(UUID id, UUID documentId, UUID inputDocumentId, UUID actorId, UUID operationId, KnowledgeInput input) {
        jdbc.update("""
                INSERT INTO knowledge_version(id,document_id,version_no,submitted_by,operation_id,input_document_id,
                title,body,source_url,source_anchor,applicability,usage_note,verification_note,verified_on,digest)
                VALUES(?,?,(SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_version WHERE document_id=?),
                ?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, documentId, documentId, actorId, operationId, inputDocumentId, input.title(), input.body(), input.sourceUrl(),
                input.sourceAnchor(), input.applicability(), input.usageNote(), input.verificationNote(),
                input.verifiedOn(), input.digest());
    }

    void review(UUID versionId, UUID actorId, UUID operationId, String decision, String reason) {
        jdbc.update("INSERT INTO knowledge_review(version_id,reviewer_id,operation_id,decision,reason) "
                + "VALUES(?,?,?,?,?)", versionId, actorId, operationId, decision, reason);
    }

    Optional<Version> findApproved(UUID versionId) {
        return jdbc.query(SELECT + " WHERE v.id=? AND r.decision='APPROVE'", MAPPER, versionId)
                .stream().findFirst();
    }

    record Version(UUID id, UUID documentId, int versionNo, UUID inputDocumentId, KnowledgeInput input, String digest,
            OffsetDateTime submittedAt, String decision, String reason, UUID reviewerId, OffsetDateTime reviewedAt) { }
    record Operation(String kind, Version version) { }
}
