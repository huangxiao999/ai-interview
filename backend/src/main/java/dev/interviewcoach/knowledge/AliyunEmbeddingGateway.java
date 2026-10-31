package dev.interviewcoach.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("local")
public final class AliyunEmbeddingGateway implements EmbeddingGateway {
    private final URI endpoint;
    private final String key;
    private final String workspace;
    private final String region;
    private final ObjectMapper mapper;
    private final java.util.concurrent.ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"knowledge-http-deadline");t.setDaemon(true);return t;
    });

    @Autowired
    public AliyunEmbeddingGateway(Environment environment, ObjectMapper mapper) {
        // 本轮没有生产启用开关。真实调用须先核实账号/留存/费用，再另行授权接入。
        this(null, null, null, null, mapper);
    }

    /** 显式外部适配装配；测试只传本机HTTP故障服务，不替换本地生产规则。 */
    public AliyunEmbeddingGateway(URI endpoint, String key, String workspace, String region, ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.key = key;
        this.workspace = workspace;
        this.region = region;
        this.mapper = mapper;
    }

    @Override public boolean available() { return endpoint != null && key != null && workspace != null && region != null; }

    @Override public Map<String, String> configuration() {
        return Map.of("provider", "aliyun-bailian", "model", "text-embedding-v4", "dimension", "1024",
                "textType", "document", "workspace", workspace == null ? "UNVERIFIED" : workspace,
                "region", region == null ? "UNVERIFIED" : region, "endpoint", endpoint == null ? "UNCONFIGURED" : endpoint.toString());
    }

    @Override public Response embed(List<String> texts, Instant deadline) {
        if (!available()) throw new IndexServiceUnavailable();
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isNegative() || left.isZero()) throw new IndexExternalFailure("DEADLINE");
        var http = HttpClient.newBuilder().connectTimeout(left.compareTo(Duration.ofSeconds(5)) < 0 ? left : Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(left);
        // 读取流的逐次超时不能代替绝对截止；到期主动关闭传输，仍不证明远端停止。
        var expiration=deadlines.schedule(http::shutdownNow,Math.max(1,left.toMillis()),TimeUnit.MILLISECONDS);
        // 单次显式POST，无SDK、重试拦截器或重新发送循环。响应体有界，不落临时文件。
        try (http) {
            return RestClient.builder().requestFactory(factory).build().post().uri(endpoint)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .body(Map.of("model", "text-embedding-v4", "input", Map.of("texts", texts),
                            "parameters", Map.of("dimension", 1024, "text_type", "document")))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw new IndexExternalFailure("HTTP_" + response.getStatusCode().value());
                        byte[] bytes = response.getBody().readNBytes(1_048_577);
                        if (bytes.length > 1_048_576) throw new IndexExternalFailure("RESPONSE_LIMIT");
                        var root = mapper.readTree(bytes);
                        var embeddings = root.path("output").path("embeddings");
                        if (!embeddings.isArray() || embeddings.size() != texts.size()) throw new IndexExternalFailure("INVALID_RESPONSE");
                        var vectors = new ArrayList<List<Double>>();
                        for (int i = 0; i < embeddings.size(); i++) {
                            var item = embeddings.get(i);
                            if (!item.path("index").isInt() || item.path("index").asInt() != i
                                    || !item.path("embedding").isArray()) throw new IndexExternalFailure("INVALID_MAPPING");
                            var vector = new ArrayList<Double>();
                            for (var number : item.path("embedding")) {
                                if (!number.isNumber()) throw new IndexExternalFailure("INVALID_VECTOR");
                                vector.add(number.asDouble());
                            }
                            vectors.add(List.copyOf(vector));
                        }
                        var usage = root.path("usage").path("total_tokens");
                        return new Response(List.copyOf(vectors), root.path("request_id").asText(""),
                                usage.isIntegralNumber() && usage.asLong() >= 0 ? usage.asLong() : null);
                    });
        } catch (IndexExternalFailure ex) { throw ex; }
        catch (RuntimeException ex) { throw new IndexExternalFailure("EXTERNAL_UNKNOWN"); }
        finally { expiration.cancel(false); }
    }
    @PreDestroy public void close() { deadlines.shutdownNow(); }
}

class IndexServiceUnavailable extends RuntimeException { }
class IndexExternalFailure extends RuntimeException {
    final String category;
    IndexExternalFailure(String category) { this.category = category; }
}
