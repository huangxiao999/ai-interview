package dev.interviewcoach.execution;

import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 单活动进程内的实际外调许可；索引和首个计划用例共用，不能每个执行器各自拥有5份。 */
@Component @Profile("local")
public final class ExternalAiPermits {
    private final Semaphore permits=new Semaphore(5,true);
    public boolean acquire(){return permits.tryAcquire();}
    public void release(){permits.release();}
}
