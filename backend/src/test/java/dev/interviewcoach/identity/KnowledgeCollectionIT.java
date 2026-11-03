package dev.interviewcoach.identity;

import dev.interviewcoach.InterviewCoachApplication;
import dev.interviewcoach.knowledge.EmbeddingGateway;
import dev.interviewcoach.knowledge.KnowledgeService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** 合成供应商只给确定向量；发布、权限、过滤、锁及PG仓储全走生产HTTP路径。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeCollectionIT {
    static final ObjectMapper JSON=JsonMapper.builder().build();
    static final String API="/api/maintenance/knowledge";
    static final AtomicBoolean COMMIT_LOSS=new AtomicBoolean(), SEARCH_FAILURE=new AtomicBoolean();
    static final AtomicInteger CALLS=new AtomicInteger();
    static volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;static URI base;static String database;
    static Account maintainer,other,user;
    static Map<String,Object> originalCollection;static Fixture originalFixture;static UUID originalOperation;
    @BeforeAll static void start() {
        database=System.getenv("P3_COLLECTION_TEST_DATABASE");
        if(database==null || !database.matches("p3dt03_verify_[a-f0-9]{16}"))throw new IllegalStateException("Fresh isolated PG16 required");
        var admin=new JdbcTemplate(raw());assertThat(admin.queryForObject("SELECT current_database()",String.class)).isEqualTo(database);
        assertThat(admin.queryForObject("SHOW server_version_num",Integer.class)).isBetween(160000,169999);
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='app'",Integer.class)).isZero();
        assertThat(flyway("8").migrate().migrationsExecuted).isEqualTo(8);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
        var history=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        var current=flyway(null);assertThat(current.migrate().migrationsExecuted).isEqualTo(1);current.validate();assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND version<>'9' ORDER BY installed_rank")).isEqualTo(history);
        maintainer=account(AccountAuthority.KNOWLEDGE_MAINTAINER);other=account(AccountAuthority.KNOWLEDGE_MAINTAINER);user=account(AccountAuthority.TRIAL_USER);
        startApplication();
    }
    static Flyway flyway(String target) {
        var c=Flyway.configure().dataSource(raw()).schemas("app").defaultSchema("app");if(target!=null)c.target(target);return c.load();
    }
    static DataSource raw() { return new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")); }
    static void startApplication() {
        context=new SpringApplicationBuilder(InterviewCoachApplication.class,FixtureConfig.class).profiles("local").run("--server.port=0","--BAILIAN_EMBEDDING_ENABLED=false",
                "--material.private-root="+Path.of("../.cache",database,"private").toAbsolutePath());
        base=URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterAll static void close() { release.countDown();if(context!=null)context.close(); }
    @AfterEach void releaseFaults() { COMMIT_LOSS.set(false);SEARCH_FAILURE.set(false);release.countDown(); }

    @Test @Order(1) void migrationAndDefaultDisabledHaveRealEvidence() {
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='app.knowledge_collection_entry'::regclass AND attname='embedding'",String.class)).isEqualTo("public.vector(1024)");
        assertThat(((EmbeddingGateway)context.getBean("aliyunEmbeddingGateway")).available()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_version_stop",Integer.class)).isZero();
    }

    @Test @Order(2) void publishesWholeSnapshotAndQueriesOldOperationAfterPointerMoves() throws Exception {
        Client a=new Client(maintainer);originalFixture=build(a,"[X] publication source");originalOperation=UUID.randomUUID();
        var prepared=ok(a.get("/index-builds/"+originalFixture.build()+"/publication-source"));
        assertThat(prepared).containsEntry("eligible",true).containsEntry("versionDigest",originalFixture.digest());
        var input=publication(currentId(),List.of(selection(originalFixture,"21","jmm","visible","yes")));
        originalCollection=ok(a.post("/collections",input,originalOperation));
        assertThat(originalCollection).containsEntry("runtimeReady",false).containsEntry("entryCount",1);
        var result=probe(a,originalCollection,"21","jmm","visible",vector(0));
        assertThat(result).containsEntry("status","EVIDENCE_AVAILABLE").containsEntry("runtimeReady",false);
        var candidate=candidates(result).getFirst();
        assertThat(candidate).containsEntry("body","[X] publication source").containsEntry("sourceAnchor","section 1");
        assertThat(candidate.get("chunkId")).isEqualTo(claims(selection(originalFixture,"21","jmm","visible","yes")).getFirst().get("chunkId"));
        var oldManifest=originalCollection.get("manifestDigest");
        var newer=ok(a.post("/collections",publication(currentId(),List.of(selection(originalFixture,"21","jmm","visible","yes"))),UUID.randomUUID()));
        assertThat(newer.get("collectionId")).isNotEqualTo(originalCollection.get("collectionId"));
        assertThat(ok(a.post("/collections",input,originalOperation))).isEqualTo(originalCollection);
        assertThat(currentId().toString()).isEqualTo(newer.get("collectionId"));
        assertThat(ok(a.get("/collections/"+originalCollection.get("collectionId"))).get("manifestDigest")).isEqualTo(oldManifest);
        assertThat(probe(a,originalCollection,"21","jmm","visible",vector(0)).get("status")).isEqualTo("EVIDENCE_AVAILABLE");
        assertThat(a.post("/collections",publication(currentId(),List.of(selection(originalFixture,"21","other","visible","yes"))),originalOperation).statusCode()).isEqualTo(409);
    }

    @Test @Order(3) void refusesIncompleteUnapprovedMixedAndUnsupportedSources() throws Exception {
        Client a=new Client(maintainer);var f=build(a,"[X] completeness");UUID before=currentId();
        var selection=selection(f,"21","locks","held","yes");var forged=new LinkedHashMap<>(selection);
        forged.put("claims",List.of(Map.of("chunkId",UUID.randomUUID(),"claimKey","held","claimValue","yes","quote","missing","verificationNote","Synthetic quote check")));
        assertThat(a.post("/collections",publication(before,List.of(forged)),UUID.randomUUID()).statusCode()).isEqualTo(409);
        var unsupported=new LinkedHashMap<>(selection);var first=new LinkedHashMap<>(claims(selection).getFirst());first.put("quote","not in source");unsupported.put("claims",List.of(first));
        assertThat(a.post("/collections",publication(before,List.of(unsupported)),UUID.randomUUID()).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE knowledge_review SET decision='REJECT' WHERE version_id=?",f.version());
        assertThat(a.post("/collections",publication(before,List.of(selection)),UUID.randomUUID()).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE knowledge_review SET decision='APPROVE' WHERE version_id=?",f.version());
        var mixed=build(a,"[X] different config");
        var alternate=new TreeMap<>(context.getBean(EmbeddingGateway.class).configuration());alternate.put("model","text-embedding-v4");
        String alternateJson=JSON.writeValueAsString(alternate);
        String alternateHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(alternateJson.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("UPDATE knowledge_index_build SET config_json=?::jsonb,config_digest=? WHERE id=?",alternateJson,alternateHash,mixed.build());
        assertThat(a.post("/collections",publication(before,List.of(selection,selection(mixed,"21","locks","held","yes"))),UUID.randomUUID()).statusCode()).isEqualTo(409);
        jdbc.update("DELETE FROM knowledge_index_vector WHERE batch_id IN (SELECT id FROM knowledge_index_batch WHERE build_id=?)",f.build());
        assertThat(a.post("/collections",publication(before,List.of(selection)),UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(currentId()).isEqualTo(before);
    }

    @Test @Order(4) void rollsBackHeaderEntriesPointerAndReceiptTogether() throws Exception {
        Client a=new Client(maintainer);var one=build(a,"[X] rollback one");var two=build(a,"[X] rollback two");UUID before=currentId(),op=UUID.randomUUID();
        int count=jdbc.queryForObject("SELECT count(*) FROM knowledge_collection",Integer.class);
        jdbc.execute("CREATE FUNCTION collection_insert_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.claim_value='FAIL_INSERT' THEN RAISE EXCEPTION 'synthetic publication failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER collection_insert_fault BEFORE INSERT ON knowledge_collection_entry FOR EACH ROW EXECUTE FUNCTION collection_insert_fault()");
        try {
            var response=a.post("/collections",publication(before,List.of(selection(one,"21","rollback","k","yes"),selection(two,"21","rollback","k","FAIL_INSERT"))),op);
            assertThat(response.statusCode()).isEqualTo(503);assertThat(json(response).get("code")).isEqualTo("RESULT_UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_collection",Integer.class)).isEqualTo(count);
            assertThat(currentId()).isEqualTo(before);assertThat(a.get("/collection-operations/"+op).statusCode()).isEqualTo(404);
        } finally { jdbc.execute("DROP TRIGGER collection_insert_fault ON knowledge_collection_entry");jdbc.execute("DROP FUNCTION collection_insert_fault()"); }
    }

    @Test @Order(5) void concurrentPublicationUsesExpectedPointerWithoutHalfSnapshots() throws Exception {
        Client a=new Client(maintainer),b=new Client(other);var f=build(a,"[X] simultaneous publication");UUID before=currentId();
        var input=publication(before,List.of(selection(f,"21","race","k","yes")));var go=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(()->{go.await();return a.post("/collections",input,UUID.randomUUID());});
            var second=threads.submit(()->{go.await();return b.post("/collections",input,UUID.randomUUID());});go.countDown();
            assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT entry_count FROM knowledge_collection WHERE id=?",Integer.class,currentId())).isEqualTo(1);
    }

    @Test @Order(6) void lostCommitAcknowledgmentCanQueryAndReplayOriginalCollection() throws Exception {
        Client a=new Client(maintainer),b=new Client(other);var f=build(a,"[X] lost confirmation");var op=UUID.randomUUID();
        var input=publication(currentId(),List.of(selection(f,"21","unknown","k","yes")));int before=jdbc.queryForObject("SELECT count(*) FROM knowledge_collection",Integer.class);
        COMMIT_LOSS.set(true);var response=a.post("/collections",input,op);assertThat(response.statusCode()).isEqualTo(503);
        var receipt=ok(a.get("/collection-operations/"+op));var restored=ok(a.post("/collections",input,op));
        assertThat(restored.get("collectionId")).isEqualTo(receipt.get("resourceId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_collection",Integer.class)).isEqualTo(before+1);
        assertThat(b.get("/collection-operations/"+op).statusCode()).isEqualTo(404);
        assertThat(a.post("/documents",KnowledgeMaintenanceIT.input("collision"),op).statusCode()).isEqualTo(409);
    }

    @Test @Order(7) void filtersConditionsBeforeCosineAndKeepsUnknownDistinctFromErrors() throws Exception {
        Client a=new Client(maintainer);var v21=build(a,"[X] java21");var v17=build(a,"[X] java17");var far=build(a,"[Y] opposite vector");
        var c=ok(a.post("/collections",publication(currentId(),List.of(selection(v21,"21","version","k","yes"),selection(v17,"17","version","k","no"),selection(far,"21","far","k","yes"))),UUID.randomUUID()));
        assertThat(candidates(probe(a,c,"21","version","k",vector(0)))).hasSize(1);
        assertThat(probe(a,c,"17","version","k",vector(0)).get("status")).isEqualTo("EVIDENCE_AVAILABLE");
        assertThat(probe(a,c,"","version","k",vector(0))).containsEntry("gap","CONDITIONS_REQUIRED");
        assertThat(probe(a,c,"21","far","k",vector(0)).get("status")).isEqualTo("NO_APPLICABLE_EVIDENCE");
        var bad=probeInput(c,"21","version","k",vector(0));bad.put("configDigest","bad");
        assertThat(a.post("/collections/"+c.get("collectionId")+"/probe",bad,UUID.randomUUID()).statusCode()).isEqualTo(409);
        SEARCH_FAILURE.set(true);
        try { var r=a.post("/collections/"+c.get("collectionId")+"/probe",probeInput(c,"21","version","k",vector(0)),UUID.randomUUID());
            assertThat(r.statusCode()).isEqualTo(503);assertThat(json(r)).containsEntry("code","RESULT_UNKNOWN");
        } finally { SEARCH_FAILURE.set(false); }
    }

    @Test @Order(8) void conflictCannotBeHiddenByThresholdOrTopK() throws Exception {
        Client a=new Client(maintainer);var selections=new ArrayList<Map<String,Object>>();
        for(int i=0;i<6;i++)selections.add(selection(build(a,"[X] agreement "+i),"21","conflict","k","yes"));
        selections.add(selection(build(a,"[Y] conflicting low score"),"21","conflict","k","no"));
        var c=ok(a.post("/collections",publication(currentId(),selections),UUID.randomUUID()));
        assertThat(probe(a,c,"21","conflict","k",vector(0))).containsEntry("status","CONFLICT_UNRESOLVED");
        assertThat((List<?>)probe(a,c,"21","conflict","k",vector(0)).get("conflicts")).hasSize(7);
        assertThat(candidates(probe(a,c,"21","conflict","k",vector(0)))).isEmpty();
    }

    @Test @Order(9) void stopPreservesHistoricalFactsAndBlocksNewUsesAcrossModules() throws Exception {
        Client a=new Client(maintainer);var f=build(a,"[X] stopped history");
        var c=ok(a.post("/collections",publication(currentId(),List.of(selection(f,"21","stop","k","yes"))),UUID.randomUUID()));
        UUID op=UUID.randomUUID();var stop=Map.of("digest",f.digest(),"reason","Synthetic source no longer safe");
        var fact=ok(a.post(versionPath(f)+"/stop",stop,op));assertThat(ok(a.post(versionPath(f)+"/stop",stop,op))).isEqualTo(fact);
        assertThat(ok(a.get("/index-builds/"+f.build()+"/publication-source"))).containsEntry("eligible",false);
        assertThat(context.getBean(KnowledgeService.class).findApproved(f.version())).isEmpty();
        assertThat(probe(a,c,"21","stop","k",vector(0)).get("status")).isEqualTo("NO_APPLICABLE_EVIDENCE");
        assertThat(a.post(versionPath(f)+"/index-builds",Map.of("digest",f.digest()),UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(a.post("/collections",publication(currentId(),List.of(selection(f,"21","stop","k","yes"))),UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(ok(a.get(versionPath(f))).get("state")).isEqualTo("APPROVED");
        assertThat(ok(a.get("/collections/"+c.get("collectionId")))).isEqualTo(c);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_collection_entry WHERE collection_id=?",Integer.class,UUID.fromString(c.get("collectionId").toString()))).isEqualTo(1);
    }

    @Test @Order(10) void stopDuringExternalCallFencesLateCandidateAndKeepsReceiptOnly() throws Exception {
        Client a=new Client(maintainer);entered=new CountDownLatch(1);release=new CountDownLatch(1);
        var v=approved(a,"[SLOW_STOP] synthetic late source");var f=createBuild(a,v);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        var gen=jdbc.queryForObject("SELECT generation FROM knowledge_index_batch WHERE build_id=?",Long.class,f.build());
        ok(a.post(versionPath(f)+"/stop",Map.of("digest",f.digest(),"reason","Synthetic late fencing"),UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT state FROM knowledge_index_build WHERE id=?",String.class,f.build())).isEqualTo("INVALIDATED");
        assertThat(jdbc.queryForObject("SELECT generation FROM knowledge_index_batch WHERE build_id=?",Long.class,f.build())).isGreaterThan(gen);
        release.countDown();await(()->jdbc.queryForObject("SELECT count(*) FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=? AND a.usage_tokens=10",Integer.class,f.build())==1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_vector v JOIN knowledge_index_batch b ON b.id=v.batch_id WHERE b.build_id=?",Integer.class,f.build())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_candidate c JOIN knowledge_index_attempt a ON a.id=c.attempt_id JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=?",Integer.class,f.build())).isZero();
        assertThat(a.post("/index-builds/"+f.build()+"/resume",Map.of("attemptId",UUID.randomUUID(),"acceptPossibleDuplicateCharge",true),UUID.randomUUID()).statusCode()).isEqualTo(409);
    }

    @Test @Order(11) void stopWinsRootLockBeforePublicationAndDoesNotRebindOldCollection() throws Exception {
        Client a=new Client(maintainer),b=new Client(other);var f=build(a,"[X] stop versus publish");UUID before=currentId();
        var input=publication(before,List.of(selection(f,"21","race-stop","k","yes")));
        try(var blocker=jdbc.getDataSource().getConnection();var threads=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try(var s=blocker.prepareStatement("SELECT id FROM knowledge_document WHERE id=? FOR UPDATE")){s.setObject(1,f.root());s.execute();}
            var stopped=threads.submit(()->b.post(versionPath(f)+"/stop",Map.of("digest",f.digest(),"reason","Synthetic stop first"),UUID.randomUUID()));
            await(()->rootWaiters()>=1);
            var published=threads.submit(()->a.post("/collections",input,UUID.randomUUID()));
            await(()->rootWaiters()>=2);blocker.commit();
            assertThat(stopped.get().statusCode()).isEqualTo(200);assertThat(published.get().statusCode()).isEqualTo(409);
        }
        assertThat(currentId()).isEqualTo(before);
    }
    @Test @Order(12) void requiresSessionCsrfCurrentIdentityAndMaintainerAuthority() throws Exception {
        Client anonymous=new Client(null),ordinary=new Client(user),a=new Client(maintainer);
        assertThat(anonymous.get("/collections/current").statusCode()).isEqualTo(401);
        assertThat(ordinary.get("/collections/current").statusCode()).isEqualTo(403);
        var f=build(a,"[X] authentication");var input=publication(currentId(),List.of(selection(f,"21","auth","k","yes")));
        a.expected=other.id();assertThat(a.post("/collections",input,UUID.randomUUID()).statusCode()).isEqualTo(409);a.expected=maintainer.id();
        String token=a.token;a.token=null;assertThat(a.post("/collections",input,UUID.randomUUID()).statusCode()).isEqualTo(403);a.token=token;
        var temporary=account(AccountAuthority.KNOWLEDGE_MAINTAINER);var stale=new Client(temporary);
        jdbc.update("UPDATE identity_account SET credential_version=credential_version+1 WHERE id=?",temporary.id());
        assertThat(stale.post("/collections",input,UUID.randomUUID()).statusCode()).isEqualTo(401);
    }

    @Test @Order(13) void rejectsMalformedProbeVectorsAndBoundsReturnedContext() throws Exception {
        Client a=new Client(maintainer);var selections=new ArrayList<Map<String,Object>>();
        for(int i=0;i<7;i++)selections.add(selection(build(a,"[X] bounded "+i),"21","bounds","k","yes"));
        var c=ok(a.post("/collections",publication(currentId(),selections),UUID.randomUUID()));
        var hits=candidates(probe(a,c,"21","bounds","k",vector(0)));assertThat(hits).hasSize(5);
        assertThat(hits.stream().mapToInt(h->h.get("body").toString().codePointCount(0,h.get("body").toString().length())).sum()).isLessThanOrEqualTo(5000);
        for(var invalid:List.of(List.of(1d),Collections.nCopies(1024,0d),Collections.nCopies(1024,Double.MAX_VALUE),Collections.nCopies(1024,Double.MIN_VALUE))) {
            assertThat(a.post("/collections/"+c.get("collectionId")+"/probe",probeInput(c,"21","bounds","k",invalid),UUID.randomUUID()).statusCode()).isEqualTo(400);
        }
    }

    @Test @Order(14) void restartRetainsOriginalPublicationAndUsesNoEmbeddingForProbe() throws Exception {
        int calls=CALLS.get();context.close();startApplication();Client a=new Client(maintainer);
        assertThat(ok(a.get("/collection-operations/"+originalOperation)).get("resourceId")).isEqualTo(originalCollection.get("collectionId"));
        assertThat(probe(a,originalCollection,"21","jmm","visible",vector(0)).get("status")).isEqualTo("EVIDENCE_AVAILABLE");
        assertThat(CALLS.get()).isEqualTo(calls);
    }

    @Test @Order(15) void stopAfterCandidateStorageRejectsRecoveryAndNewVersionCanBeUsed() throws Exception {
        Client a=new Client(maintainer),b=new Client(other);
        jdbc.execute("CREATE FUNCTION collection_candidate_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.id=a.batch_id JOIN knowledge_index_build x ON x.id=b.build_id JOIN knowledge_version v ON v.id=x.version_id WHERE a.id=NEW.attempt_id AND v.body LIKE '[CANDIDATE_STOP]%') THEN PERFORM pg_advisory_xact_lock(932018); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER collection_candidate_gate BEFORE INSERT ON knowledge_index_candidate FOR EACH ROW EXECUTE FUNCTION collection_candidate_gate()");
        Fixture f;
        try(var gate=jdbc.getDataSource().getConnection();var threads=Executors.newSingleThreadExecutor()) {
            try(var s=gate.createStatement()){s.execute("SELECT pg_advisory_lock(932018)");}
            f=createBuild(a,approved(a,"[CANDIDATE_STOP] synthetic candidate gate"));
            await(()->jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'INSERT INTO knowledge_index_candidate%'",Integer.class)>0);
            Fixture fixed=f;
            var stop=threads.submit(()->b.post(versionPath(fixed)+"/stop",Map.of("digest",fixed.digest(),"reason","Synthetic candidate barrier"),UUID.randomUUID()));
            await(()->rootWaiters()>=1);
            try(var s=gate.createStatement()){s.execute("SELECT pg_advisory_unlock(932018)");}
            assertThat(stop.get().statusCode()).isEqualTo(200);
        } finally {jdbc.execute("DROP TRIGGER collection_candidate_gate ON knowledge_index_candidate");jdbc.execute("DROP FUNCTION collection_candidate_gate()");}
        assertThat(jdbc.queryForObject("SELECT state FROM knowledge_index_build WHERE id=?",String.class,f.build())).isEqualTo("INVALIDATED");
        UUID attempt=jdbc.queryForObject("SELECT current_attempt FROM knowledge_index_batch WHERE build_id=?",UUID.class,f.build());
        String payload=jdbc.queryForObject("SELECT payload::text FROM knowledge_index_candidate WHERE attempt_id=?",String.class,attempt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_vector v JOIN knowledge_index_batch b ON b.id=v.batch_id WHERE b.build_id=?",Integer.class,f.build())).isZero();
        assertThat(a.post("/index-builds/"+f.build()+"/resume",Map.of("attemptId",attempt,"acceptPossibleDuplicateCharge",true),UUID.randomUUID()).statusCode()).isEqualTo(409);
        context.close();startApplication();a=new Client(maintainer);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM knowledge_index_candidate WHERE attempt_id=?",String.class,attempt)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT state FROM knowledge_index_build WHERE id=?",String.class,f.build())).isEqualTo("INVALIDATED");
        var next=ok(a.post("/documents/"+f.root()+"/versions",KnowledgeMaintenanceIT.input("[X] corrected new version"),UUID.randomUUID()));approve(a,next);
        var fixed=createBuild(a,next);awaitSuccess(fixed);
        assertThat(fixed.version()).isNotEqualTo(f.version());assertThat(context.getBean(KnowledgeService.class).findApproved(f.version())).isEmpty();
        assertThat(context.getBean(KnowledgeService.class).findApproved(fixed.version())).isPresent();
    }
    static int rootWaiters() {return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%knowledge_document%FOR UPDATE%'",Integer.class);}
    @Test @Order(16) void publishesAllBatchesAndKeepsUnicodeOffsetsAndContextBound() throws Exception {
        Client a=new Client(maintainer);String body="[X] "+"并发🙂".repeat(4000);var f=build(a,body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_batch WHERE build_id=?",Integer.class,f.build())).isEqualTo(2);
        var c=ok(a.post("/collections",publication(currentId(),List.of(selection(f,"21","unicode","k","yes"))),UUID.randomUUID()));
        assertThat(((Number)c.get("entryCount")).intValue()).isGreaterThan(10);
        var hits=candidates(probe(a,c,"21","unicode","k",vector(0)));assertThat(hits).hasSize(5);
        int codePoints=0;
        for(var hit:hits) {
            int start=((Number)hit.get("startOffset")).intValue(),end=((Number)hit.get("endOffset")).intValue();
            assertThat(body.substring(start,end)).isEqualTo(hit.get("body"));
            codePoints+=body.codePointCount(start,end);
        }
        assertThat(codePoints).isLessThanOrEqualTo(5000);
    }

    @Test @Order(17) void stoppingPartialBuildPreservesAcceptedBatchAndFencesRemainingWork() throws Exception {
        Client a=new Client(maintainer);entered=new CountDownLatch(1);release=new CountDownLatch(1);
        var f=createBuild(a,approved(a,"A".repeat(10000)+"[SLOW_STOP] second batch"));
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForList("SELECT state FROM knowledge_index_batch WHERE build_id=? ORDER BY ordinal",String.class,f.build())).containsExactly("ACCEPTED","RUNNING");
        ok(a.post(versionPath(f)+"/stop",Map.of("digest",f.digest(),"reason","Synthetic partial stop"),UUID.randomUUID()));
        release.countDown();
        await(()->jdbc.queryForObject("SELECT count(*) FROM knowledge_index_attempt a JOIN knowledge_index_batch b ON b.id=a.batch_id WHERE b.build_id=? AND b.ordinal=1 AND a.usage_tokens=10",Integer.class,f.build())==1);
        assertThat(jdbc.queryForList("SELECT state FROM knowledge_index_batch WHERE build_id=? ORDER BY ordinal",String.class,f.build())).containsExactly("ACCEPTED","INVALIDATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_index_vector v JOIN knowledge_index_batch b ON b.id=v.batch_id WHERE b.build_id=?",Integer.class,f.build())).isEqualTo(10);
        assertThat(context.getBean(KnowledgeService.class).findApproved(f.version())).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods=false) static class FixtureConfig {
        @Bean @Primary EmbeddingGateway syntheticSupplier() {
            return new EmbeddingGateway() {
                public boolean available(){return true;}
                public Map<String,String> configuration(){return Map.of("provider","synthetic-fixture","model","qwen3.7-text-embedding-flash","dimension","1024","textType","document","outputType","dense");}
                public Response embed(List<String> texts,java.time.Instant deadline) {
                    CALLS.incrementAndGet();if(texts.getFirst().startsWith("[SLOW_STOP]")) {entered.countDown();try {if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic supplier gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                    return new Response(texts.stream().map(t->vector(t.startsWith("[Y]")?1:0)).toList(),"synthetic-"+UUID.randomUUID(),10L);
                }
            };
        }
        @Bean @Primary DataSource faultDataSource() { return new DelegatingDataSource(new DriverManagerDataSource(
                System.getenv("DB_URL")+"?currentSchema=app",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))) {
            @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
        }; }
        static Connection wrap(Connection real) {
            var publishing=new AtomicBoolean();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    if(method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        if(sql.startsWith("INSERT INTO knowledge_collection_request"))publishing.set(true);
                        if(sql.contains("AS similarity") && SEARCH_FAILURE.get())throw new SQLException("Synthetic search unavailable","08006");
                    }
                    Object result=method.invoke(real,args);
                    if(method.getName().equals("commit") && publishing.get() && COMMIT_LOSS.compareAndSet(true,false))throw new SQLException("Synthetic lost real commit acknowledgment","08006");
                    return result;
                } catch(InvocationTargetException ex){throw ex.getCause();}
            });
        }
    }
    record Account(UUID id,String login,String password) { }
    record Fixture(UUID root,UUID version,UUID build,String digest) { }
    static Account account(AccountAuthority role) {
        UUID id=UUID.randomUUID();String login="c."+id,password="synthetic-"+UUID.randomUUID();
        new JdbcAccountRepository(jdbc).insert(id,login,new PasswordHashes().encode(password),Set.of(role));return new Account(id,login,password);
    }
    static List<Double> vector(int axis) { var v=new ArrayList<Double>(Collections.nCopies(1024,0d));v.set(axis,1d);return v; }
    static String versionPath(Fixture f) { return "/documents/"+f.root()+"/versions/"+f.version(); }
    static Map<String,Object> approved(Client a,String body) throws Exception { var v=ok(a.post("/documents",KnowledgeMaintenanceIT.input(body),UUID.randomUUID()));approve(a,v);return v; }
    static void approve(Client a,Map<String,Object> v) throws Exception {ok(a.post("/documents/"+v.get("documentId")+"/versions/"+v.get("versionId")+"/review",Map.of("digest",v.get("digest"),"decision","APPROVE","reason","Explicit synthetic fixture review"),UUID.randomUUID()));}
    static Fixture createBuild(Client a,Map<String,Object> v) throws Exception {
        var response=ok(a.post("/documents/"+v.get("documentId")+"/versions/"+v.get("versionId")+"/index-builds",Map.of("digest",v.get("digest")),UUID.randomUUID()));
        return new Fixture(UUID.fromString(v.get("documentId").toString()),UUID.fromString(v.get("versionId").toString()),UUID.fromString(response.get("buildId").toString()),v.get("digest").toString());
    }
    static Fixture build(Client a,String body) throws Exception {var f=createBuild(a,approved(a,body));awaitSuccess(f);return f;}
    static void awaitSuccess(Fixture f) throws Exception {await(()->"SUCCEEDED".equals(jdbc.queryForObject("SELECT state FROM knowledge_index_build WHERE id=?",String.class,f.build())));}
    static UUID currentId(){return jdbc.queryForObject("SELECT collection_id FROM knowledge_collection_current WHERE singleton=1",UUID.class);}
    @SuppressWarnings("unchecked") static Map<String,Object> selection(Fixture f,String version,String scenario,String key,String value) throws Exception {
        var source=ok(new Client(maintainer).get("/index-builds/"+f.build()+"/publication-source"));
        var claims=((List<Map<String,Object>>)source.get("chunks")).stream().map(c->Map.<String,Object>of("chunkId",c.get("chunkId"),"claimKey",key,"claimValue",value,"quote",c.get("body").toString().strip(),"verificationNote","Explicit synthetic claim; no semantic quality assertion")).toList();
        return Map.of("buildId",f.build(),"conditions",Map.of("technology","Java","technologyVersion",version,"scenario",scenario),"claims",claims);
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> claims(Map<String,Object> s){return (List<Map<String,Object>>)s.get("claims");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> candidates(Map<String,Object> s){return (List<Map<String,Object>>)s.get("candidates");}
    static Map<String,Object> publication(UUID expected,List<Map<String,Object>> builds){var r=new LinkedHashMap<String,Object>();r.put("expectedCurrentCollectionId",expected);r.put("builds",builds);return r;}
    static Map<String,Object> probeInput(Map<String,Object> c,String version,String scenario,String key,List<Double> vector) {
        var r=new LinkedHashMap<String,Object>();r.put("configDigest",c.get("configDigest"));r.put("conditions",Map.of("technology","Java","technologyVersion",version,"scenario",scenario));r.put("claimKey",key);r.put("vector",vector);return r;
    }
    static Map<String,Object> probe(Client a,Map<String,Object> c,String version,String scenario,String key,List<Double> vector) throws Exception {return ok(a.post("/collections/"+c.get("collectionId")+"/probe",probeInput(c,version,scenario,key,vector),UUID.randomUUID()));}
    static void await(BooleanSupplier condition) throws Exception {for(int i=0;i<200;i++){if(condition.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError("Isolated condition timed out");}
    static Map<String,Object> json(HttpResponse<String> r){return org.springframework.boot.json.JsonParserFactory.getJsonParser().parseMap(r.body());}
    static Map<String,Object> ok(HttpResponse<String> r){assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return json(r);}
    static class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        UUID expected;String token;
        Client(Account a) throws Exception {
            if(a==null)return;expected=a.id();token=(String)json(getAbsolute("/api/auth/csrf")).get("token");
            var login=http.send(HttpRequest.newBuilder(base.resolve("/api/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",token)
                    .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(a.login(),StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(a.password(),StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);token=(String)json(getAbsolute("/api/auth/csrf")).get("token");
        }
        HttpResponse<String> getAbsolute(String path) throws Exception {return http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> get(String path) throws Exception {return getAbsolute(API+path);}
        HttpResponse<String> post(String path,Map<String,?> body,UUID operation) throws Exception {
            var b=HttpRequest.newBuilder(base.resolve(API+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("X-Operation-Id",operation.toString());
            if(expected!=null)b.header("X-Expected-Account-Id",expected.toString());if(token!=null)b.header("X-CSRF-TOKEN",token);
            return http.send(b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
