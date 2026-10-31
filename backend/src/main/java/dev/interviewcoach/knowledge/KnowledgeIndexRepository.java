package dev.interviewcoach.knowledge;

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
class KnowledgeIndexRepository {
    private final JdbcTemplate jdbc;
    KnowledgeIndexRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static final RowMapper<Build> BUILD = (r, n) -> new Build(r.getObject("id", UUID.class),
            r.getObject("document_id", UUID.class), r.getObject("version_id", UUID.class),
            r.getObject("owner_id", UUID.class), r.getString("version_digest"), r.getString("config_digest"),
            r.getString("config_json"), r.getString("policy"), r.getString("state"), r.getString("error_category"));
    static final RowMapper<Batch> BATCH = (r, n) -> new Batch(r.getObject("id", UUID.class),
            r.getObject("build_id", UUID.class), r.getInt("ordinal"), r.getString("input_json"),
            r.getString("input_digest"), r.getString("state"), r.getInt("attempt_count"), r.getLong("generation"),
            r.getObject("current_attempt", UUID.class), r.getObject("queued_at", OffsetDateTime.class));

    Optional<Build> findBuild(UUID id) { return jdbc.query("SELECT * FROM knowledge_index_build WHERE id=?", BUILD, id).stream().findFirst(); }
    Optional<Build> findBinding(UUID version, String config) {
        return jdbc.query("SELECT * FROM knowledge_index_build WHERE version_id=? AND config_digest=? AND policy=?",
                BUILD, version, config, KnowledgeIndexPolicy.REVISION).stream().findFirst();
    }
    Optional<Request> findRequest(UUID actor, UUID operation) {
        return jdbc.query("SELECT * FROM knowledge_index_request WHERE actor_id=? AND operation_id=?",
                (r, n) -> new Request(r.getObject("build_id", UUID.class), r.getString("kind"), r.getString("input_digest")), actor, operation).stream().findFirst();
    }
    List<Batch> batches(UUID build) { return jdbc.query("SELECT * FROM knowledge_index_batch WHERE build_id=? ORDER BY ordinal", BATCH, build); }
    Optional<Batch> findBatch(UUID id) { return jdbc.query("SELECT * FROM knowledge_index_batch WHERE id=?", BATCH, id).stream().findFirst(); }
    List<Build> list(UUID root, UUID version) { return jdbc.query("SELECT * FROM knowledge_index_build WHERE document_id=? AND version_id=? ORDER BY created_at", BUILD, root, version); }
    OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
    void request(UUID actor, UUID operation, UUID build, String kind, String digest) {
        jdbc.update("INSERT INTO knowledge_index_request(actor_id,operation_id,build_id,kind,input_digest) VALUES(?,?,?,?,?)", actor, operation, build, kind, digest);
    }
    void create(Build b) {
        jdbc.update("INSERT INTO knowledge_index_build(id,document_id,version_id,owner_id,version_digest,config_digest,config_json,policy,state) VALUES(?,?,?,?,?,?,?::jsonb,?,'QUEUED')",
                b.id(), b.root(), b.version(), b.owner(), b.digest(), b.configDigest(), b.config(), b.policy());
    }
    void batch(UUID build, int ordinal, String input) {
        jdbc.update("INSERT INTO knowledge_index_batch(id,build_id,ordinal,input_json,input_digest,state) VALUES(?,?,?,?::jsonb,?,'QUEUED')",
                UUID.randomUUID(), build, ordinal, input, KnowledgeIndexPolicy.hash(input));
    }
    Optional<Batch> findWork() {
        return jdbc.query("""
                SELECT x.* FROM knowledge_index_batch x JOIN knowledge_index_build b ON b.id=x.build_id
                WHERE b.state IN ('QUEUED','RUNNING','CANDIDATE_READY') AND x.state IN ('QUEUED','RUNNING','CANDIDATE_READY')
                AND NOT EXISTS(SELECT 1 FROM knowledge_index_batch p WHERE p.build_id=x.build_id AND p.ordinal<x.ordinal AND p.state<>'ACCEPTED')
                ORDER BY b.created_at,x.ordinal LIMIT 1
                """, BATCH).stream().findFirst();
    }
    void state(UUID build, UUID batch, String state, String error) {
        jdbc.update("UPDATE knowledge_index_batch SET state=? WHERE id=?", state, batch);
        jdbc.update("UPDATE knowledge_index_build SET state=?,error_category=? WHERE id=?", state.equals("ACCEPTED") ? "QUEUED" : state, error, build);
    }
    void invalidate(Build build, Batch batch) {
        jdbc.update("UPDATE knowledge_index_batch SET state='INVALIDATED',generation=generation+1 WHERE id=?", batch.id());
        jdbc.update("UPDATE knowledge_index_build SET state='INVALIDATED',error_category='QUALIFICATION_CHANGED' WHERE id=?", build.id());
    }
    void dispatch(Build b, Batch batch, UUID attempt, UUID executor, OffsetDateTime now) {
        jdbc.update("INSERT INTO knowledge_index_attempt(id,batch_id,attempt_no,generation,executor_id,state,started_at,deadline) VALUES(?,?,?,?,?,'DISPATCH_INTENT',?,?)",
                attempt, batch.id(), batch.attempts()+1, batch.generation()+1, executor, now, now.plusSeconds(KnowledgeIndexPolicy.SERVICE_SECONDS));
        jdbc.update("UPDATE knowledge_index_batch SET state='RUNNING',attempt_count=attempt_count+1,generation=generation+1,current_attempt=? WHERE id=?", attempt, batch.id());
        jdbc.update("UPDATE knowledge_index_build SET state='RUNNING',error_category=NULL WHERE id=?", b.id());
    }
    Optional<Attempt> findAttempt(UUID id) {
        return jdbc.query("SELECT * FROM knowledge_index_attempt WHERE id=?", (r,n) -> new Attempt(id,
                r.getObject("executor_id", UUID.class), r.getLong("generation"), r.getString("state"),
                r.getObject("deadline", OffsetDateTime.class), r.getString("error_category")), id).stream().findFirst();
    }
    Optional<String> findCandidate(UUID attempt) {
        return jdbc.query("SELECT payload::text FROM knowledge_index_candidate WHERE attempt_id=?", (r,n)->r.getString(1), attempt).stream().findFirst();
    }
    void candidate(Build b, Batch batch, String payload, EmbeddingGateway.Response response) {
        jdbc.update("INSERT INTO knowledge_index_candidate(attempt_id,payload) VALUES(?,?::jsonb)", batch.attempt(), payload);
        jdbc.update("UPDATE knowledge_index_attempt SET state='RESPONSE_STORED',provider_request_id=?,usage_tokens=?,usage_unknown=?,finished_at=clock_timestamp() WHERE id=?",
                response.requestId(), response.usageTokens(), response.usageTokens()==null, batch.attempt());
        state(b.id(), batch.id(), "CANDIDATE_READY", null);
    }
    void failed(Build b, Batch batch, String category, boolean externalUnknown) {
        jdbc.update("UPDATE knowledge_index_attempt SET state=?,error_category=?,finished_at=clock_timestamp() WHERE id=?",
                externalUnknown ? "EXTERNAL_UNKNOWN" : "FAILED", category, batch.attempt());
        jdbc.update("UPDATE knowledge_index_batch SET generation=generation+1 WHERE id=?", batch.id());
        state(b.id(), batch.id(), batch.attempts() >= 3 ? "FAILED_FINAL" : "WAITING_MANUAL", category);
    }
    void resume(Build b, Batch batch) {
        jdbc.update("UPDATE knowledge_index_batch SET state='QUEUED',queued_at=clock_timestamp() WHERE id=?", batch.id());
        jdbc.update("UPDATE knowledge_index_build SET state='QUEUED',error_category=NULL WHERE id=?", b.id());
    }
    void accept(Build b, Batch batch, List<KnowledgeIndexPolicy.Chunk> chunks, EmbeddingGateway.Response response) {
        for (int i=0; i<chunks.size(); i++) {
            var chunk=chunks.get(i);
            jdbc.update("INSERT INTO knowledge_index_vector(batch_id,chunk_id,ordinal,start_offset,end_offset,body,digest,embedding) VALUES(?,?,?,?,?,?,?,?)",
                    batch.id(), chunk.id(), chunk.ordinal(), chunk.startOffset(), chunk.endOffset(), chunk.text(), chunk.digest(),
                    response.vectors().get(i).toArray(Double[]::new));
        }
        jdbc.update("UPDATE knowledge_index_attempt SET state='ACCEPTED' WHERE id=?", batch.attempt());
        state(b.id(), batch.id(), "ACCEPTED", null);
        int remaining=jdbc.queryForObject("SELECT count(*) FROM knowledge_index_batch WHERE build_id=? AND state<>'ACCEPTED'", Integer.class,b.id());
        if (remaining==0) jdbc.update("UPDATE knowledge_index_build SET state='SUCCEEDED',error_category=NULL WHERE id=?",b.id());
        else jdbc.update("UPDATE knowledge_index_batch SET queued_at=clock_timestamp() WHERE id=(SELECT id FROM knowledge_index_batch WHERE build_id=? AND state='QUEUED' ORDER BY ordinal LIMIT 1)",b.id());
    }
    void suspendAcceptance(UUID build) { jdbc.update("UPDATE knowledge_index_build SET state='WAITING_MANUAL',error_category='ACCEPTANCE_UNKNOWN' WHERE id=? AND state='CANDIDATE_READY'",build); }
    List<UUID> recoveryCandidates() {
        return jdbc.queryForList("SELECT id FROM knowledge_index_build WHERE state='WAITING_MANUAL' AND error_category='ACCEPTANCE_UNKNOWN'",UUID.class);
    }

    record Build(UUID id,UUID root,UUID version,UUID owner,String digest,String configDigest,String config,String policy,String state,String error) { }
    record Batch(UUID id,UUID build,int ordinal,String input,String digest,String state,int attempts,long generation,UUID attempt,OffsetDateTime queuedAt) { }
    record Request(UUID build,String kind,String digest) { }
    record Attempt(UUID id,UUID executor,long generation,String state,OffsetDateTime deadline,String error) { }
}
