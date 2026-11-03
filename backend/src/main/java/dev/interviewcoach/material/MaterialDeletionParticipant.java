package dev.interviewcoach.material;

import java.util.UUID;

/** 在M-02原删除事务中参与；实现者不得自行提交、外调或删除其他模块仓储。 */
public interface MaterialDeletionParticipant {
    void invalidateAndRegister(UUID owner, UUID root, UUID deletion);
    Impact impact(UUID owner, UUID root);
    record Impact(int plans, int operations, int candidates) { }
}
