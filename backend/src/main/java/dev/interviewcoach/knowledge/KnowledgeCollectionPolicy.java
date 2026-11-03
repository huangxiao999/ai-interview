package dev.interviewcoach.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 仅工程探测边界，未作真实20例定标；不提供运行时语义质量保证。 */
final class KnowledgeCollectionPolicy {
    static final String REVISION = "cosine-exact-k5-min0.7-max5000cp-engineering-v1";
    static final int TOP_K = 5;
    static final int MAX_CODE_POINTS = 5000;
    static final double MIN_SIMILARITY = 0.7;
    private KnowledgeCollectionPolicy() { }

    record Conditions(String technology, String technologyVersion, String scenario) {
        Conditions normalized() {
            return new Conditions(KnowledgeInput.text(technology,100), KnowledgeInput.text(technologyVersion,100),
                    KnowledgeInput.text(scenario,200));
        }
        boolean incomplete() { return technology==null || technology.isBlank() || technologyVersion==null
                || technologyVersion.isBlank() || scenario==null || scenario.isBlank(); }
    }
    record Claim(UUID chunkId, String claimKey, String claimValue, String quote, String verificationNote) {
        Claim normalized() {
            if(chunkId==null) throw new InvalidKnowledge("请固定片段身份");
            return new Claim(chunkId,KnowledgeInput.text(claimKey,200),KnowledgeInput.text(claimValue,500),
                    KnowledgeInput.text(quote,1000),KnowledgeInput.text(verificationNote,2000));
        }
    }
    record Selection(UUID buildId, Conditions conditions, List<Claim> claims) {
        Selection normalized() {
            if(buildId==null || conditions==null || claims==null || claims.isEmpty() || claims.size()>500)
                throw new InvalidKnowledge("请提供完整构建和人工核对的条件/片段清单");
            var normalized=new ArrayList<Claim>();var ids=new HashSet<UUID>();
            for(var c:claims) {
                if(c==null) throw new InvalidKnowledge("片段不能为null");
                var claim=c.normalized();if(!ids.add(claim.chunkId()))throw new InvalidKnowledge("片段身份重复");
                normalized.add(claim);
            }
            normalized.sort(Comparator.comparing(c->c.chunkId().toString()));
            return new Selection(buildId,conditions.normalized(),List.copyOf(normalized));
        }
    }
    record Publish(UUID expectedCurrentCollectionId, List<Selection> builds) {
        Publish normalized() {
            if(builds==null || builds.isEmpty() || builds.size()>20) throw new InvalidKnowledge("集合需包含1～20个完整构建");
            var normalized=new ArrayList<Selection>();var ids=new HashSet<UUID>();int size=0;
            for(var s:builds) {
                if(s==null)throw new InvalidKnowledge("构建不能为null");
                var selected=s.normalized();if(!ids.add(selected.buildId()))throw new InvalidKnowledge("构建重复");
                size+=selected.claims().size();normalized.add(selected);
            }
            if(size>500)throw new InvalidKnowledge("一次发布最多500个片段");
            normalized.sort(Comparator.comparing(s->s.buildId().toString()));
            return new Publish(expectedCurrentCollectionId,List.copyOf(normalized));
        }
    }
    record Probe(String configDigest, Conditions conditions, String claimKey, List<Double> vector) { }
    record Stop(String digest, String reason) { }

    /** pgvector使用float32；拒绝溢出及转换后全零，不能把浮点损失冒充有效查询。 */
    static String vector(List<Double> values) {
        if(values==null || values.size()!=1024)throw new InvalidKnowledge("向量必须为1024维");
        boolean nonzero=false;var numbers=new ArrayList<String>(1024);
        for(var number:values) {
            if(number==null || !Double.isFinite(number) || !Float.isFinite(number.floatValue()))
                throw new InvalidKnowledge("向量数值无效");
            float value=number.floatValue();nonzero|=value!=0; numbers.add(Float.toString(value));
        }
        if(!nonzero)throw new InvalidKnowledge("向量不能为零");
        return "["+String.join(",",numbers)+"]";
    }
}
