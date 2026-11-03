package dev.interviewcoach.knowledge;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
class KnowledgeCollectionRepository {
    private final JdbcTemplate jdbc;
    KnowledgeCollectionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Collection(UUID id,String configDigest,String config,String chunkPolicy,String retrievalPolicy,
            String manifestDigest,int entryCount,OffsetDateTime publishedAt) { }
    record Request(String kind,String digest,UUID resource) { }
    record Stop(UUID versionId,UUID actorId,String reason,OffsetDateTime stoppedAt) { }
    record VectorRow(UUID batch,UUID chunk,int ordinal,int start,int end,String body,String digest,List<Double> vector) { }
    record Entry(UUID chunkId,UUID buildId,UUID versionId,UUID documentId,String versionDigest,OffsetDateTime reviewedAt,
            String sourceUrl,String sourceAnchor,String applicability,String technology,String technologyVersion,String scenario,
            String claimKey,String claimValue,String quote,String verificationNote,int startOffset,int endOffset,
            String body,String digest,String vector) { }
    record Hit(Entry entry,double similarity) { }
    private static final RowMapper<Collection> COLLECTION=(r,n)->new Collection(r.getObject("id",UUID.class),r.getString("config_digest"),
            r.getString("config_json"),r.getString("chunk_policy"),r.getString("retrieval_policy"),r.getString("manifest_digest"),
            r.getInt("entry_count"),r.getObject("published_at",OffsetDateTime.class));
    private static Entry entry(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Entry(r.getObject("chunk_id",UUID.class),r.getObject("build_id",UUID.class),r.getObject("version_id",UUID.class),
                r.getObject("document_id",UUID.class),r.getString("version_digest"),r.getObject("reviewed_at",OffsetDateTime.class),
                r.getString("source_url"),r.getString("source_anchor"),r.getString("applicability"),r.getString("technology"),
                r.getString("technology_version"),r.getString("scenario"),r.getString("claim_key"),r.getString("claim_value"),
                r.getString("quote"),r.getString("verification_note"),r.getInt("start_offset"),r.getInt("end_offset"),
                r.getString("body"),r.getString("digest"),null);
    }
    Optional<Collection> find(UUID id) { return jdbc.query("SELECT * FROM knowledge_collection WHERE id=?",COLLECTION,id).stream().findFirst(); }
    Optional<UUID> findCurrent() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT collection_id FROM knowledge_collection_current WHERE singleton=1",UUID.class));
    }
    Optional<UUID> lockCurrent() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT collection_id FROM knowledge_collection_current WHERE singleton=1 FOR UPDATE",UUID.class));
    }
    Optional<Request> findRequest(UUID actor,UUID operation) {
        return jdbc.query("SELECT * FROM knowledge_collection_request WHERE actor_id=? AND operation_id=?",
                (r,n)->new Request(r.getString("kind"),r.getString("input_digest"),r.getObject("resource_id",UUID.class)),actor,operation).stream().findFirst();
    }
    void request(UUID actor,UUID operation,String kind,String digest,UUID resource) {
        jdbc.update("INSERT INTO knowledge_collection_request(actor_id,operation_id,kind,input_digest,resource_id) VALUES(?,?,?,?,?)",actor,operation,kind,digest,resource);
    }
    List<VectorRow> vectors(UUID build) {
        return jdbc.query("SELECT v.* FROM knowledge_index_vector v JOIN knowledge_index_batch b ON b.id=v.batch_id WHERE b.build_id=? ORDER BY b.ordinal,v.ordinal",
                (r,n)->new VectorRow(r.getObject("batch_id",UUID.class),r.getObject("chunk_id",UUID.class),r.getInt("ordinal"),r.getInt("start_offset"),r.getInt("end_offset"),
                        r.getString("body"),r.getString("digest"),Arrays.asList((Double[])r.getArray("embedding").getArray())),build);
    }
    void create(UUID id,UUID actor,KnowledgeIndexRepository.Build binding,String manifest,int count) {
        jdbc.update("INSERT INTO knowledge_collection(id,published_by,config_digest,config_json,chunk_policy,retrieval_policy,manifest_digest,entry_count) VALUES(?,?,?,?::jsonb,?,?,?,?)",
                id,actor,binding.configDigest(),binding.config(),binding.policy(),KnowledgeCollectionPolicy.REVISION,manifest,count);
    }
    void insert(UUID id,Entry e) {
        jdbc.update("""
                INSERT INTO knowledge_collection_entry(collection_id,chunk_id,build_id,version_id,document_id,version_digest,reviewed_at,
                source_url,source_anchor,applicability,technology,technology_version,scenario,claim_key,claim_value,quote,verification_note,
                start_offset,end_offset,body,digest,embedding) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::public.vector)
                """,id,e.chunkId(),e.buildId(),e.versionId(),e.documentId(),e.versionDigest(),e.reviewedAt(),e.sourceUrl(),e.sourceAnchor(),
                e.applicability(),e.technology(),e.technologyVersion(),e.scenario(),e.claimKey(),e.claimValue(),e.quote(),e.verificationNote(),
                e.startOffset(),e.endOffset(),e.body(),e.digest(),e.vector());
    }
    void activate(UUID id) { jdbc.update("UPDATE knowledge_collection_current SET collection_id=? WHERE singleton=1",id); }
    List<UUID> roots(UUID collection) {
        return jdbc.queryForList("SELECT DISTINCT document_id FROM knowledge_collection_entry WHERE collection_id=? ORDER BY document_id",UUID.class,collection);
    }
    /** 精确检索：SQL先限定资格/条件；Hikari只选app，类型和算子显式定位public扩展。 */
    List<Hit> search(UUID collection,KnowledgeCollectionPolicy.Conditions c,String claim,String vector) {
        return jdbc.query("""
                SELECT e.*,1-(e.embedding OPERATOR(public.<=>) ?::public.vector) AS similarity FROM knowledge_collection_entry e
                JOIN knowledge_review r ON r.version_id=e.version_id AND r.decision='APPROVE'
                WHERE e.collection_id=? AND e.technology=? AND e.technology_version=? AND e.scenario=? AND e.claim_key=?
                AND NOT EXISTS(SELECT 1 FROM knowledge_version_stop s WHERE s.version_id=e.version_id)
                ORDER BY e.embedding OPERATOR(public.<=>) ?::public.vector,e.chunk_id
                """,(r,n)->new Hit(entry(r),r.getDouble("similarity")),vector,collection,c.technology(),c.technologyVersion(),c.scenario(),claim,vector);
    }
    Optional<Stop> findStop(UUID version) {
        return jdbc.query("SELECT * FROM knowledge_version_stop WHERE version_id=?",(r,n)->new Stop(version,r.getObject("actor_id",UUID.class),
                r.getString("reason"),r.getObject("stopped_at",OffsetDateTime.class)),version).stream().findFirst();
    }
    void stop(UUID version,UUID actor,String reason) { jdbc.update("INSERT INTO knowledge_version_stop(version_id,actor_id,reason) VALUES(?,?,?)",version,actor,reason); }
}
