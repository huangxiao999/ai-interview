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
                // 服务保留待暂停身份；后续tick只核实暂停，不能因释放线程许可就自动接收。
                LOG.warn("Knowledge index worker step failed: {}",failure.getClass().getSimpleName());
            } finally { busy.set(false); }
        });
    }
    @PreDestroy void close() { execution.shutdownNow(); }
}
