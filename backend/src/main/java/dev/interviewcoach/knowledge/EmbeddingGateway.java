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

    /** 只含可独立校验的供应商报告事实；不表示向量合格、业务成功或账单已经确定。 */
    record Receipt(String requestId, Long usageTokens) {
        static final Receipt UNKNOWN = new Receipt(null, null);
        public Receipt {
            if (requestId == null || requestId.isBlank() || requestId.length() > 200
                    || requestId.chars().anyMatch(Character::isISOControl)) requestId = null;
            if (usageTokens != null && usageTokens < 0) usageTokens = null;
        }
        boolean hasFacts() { return requestId != null || usageTokens != null; }
    }
}
