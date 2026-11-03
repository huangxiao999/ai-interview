package dev.interviewcoach.plan;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单活动应用执行者；最多5个本地上下文，真实外调另受共享5许可限制。 */
@Component @Profile("local")
final class PlanWorker {
    private static final Logger LOG=LoggerFactory.getLogger(PlanWorker.class);
    private final PlanService service;
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService execution=Executors.newFixedThreadPool(5);
    PlanWorker(PlanService service){this.service=service;}
    @Scheduled(fixedDelay=250)
    synchronized void tick(){
        try{
            if(!service.reconcile())return;
            service.cleanup();
            for(var id:service.ready()){
                if(active.size()>=5)break;
                if(!active.add(id))continue;
                execution.submit(()->{try{service.work(id);}catch(RuntimeException failure){LOG.warn("Plan step requires verification: {}",failure.getClass().getSimpleName());}finally{active.remove(id);}});
            }
        }catch(RuntimeException failure){LOG.warn("Plan recovery/cleanup requires verification: {}",failure.getClass().getSimpleName());}
    }
    @PreDestroy void close(){execution.shutdownNow();}
}
