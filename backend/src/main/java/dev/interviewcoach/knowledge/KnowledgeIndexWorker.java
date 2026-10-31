package dev.interviewcoach.knowledge;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只执行公共知识构建，一个本地有效句柄；挂起/超时不通过新线程绕开容量。 */
@Component
@Profile("local")
final class KnowledgeIndexWorker {
    private static final Logger LOG=LoggerFactory.getLogger(KnowledgeIndexWorker.class);
    private final KnowledgeIndexService service;
    private final ExecutorService execution=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private boolean recovered;
    KnowledgeIndexWorker(KnowledgeIndexService service) { this.service=service; }

    @Scheduled(fixedDelay=250)
    void tick() {
        if(!busy.compareAndSet(false,true)) return;
        execution.submit(()->{
            try {
                if(!recovered) { service.recoverAcceptance();recovered=true; }
                service.workOnce();
            } catch(RuntimeException failure) {
                // DB提交未知时不能重新外调；候选仍在则暂停接收，其他在途在期限/重启核实。
                LOG.warn("Knowledge index worker paused: {}",failure.getClass().getSimpleName());
            } finally { busy.set(false); }
        });
    }
    @PreDestroy void close() { execution.shutdownNow(); }
}
