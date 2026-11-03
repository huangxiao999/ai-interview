package dev.interviewcoach.plan;

import dev.interviewcoach.material.MaterialPlanFacts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlanPolicyTest {
    final UUID reference=UUID.randomUUID();
    final PlanPolicy.Input input=new PlanPolicy.Input(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixed","Java","21","jmm","visible",true,true,true);
    final MaterialPlanFacts.Snapshot material=new MaterialPlanFacts.Snapshot("线程池项目","Java 后端",true);
    PlanPolicy.Draft plan(int count,String difficulty,UUID source,String quote){return new PlanPolicy.Draft(count,List.of(new PlanPolicy.Topic("Java",count,difficulty,"围绕线程池项目及岗位安排",quote,"Java 后端",List.of(source))),30);}
    @Test void acceptsExactDefaultContract(){assertThatCode(()->PlanPolicy.validate(plan(8,"MEDIUM",reference,"线程池项目"),input,material,List.of(reference))).doesNotThrowAnyException();}
    @Test void rejectsModelChangingQuestionCount(){assertThatThrownBy(()->PlanPolicy.validate(plan(7,"MEDIUM",reference,"线程池项目"),input,material,List.of(reference))).isInstanceOf(PlanPolicy.Rejected.class);}
    @Test void rejectsDifferentDifficulty(){assertThatThrownBy(()->PlanPolicy.validate(plan(8,"ADVANCED",reference,"线程池项目"),input,material,List.of(reference))).isInstanceOf(PlanPolicy.Rejected.class);}
    @Test void rejectsInventedSource(){assertThatThrownBy(()->PlanPolicy.validate(plan(8,"MEDIUM",UUID.randomUUID(),"线程池项目"),input,material,List.of(reference))).isInstanceOf(PlanPolicy.Rejected.class);}
    @Test void rejectsInventedExperience(){assertThatThrownBy(()->PlanPolicy.validate(plan(8,"MEDIUM",reference,"不存在的支付项目"),input,material,List.of(reference))).isInstanceOf(PlanPolicy.Rejected.class);}
}
