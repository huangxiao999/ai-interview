package dev.interviewcoach.plan;

import dev.interviewcoach.knowledge.PlanEvidenceFacts;
import dev.interviewcoach.material.MaterialPlanFacts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import static dev.interviewcoach.plan.PlanPolicy.*;

/** M-03编辑规则；严格整数、确定性合计和固定知识资格，不调用模型估时。 */
final class PlanRevisionPolicy {
    static final String REVISION="plan-edit-v1";
    record TopicInput(UUID topicId,JsonNode order,PlanEvidenceFacts.Option coverage,JsonNode questionCount,
                      String difficulty,String reason,String resumeQuote,String jdQuote){ }
    record Save(UUID expectedRevisionId,List<TopicInput> topics){ }
    record Confirm(UUID expectedRevisionId){ }
    record Topic(UUID topicId,int order,String name,PlanEvidenceFacts.Option coverage,int questionCount,
                 String difficulty,String reason,String resumeQuote,String jdQuote,List<UUID> evidenceRefs){ }
    record Content(String revisionPolicy,int totalQuestions,List<Topic> topics,Integer estimatedMinutes){ }
    static int integer(JsonNode n){
        if(n==null || !n.isIntegralNumber() || !n.canConvertToInt())throw new Rejected("INVALID_INPUT","题量及顺序必须为整数");
        return n.intValue();
    }
    static String clean(String s,int max){String v=text(s,max).replace("\r\n","\n").replace('\r','\n');if(v.indexOf(0)>=0)throw new Rejected("INVALID_INPUT","文本含非法字符");return v;}
    static Save normalized(Save raw){
        if(raw==null || raw.expectedRevisionId()==null || raw.topics()==null || raw.topics().isEmpty() || raw.topics().size()>15)
            throw new Rejected("INVALID_INPUT","请固定原修订并填写1至15个有效主题");
        var ids=new HashSet<UUID>();var bindings=new HashSet<PlanEvidenceFacts.Option>();var result=new ArrayList<TopicInput>();int sum=0,position=0;
        for(var t:raw.topics()){
            if(t==null || t.topicId()==null || !ids.add(t.topicId()) || integer(t.order())!=++position || t.coverage()==null)
                throw new Rejected("INVALID_INPUT","主题身份或顺序重复、缺失或不连续");
            int count=integer(t.questionCount());if(count<1 || count>15)throw new Rejected("INVALID_INPUT","每个主题题量须为1至15的正整数");sum+=count;
            if(!List.of("BASIC","MEDIUM","ADVANCED").contains(t.difficulty()==null?"":t.difficulty()))throw new Rejected("INVALID_INPUT","请选择基础、中等或进阶");
            var b=t.coverage();if(b.collectionId()==null)throw new Rejected("INVALID_INPUT","请选择固定知识主题");
            var binding=new PlanEvidenceFacts.Option(b.collectionId(),clean(b.configDigest(),200),clean(b.technology(),200),clean(b.technologyVersion(),100),clean(b.scenario(),200),clean(b.claimKey(),200));
            if(!bindings.add(binding))throw new Rejected("INVALID_INPUT","同一知识主题不能重复分配，请合并题量");
            result.add(new TopicInput(t.topicId(),t.order(),binding,t.questionCount(),t.difficulty(),clean(t.reason(),2000),clean(t.resumeQuote(),1000),clean(t.jdQuote(),1000)));
        }
        if(sum<5 || sum>15)throw new Rejected("INVALID_INPUT","主问题合计必须为5至15道");
        return new Save(raw.expectedRevisionId(),List.copyOf(result));
    }
    static Content validate(Save input,MaterialPlanFacts.Snapshot materials,List<PlanEvidenceFacts.Coverage> allowed){
        var topics=new ArrayList<Topic>();int total=0;
        for(var t:input.topics()){
            var proof=allowed.stream().filter(c->c.binding().equals(t.coverage())).findFirst()
                    .orElseThrow(()->new Rejected("KNOWLEDGE_NOT_READY","主题不在原固定集合的已审核适用范围，或已停用/存在冲突"));
            if(!materials.resume().contains(t.resumeQuote()) || !materials.jd().contains(t.jdQuote()))throw new Rejected("INVALID_INPUT","安排依据必须来自本人的固定资料版本");
            int count=integer(t.questionCount());total+=count;
            topics.add(new Topic(t.topicId(),integer(t.order()),proof.name(),t.coverage(),count,t.difficulty(),t.reason(),t.resumeQuote(),t.jdQuote(),proof.evidenceRefs()));
        }
        return new Content(REVISION,total,List.copyOf(topics),null);
    }
    static boolean eligible(Content content,List<PlanEvidenceFacts.Coverage> allowed){
        return !content.topics().isEmpty() && content.topics().stream().allMatch(t->allowed.stream().anyMatch(c->c.binding().equals(t.coverage()) && c.evidenceRefs().containsAll(t.evidenceRefs()) && !t.evidenceRefs().isEmpty()));
    }
}
