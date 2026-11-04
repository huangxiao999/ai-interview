package dev.interviewcoach.plan;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository @Profile("local")
class PlanRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    PlanRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    record Work(UUID id,UUID owner,UUID root,UUID operation,String digest,PlanPolicy.Input input,int revision,String state,String reason,
                long generation,int initial,int automatic,int manual,int query,boolean manualMode,OffsetDateTime queued,OffsetDateTime deadline,
                String vector,String evidence,String prompt,long resumeSeq){ }
    record Attempt(UUID id,UUID workspace,String kind,int ordinal,String category,long generation,int revision,String state,OffsetDateTime deadline){ }
    record Stored(UUID id,UUID attempt,long generation,int revision,String payload,String state){ }
    Optional<Work> find(UUID id){return findWhere("id=?",id);}
    UUID findCreateOperation(UUID id){return jdbc.queryForObject("SELECT create_operation_id FROM plan_workspace WHERE id=?",UUID.class,id);}
    Optional<Work> findWhere(String predicate,Object...args){
        return jdbc.query("SELECT * FROM plan_workspace WHERE "+predicate,(r,n)->new Work(r.getObject("id",UUID.class),r.getObject("owner_id",UUID.class),
                r.getObject("resume_id",UUID.class),r.getObject("operation_id",UUID.class),r.getString("initial_digest"),
                r.getString("input")==null?null:mapper.readValue(r.getString("input"),PlanPolicy.Input.class),r.getInt("input_revision"),
                r.getString("state"),r.getString("reason"),r.getLong("generation"),r.getInt("initial_used"),r.getInt("auto_used"),r.getInt("manual_used"),
                r.getInt("query_used"),r.getBoolean("manual_mode"),r.getObject("queued_at",OffsetDateTime.class),r.getObject("deadline_at",OffsetDateTime.class),
                r.getString("query_vector"),r.getString("evidence"),r.getString("prompt"),r.getLong("resume_seq")),args).stream().findFirst();
    }
    void lock(UUID id){jdbc.queryForList("SELECT id FROM plan_workspace WHERE id=? FOR UPDATE",id);}
    OffsetDateTime now(){return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);}
    void limitLockWait(OffsetDateTime deadline){
        long millis=Math.max(1,Math.min(5000,java.time.Duration.between(now(),deadline).toMillis()));
        jdbc.queryForObject("SELECT set_config('lock_timeout',?,true)",String.class,millis+"ms");
    }
    int write(String sql,Object...args){return jdbc.update(sql,args);}
    List<UUID> ready(){return jdbc.queryForList("SELECT id FROM plan_workspace WHERE state IN ('QUEUED','CANDIDATE_READY') ORDER BY created_at LIMIT 5",UUID.class);}
    List<UUID> interrupted(){return jdbc.queryForList("SELECT id FROM plan_workspace WHERE state IN ('PREPARING_EVIDENCE','RUNNING','CANDIDATE_READY') ORDER BY created_at",UUID.class);}
    Optional<Attempt> attempt(UUID workspace){return jdbc.query("SELECT * FROM plan_external_attempt WHERE workspace_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
            (r,n)->new Attempt(r.getObject("id",UUID.class),r.getObject("workspace_id",UUID.class),r.getString("kind"),r.getInt("ordinal"),r.getString("category"),
                    r.getLong("generation"),r.getInt("input_revision"),r.getString("state"),r.getObject("deadline_at",OffsetDateTime.class)),workspace).stream().findFirst();}
    Optional<Stored> candidate(UUID workspace){return jdbc.query("SELECT * FROM plan_generation_candidate WHERE workspace_id=? AND state='STORED' ORDER BY created_at DESC LIMIT 1",
            (r,n)->new Stored(r.getObject("id",UUID.class),r.getObject("attempt_id",UUID.class),r.getLong("generation"),r.getInt("input_revision"),r.getString("payload"),r.getString("state")),workspace).stream().findFirst();}
    Optional<java.util.Map<String,Object>> plan(UUID workspace){return jdbc.queryForList("SELECT id,revision_id,payload,eligibility FROM interview_plan WHERE workspace_id=?",workspace).stream().findFirst();}
    Optional<tools.jackson.databind.JsonNode> fixedConfiguration(UUID workspace){return jdbc.query("SELECT confirmation::text FROM plan_input_revision WHERE workspace_id=? AND revision=1 AND confirmation IS NOT NULL",(r,n)->mapper.readTree(r.getString(1)),workspace).stream().findFirst();}
    List<java.util.Map<String,Object>> attempts(UUID workspace){return jdbc.queryForList("SELECT id,kind,ordinal,category,state,request_id,input_tokens,output_tokens,cost_unknown FROM plan_external_attempt WHERE workspace_id=? ORDER BY created_at,id",workspace);}
    List<UUID> graphs(UUID root){return jdbc.queryForList("SELECT id FROM plan_workspace WHERE resume_id=? ORDER BY id",UUID.class,root);}
    List<java.util.Map<String,Object>> cleanup(){return jdbc.queryForList("SELECT c.id,c.target_id,w.owner_id,w.resume_id,c.delete_request_id FROM material_cleanup_item c "
            +"JOIN plan_workspace w ON w.id=c.target_id WHERE c.target_type='PLAN_PRIVATE_GRAPH' AND c.state='PENDING' ORDER BY w.cleanup_attempted_at NULLS FIRST,c.id LIMIT 5");}
    int count(String sql,Object...args){return jdbc.queryForObject(sql,Integer.class,args);}
}
