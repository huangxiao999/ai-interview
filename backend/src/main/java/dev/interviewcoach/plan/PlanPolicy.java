package dev.interviewcoach.plan;

import dev.interviewcoach.material.MaterialPlanFacts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class PlanPolicy {
    static final String MODEL="qwen3.8-flash";
    static final String CONFIG="bailian-beijing-qwen3.8-flash-plan-json-v1-max4096";
    static final String QUERY_CONFIG="qwen3.7-text-embedding-flash-query-1024-dense-local-contract-v1";
    static final String POLICY="plan60-queue20-initial1-auto1-manual2-v1";
    record Input(UUID resumeId,UUID resumeVersionId,UUID jdVersionId,UUID collectionId,String configDigest,
                 String technology,String technologyVersion,String scenario,String claimKey,
                 boolean materialsConfirmed,boolean restrictedCoverageConfirmed,boolean newPlanIntent) {
        Input checked(){
            if(resumeId==null || resumeVersionId==null || jdVersionId==null || collectionId==null
                    || !materialsConfirmed || !restrictedCoverageConfirmed || !newPlanIntent)
                throw new Rejected("INVALID_INPUT","请固定资料版本并确认资料、受限知识范围及新计划意图");
            return new Input(resumeId,resumeVersionId,jdVersionId,collectionId,text(configDigest,200),text(technology,200),
                    text(technologyVersion,100),text(scenario,200),text(claimKey,200),true,true,true);
        }
    }
    record Resume(String action,boolean acceptUnknownCost,String findingDigest,UUID evidenceCollectionId,String evidenceConfigDigest){ }
    record Topic(String name,int questionCount,String difficulty,String reason,String resumeQuote,String jdQuote,List<UUID> evidenceRefs){ }
    record Draft(int totalQuestions,List<Topic> topics,Integer estimatedMinutes){ }
    record Candidate(String materialDecision,List<MaterialPlanFacts.Finding> findings,Draft plan){ }
    static String text(String s,int max){
        if(s==null || s.isBlank() || s.codePointCount(0,s.length())>max)throw new Rejected("INVALID_INPUT","计划输入不完整或超限");
        return s.strip();
    }
    static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static void validate(Draft plan,Input input,MaterialPlanFacts.Snapshot materials,List<UUID> allowed){
        if(plan==null || plan.totalQuestions()!=8 || plan.topics()==null || plan.topics().isEmpty() || plan.topics().size()>8)
            throw new Rejected("INVALID_CANDIDATE","默认计划必须为8道主问题");
        int total=0;
        var names=new java.util.HashSet<String>();
        for(var topic:plan.topics()){
            if(topic==null || !input.technology().equals(topic.name()) || !names.add(topic.name()) || topic.questionCount()<1 || topic.questionCount()>8
                    || !"MEDIUM".equals(topic.difficulty()) || topic.evidenceRefs()==null || topic.evidenceRefs().isEmpty()
                    || !allowed.containsAll(topic.evidenceRefs()))throw new Rejected("INVALID_CANDIDATE","主题、难度、分配或来源不符合固定范围");
            text(topic.reason(),2000);text(topic.resumeQuote(),1000);text(topic.jdQuote(),1000);
            if(!materials.resume().contains(topic.resumeQuote()) || !materials.jd().contains(topic.jdQuote()))
                throw new Rejected("INVALID_CANDIDATE","安排依据不是本人的固定资料");
            total+=topic.questionCount();
        }
        if(total!=8 || (plan.estimatedMinutes()!=null && plan.estimatedMinutes()<=0))throw new Rejected("INVALID_CANDIDATE","计划合计或时长无效");
    }
    static final class Rejected extends RuntimeException{
        final String code;
        Rejected(String code,String message){super(message);this.code=code;}
    }
}
