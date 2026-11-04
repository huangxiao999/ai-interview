package dev.interviewcoach.plan;

import dev.interviewcoach.identity.AccountAuthority;
import dev.interviewcoach.identity.AccountWriteGuard;
import dev.interviewcoach.identity.Actor;
import dev.interviewcoach.material.MaterialPlanFacts;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** M-03本人历史发现，只有查询；复用原工作稿/操作/修订，不新建恢复状态或调用供应商。 */
@Service @Profile("local")
final class PlanHistoryService {
    private final JdbcTemplate jdbc;
    private final AccountWriteGuard accounts;
    private final MaterialPlanFacts materials;
    private final TransactionTemplate transaction;
    PlanHistoryService(JdbcTemplate jdbc,AccountWriteGuard accounts,MaterialPlanFacts materials,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.accounts=accounts;this.materials=materials;transaction=new TransactionTemplate(manager);transaction.setTimeout(5);
    }
    record Entry(UUID workspaceId,UUID createOperationId,UUID operationId,UUID resumeId,UUID resumeVersionId,UUID jdVersionId,
                 int resumeVersion,int jdVersion,OffsetDateTime createdAt,String state,String reason,boolean materialsCurrent,
                 UUID planId,UUID revisionId,boolean confirmed,String eligibility){ }
    record Page(List<Entry> items,String nextCursor){ }
    private record Position(OffsetDateTime at,UUID id){ }
    Page list(Actor observed,UUID root,String cursor,int limit){
        if(limit<1 || limit>50)throw invalid();var position=decode(cursor);
        return transaction.execute(s->{
            var actor=accounts.requireCurrent(observed);
            if(!actor.hasAuthority(AccountAuthority.TRIAL_USER))throw new AccessDeniedException("Trial user required");
            if(root!=null)materials.lockRoot(actor.accountId(),root,false);
            // DELETED与M-02屏障同事务提交；先按拥有者筛选，再经M-02公开能力重核可见性。
            var args=new ArrayList<Object>();args.add(actor.accountId());
            String filter="w.owner_id=? AND w.input IS NOT NULL AND w.reason IS DISTINCT FROM 'DELETED'";
            if(root!=null){filter+=" AND w.resume_id=?";args.add(root);}
            if(position!=null){filter+=" AND (w.created_at,w.id)<(?,?)";args.add(position.at());args.add(position.id());}
            args.add(limit+1);
            var rows=jdbc.queryForList("SELECT w.id,w.create_operation_id,w.operation_id,w.resume_id,w.created_at,w.state,w.reason, "
                    +"(w.input->>'resumeVersionId')::uuid AS resume_version_id,(w.input->>'jdVersionId')::uuid AS jd_version_id, "
                    +"p.id AS plan_id,p.current_revision_id,EXISTS(SELECT 1 FROM plan_revision_confirmation c "
                    +"WHERE c.plan_id=p.id AND c.revision_id=p.current_revision_id) AS confirmed "
                    +"FROM plan_workspace w LEFT JOIN interview_plan p ON p.workspace_id=w.id WHERE "+filter
                    +" ORDER BY w.created_at DESC,w.id DESC LIMIT ?",args.toArray());
            boolean more=rows.size()>limit;if(more)rows.removeLast();
            // 同一账号的创建/换版/删除共享账号锁；列表不拿知识锁或工作稿写锁，也不读候选。
            var facts=new HashMap<UUID,MaterialPlanFacts.History>();
            for(var row:rows.stream().sorted(Comparator.comparing(r->r.get("resume_id").toString())).toList())
                facts.put((UUID)row.get("id"),materials.history(actor.accountId(),(UUID)row.get("resume_id"),(UUID)row.get("resume_version_id"),(UUID)row.get("jd_version_id")));
            var items=new ArrayList<Entry>();
            for(var row:rows){var h=facts.get(row.get("id"));items.add(new Entry((UUID)row.get("id"),(UUID)row.get("create_operation_id"),(UUID)row.get("operation_id"),
                    (UUID)row.get("resume_id"),(UUID)row.get("resume_version_id"),(UUID)row.get("jd_version_id"),h.resumeVersion(),h.jdVersion(),
                    ((java.sql.Timestamp)row.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC),row.get("state").toString(),(String)row.get("reason"),h.current(),
                    (UUID)row.get("plan_id"),(UUID)row.get("current_revision_id"),(boolean)row.get("confirmed"),"ENGINEERING_ONLY"));}
            var last=items.isEmpty()?null:items.getLast();return new Page(List.copyOf(items),more?encode(last.createdAt(),last.workspaceId()):null);
        });
    }
    private static Position decode(String cursor){
        if(cursor==null)return null;
        try{if(cursor.length()>180)throw invalid();var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=2)throw invalid();return new Position(OffsetDateTime.parse(parts[0]),UUID.fromString(parts[1]));}
        catch(IllegalArgumentException | java.time.DateTimeException e){throw invalid();}
    }
    private static String encode(OffsetDateTime at,UUID id){return Base64.getUrlEncoder().withoutPadding().encodeToString((at+"|"+id).getBytes(StandardCharsets.UTF_8));}
    private static PlanPolicy.Rejected invalid(){return new PlanPolicy.Rejected("INVALID_INPUT","历史分页参数无效");}
}
