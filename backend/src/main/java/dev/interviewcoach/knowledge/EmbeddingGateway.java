package dev.interviewcoach.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 只替代外部供应商，不决定审核、尝试预算、事务或构建成功。 */
public interface EmbeddingGateway {
    boolean available();
    Map<String, String> configuration();
    Response embed(List<String> texts, Instant deadline);
    record Response(List<List<Double>> vectors, String requestId, Long usageTokens) { }
}
