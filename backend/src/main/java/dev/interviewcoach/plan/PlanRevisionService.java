package dev.interviewcoach.plan;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import dev.interviewcoach.knowledge.PlanEvidenceFacts;
import dev.interviewcoach.material.MaterialPlanFacts;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static dev.interviewcoach.plan.PlanPolicy.*;

/** M-03保存/确认的唯一规则入口；不生成、不开始场次、不消费权益。 */
@Service @Profile("local")
final class PlanRevisionService {
    private final PlanRepository data;
    private final MaterialPlanFacts materials;
    private final PlanEvidenceFacts knowledge;
    private final AccountWriteGuard accounts;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    PlanRevisionService(PlanRepository data,MaterialPlanFacts materials,PlanEvidenceFacts knowledge,AccountWriteGuard accounts,
                        ObjectMapper mapper,org.springframework.jdbc.core.JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.data=data;this.materials=materials;this.knowledge=knowledge;this.accounts=accounts;this.mapper=mapper;this.jdbc=jdbc;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(5);
    }
    record Revision(UUID id,int sequence,UUID parentRevisionId,PlanRevisionPolicy.Content content,OffsetDateTime createdAt){ }
    record View(UUID planId,UUID workspaceId,UUID resumeId,UUID currentRevisionId,Revision revision,boolean current,
                boolean confirmed,UUID confirmationId,OffsetDateTime confirmedAt,boolean canEdit,boolean canConfirm,
                String eligibility,List<PlanEvidenceFacts.Coverage> options){ }
    private record Context(PlanRepository.Work work,Map<String,Object> plan,MaterialPlanFacts.Snapshot materials,List<PlanEvidenceFacts.Coverage> options){ }
    View current(Actor actor,UUID plan){return transaction.execute(s->{var c=lock(actor,actor.accountId(),plan);return view(c,(UUID)c.plan().get("current_revision_id"));});}
    View read(Actor actor,UUID plan,UUID revision){return transaction.execute(s->view(lock(actor,actor.accountId(),plan),revision));}
    View save(Actor actor,UUID expected,UUID plan,UUID operation,PlanRevisionPolicy.Save raw){
        var input=PlanRevisionPolicy.normalized(raw);String digest=hash(plan+":SAVE:"+mapper.writeValueAsString(input));
        return transaction.execute(s->{
            var c=lock(actor,expected,plan);var prior=request(actor,operation);
            if(prior!=null)return replay(c,prior,"SAVE",digest);
            writable(c,input.expectedRevisionId());
            var content=PlanRevisionPolicy.validate(input,c.materials(),c.options());
            int sequence=jdbc.queryForObject("SELECT revision_no FROM plan_revision WHERE plan_id=? AND id=?",Integer.class,plan,input.expectedRevisionId())+1;
            UUID id=UUID.randomUUID();
            data.write("INSERT INTO plan_revision(id,plan_id,revision_no,parent_revision_id,payload) VALUES(?,?,?,?,?::jsonb)",id,plan,sequence,input.expectedRevisionId(),mapper.writeValueAsString(content));
            if(data.write("UPDATE interview_plan SET current_revision_id=? WHERE id=? AND current_revision_id=?",id,plan,input.expectedRevisionId())!=1)throw conflict();
            data.write("INSERT INTO plan_revision_request(owner_id,operation_id,plan_id,revision_id,kind,input_digest) VALUES(?,?,?,?,'SAVE',?)",actor.accountId(),operation,plan,id,digest);
            return view(c,id);
        });
    }
    View confirm(Actor actor,UUID expected,UUID plan,UUID operation,PlanRevisionPolicy.Confirm input){
        if(input==null || input.expectedRevisionId()==null)throw new Rejected("INVALID_INPUT","请固定需要确认的已保存修订");
        String digest=hash(plan+":CONFIRM:"+input.expectedRevisionId());
        return transaction.execute(s->{
            var c=lock(actor,expected,plan);var prior=request(actor,operation);
            if(prior!=null)return replay(c,prior,"CONFIRM",digest);
            writable(c,input.expectedRevisionId());var revision=revision(c,input.expectedRevisionId());
            if(!PlanRevisionPolicy.eligible(revision.content(),c.options()))throw new Rejected("KNOWLEDGE_NOT_READY","当前修订包含已停用、冲突或不再适用的知识，不能确认");
            data.write("INSERT INTO plan_revision_confirmation(id,plan_id,revision_id) VALUES(?,?,?) ON CONFLICT(plan_id,revision_id) DO NOTHING",UUID.randomUUID(),plan,input.expectedRevisionId());
            data.write("INSERT INTO plan_revision_request(owner_id,operation_id,plan_id,revision_id,kind,input_digest) VALUES(?,?,?,?,'CONFIRM',?)",actor.accountId(),operation,plan,input.expectedRevisionId(),digest);
            return view(c,input.expectedRevisionId());
        });
    }
    View operation(Actor actor,UUID operation){
        return transaction.execute(s->{
            user(actor,actor.accountId());var prior=request(actor,operation);if(prior==null)throw missing();
            var c=lock(actor,actor.accountId(),(UUID)prior.get("plan_id"));return view(c,(UUID)prior.get("revision_id"));
        });
    }
    private Context lock(Actor actor,UUID expected,UUID planId){
        user(actor,expected);
        var row=jdbc.queryForList("SELECT p.* FROM interview_plan p JOIN plan_workspace w ON w.id=p.workspace_id WHERE p.id=? AND w.owner_id=?",planId,actor.accountId());
        if(row.isEmpty())throw missing();var plan=row.getFirst();var w=data.find((UUID)plan.get("workspace_id")).orElseThrow(PlanRevisionService::missing);
        // 与删除、生成接收共用顺序：账号→资料根→固定知识根→工作稿。禁止独立事务。
        var facts=materials.require(actor.accountId(),w.root(),w.input()==null?null:w.input().resumeVersionId(),w.input()==null?null:w.input().jdVersionId(),false);
        var options=knowledge.revisionCoverage(w.input().collectionId(),w.input().configDigest());data.lock(w.id());
        return new Context(w,plan,facts,options);
    }
    private void user(Actor observed,UUID expected){var actor=accounts.requireCurrent(observed);if(!actor.hasAuthority(AccountAuthority.TRIAL_USER))throw new AccessDeniedException("Trial user required");if(!actor.accountId().equals(expected))throw new Rejected("ACCOUNT_CHANGED","账号已变化");}
    private void writable(Context c,UUID expected){
        if(!c.materials().current())throw new Rejected("MATERIAL_CHANGED","资料已更新，需要生成新计划");
        if(!"SUCCEEDED".equals(c.work().state()) || !"ENGINEERING_ONLY".equals(c.plan().get("eligibility")))throw conflict();
        UUID current=jdbc.queryForObject("SELECT current_revision_id FROM interview_plan WHERE id=?",UUID.class,c.plan().get("id"));
        if(!expected.equals(current))throw new Rejected("REVISION_CONFLICT","计划已由另一窗口更新；请核实当前修订，未保存草稿已保留");
    }
    private Map<String,Object> request(Actor actor,UUID operation){var rows=jdbc.queryForList("SELECT * FROM plan_revision_request WHERE owner_id=? AND operation_id=?",actor.accountId(),operation);return rows.isEmpty()?null:rows.getFirst();}
    private View replay(Context c,Map<String,Object> old,String kind,String digest){
        if(!old.get("plan_id").equals(c.plan().get("id")) || !old.get("kind").equals(kind) || !old.get("input_digest").equals(digest))throw conflict();
        return view(c,(UUID)old.get("revision_id"));
    }
    private Revision revision(Context c,UUID id){
        var rows=jdbc.queryForList("SELECT * FROM plan_revision WHERE plan_id=? AND id=?",c.plan().get("id"),id);if(rows.isEmpty() || rows.getFirst().get("payload")==null)throw missing();var r=rows.getFirst();
        var tree=mapper.readTree(r.get("payload").toString());PlanRevisionPolicy.Content content;
        if(tree.has("revisionPolicy"))content=mapper.treeToValue(tree,PlanRevisionPolicy.Content.class);
        else{
            var original=mapper.treeToValue(tree,Draft.class);var topics=new ArrayList<PlanRevisionPolicy.Topic>();int order=0;var in=c.work().input();
            for(var t:original.topics()){
                int position=++order;UUID topic=UUID.nameUUIDFromBytes((c.plan().get("id")+":"+position).getBytes(StandardCharsets.UTF_8));
                topics.add(new PlanRevisionPolicy.Topic(topic,position,t.name(),new PlanEvidenceFacts.Option(in.collectionId(),in.configDigest(),in.technology(),in.technologyVersion(),in.scenario(),in.claimKey()),t.questionCount(),t.difficulty(),t.reason(),t.resumeQuote(),t.jdQuote(),t.evidenceRefs()));
            }
            content=new PlanRevisionPolicy.Content(PlanRevisionPolicy.REVISION,original.totalQuestions(),List.copyOf(topics),original.estimatedMinutes());
        }
        return new Revision(id,((Number)r.get("revision_no")).intValue(),(UUID)r.get("parent_revision_id"),content,((java.sql.Timestamp)r.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC));
    }
    private View view(Context c,UUID revision){
        var content=revision(c,revision);UUID plan=(UUID)c.plan().get("id");UUID current=jdbc.queryForObject("SELECT current_revision_id FROM interview_plan WHERE id=?",UUID.class,plan);
        var confirms=jdbc.queryForList("SELECT * FROM plan_revision_confirmation WHERE plan_id=? AND revision_id=?",plan,revision);var fact=confirms.isEmpty()?null:confirms.getFirst();
        boolean latest=revision.equals(current),edit="ENGINEERING_ONLY".equals(c.plan().get("eligibility"))&&latest&&c.materials().current()&&"SUCCEEDED".equals(c.work().state())&&!c.options().isEmpty();
        return new View(plan,c.work().id(),c.work().root(),current,content,latest,fact!=null,fact==null?null:(UUID)fact.get("id"),fact==null?null:((java.sql.Timestamp)fact.get("confirmed_at")).toInstant().atOffset(java.time.ZoneOffset.UTC),edit,edit&&fact==null&&PlanRevisionPolicy.eligible(content.content(),c.options()),c.plan().get("eligibility").toString(),c.options());
    }
    private static Rejected conflict(){return new Rejected("OPERATION_CONFLICT","原操作、目标或固定输入不一致");}
    private static Rejected missing(){return new Rejected("RESOURCE_UNAVAILABLE","计划修订或原操作不可访问");}
}
