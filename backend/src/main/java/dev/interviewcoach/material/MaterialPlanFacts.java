package dev.interviewcoach.material;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** M-02资料及内容核对的窄能力；调用者先持账号锁，所有方法参加调用者当前事务。 */
@Service @Profile("local")
public final class MaterialPlanFacts {
    private final ResumeRepository materials;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    MaterialPlanFacts(ResumeRepository materials, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.materials=materials; this.jdbc=jdbc; this.mapper=mapper;
    }
    public record Snapshot(String resume, String jd, boolean current) { }
    public record History(int resumeVersion, int jdVersion, boolean current) { }
    /** 历史发现只投影版本身份，不加载/复制正文；调用者先持本人账号锁，资料根按稳定顺序核实。 */
    public History history(UUID owner, UUID root, UUID resumeVersion, UUID jdVersion) {
        lockRoot(owner,root,false);
        return jdbc.query("SELECT r.version_no AS resume_no,j.version_no AS jd_no, "
                +"r.id=(SELECT id FROM material_resume_version WHERE resume_id=? ORDER BY version_no DESC LIMIT 1) "
                +"AND j.id=(SELECT id FROM material_jd_version WHERE resume_id=? ORDER BY version_no DESC LIMIT 1) AS current "
                +"FROM material_resume_version r JOIN material_jd_version j ON j.resume_id=r.resume_id "
                +"WHERE r.resume_id=? AND r.id=? AND j.id=?",
                (r,n)->new History(r.getInt("resume_no"),r.getInt("jd_no"),r.getBoolean("current")),root,root,root,resumeVersion,jdVersion)
                .stream().findFirst().orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE"));
    }
    public record Finding(String kind, String source, String quote) { }
    public static final class Rejected extends RuntimeException {
        public final String code;
        Rejected(String code){super(code);this.code=code;}
    }
    public boolean lockRoot(UUID owner,UUID root,boolean allowDeleted){
        transaction();
        var r=materials.lockRoot(root).orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE"));
        if(!owner.equals(r.ownerId()) || (!allowDeleted && !"ACTIVE".equals(r.state())))throw new Rejected("RESOURCE_UNAVAILABLE");
        return "ACTIVE".equals(r.state());
    }
    public Snapshot require(UUID owner, UUID root, UUID resumeVersion, UUID jdVersion, boolean latest) {
        transaction();
        lockRoot(owner,root,false);
        var resume=materials.detail(owner,root,resumeVersion).orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE"));
        var jd=materials.jdDetail(owner,root,jdVersion,null).orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE"));
        boolean current=materials.detail(owner,root,null).orElseThrow().versionId().equals(resumeVersion)
                && materials.versions(owner,root,true).stream().findFirst().orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE")).versionId().equals(jdVersion);
        if(latest && !current) throw new Rejected("MATERIAL_CHANGED");
        return new Snapshot(resume.body(),jd.body(),current);
    }
    public void open(UUID owner, UUID id, UUID root, UUID resume, UUID jd, boolean confirmed) {
        var facts=require(owner,root,resume,jd,true);
        if(!confirmed || facts.resume().codePoints().distinct().count()<5 || facts.jd().codePoints().distinct().count()<5)
            throw new Rejected("INVALID_INPUT");
        jdbc.update("INSERT INTO material_plan_review(id,resume_id,resume_version_id,jd_version_id,confirmation) VALUES(?,?,?,?,?::jsonb)",
                id,root,resume,jd,"{\"materialsConfirmed\":true}");
    }
    /** 模型发现必须有真实固定原文定位；模型不能替用户确认冲突或受限覆盖。 */
    public String findings(UUID id, Snapshot input, String decision, List<Finding> findings) {
        transaction();
        if(decision==null || !List.of("CLEAR","REVIEW_REQUIRED","INVALID","OUT_OF_SCOPE").contains(decision)
                || findings==null || findings.size()>20 || ("CLEAR".equals(decision) && !findings.isEmpty()))
            throw new Rejected("INVALID_CANDIDATE");
        if(!"CLEAR".equals(decision) && findings.isEmpty()) throw new Rejected("INVALID_CANDIDATE");
        for(var f:findings) {
            if(f==null || f.kind()==null || f.kind().isBlank() || f.kind().length()>200 || f.quote()==null || f.quote().isBlank() || f.quote().length()>1000
                    || !("RESUME".equals(f.source()) ? input.resume() : "JD".equals(f.source()) ? input.jd() : "").contains(f.quote()))
                throw new Rejected("INVALID_CANDIDATE");
        }
        String json=mapper.writeValueAsString(findings); String digest;
        try { digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        jdbc.update("UPDATE material_plan_review SET state=CASE WHEN state='CONFIRMED' AND finding_digest=? AND ?='REVIEW_REQUIRED' "
                +"THEN 'CONFIRMED' ELSE ? END,findings=?::jsonb,finding_digest=? WHERE id=?",digest,decision,decision,json,digest,id);
        return digest;
    }
    public void confirm(UUID id, String digest) {
        transaction();
        int changed=jdbc.update("UPDATE material_plan_review SET state='CONFIRMED',confirmation=jsonb_build_object('findingDigest',finding_digest) "
                +"WHERE id=? AND state='REVIEW_REQUIRED' AND finding_digest=?",id,digest);
        if(changed!=1) throw new Rejected("OPERATION_CONFLICT");
    }
    public JsonNode review(UUID id) {
        transaction();
        return jdbc.query("SELECT jsonb_build_object('state',state,'digest',finding_digest,'findings',findings)::text FROM material_plan_review WHERE id=?",
                (r,n)->mapper.readTree(r.getString(1)),id).stream().findFirst().orElseThrow(()->new Rejected("RESOURCE_UNAVAILABLE"));
    }
    private static void transaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Material facts require shared transaction");
    }
}
