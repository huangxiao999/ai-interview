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
import javax.net.ssl.SSLContext;
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
    private static final String MODEL = "qwen3.7-text-embedding-flash";
    private static final String REGION = "cn-beijing";
    private static final String NATIVE_PATH = "/api/v1/services/embeddings/text-embedding/text-embedding";
    private final URI endpoint;
    private final String key;
    private final String workspace;
    private final String region;
    private final ObjectMapper mapper;
    private final SSLContext loopbackTls;
    private final java.util.concurrent.ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"knowledge-http-deadline");t.setDaemon(true);return t;
    });

    @Autowired
    public AliyunEmbeddingGateway(Environment environment, ObjectMapper mapper) {
        this(connection(environment), mapper, null);
    }

    /** 仅供本机HTTP故障服务装配；生产必须走Environment构造及专属HTTPS端点校验。 */
    public AliyunEmbeddingGateway(URI endpoint, String key, String workspace, String region, ObjectMapper mapper) {
        this(endpoint, key, workspace, region, mapper, null);
    }

    /** 仅本机TLS故障替身可注入专用信任上下文；生产Environment装配始终用默认TLS校验。 */
    public AliyunEmbeddingGateway(URI endpoint, String key, String workspace, String region, ObjectMapper mapper,
            SSLContext loopbackTls) {
        this(loopback(endpoint) && present(key) && present(workspace) && present(region)
                && (!"https".equals(endpoint.getScheme()) || loopbackTls != null)
                ? new Connection(endpoint, key, workspace, region) : null, mapper, loopbackTls);
    }

    private AliyunEmbeddingGateway(Connection connection, ObjectMapper mapper, SSLContext loopbackTls) {
        this.endpoint = connection == null ? null : connection.endpoint;
        this.key = connection == null ? null : connection.key;
        this.workspace = connection == null ? null : connection.workspace;
        this.region = connection == null ? null : connection.region;
        this.mapper = mapper;
        this.loopbackTls = loopbackTls;
    }

    private static Connection connection(Environment environment) {
        // 默认关闭；缺失或非法配置均保持未就绪。关闭时不读取Key，更不因已有.env而外调。
        if (!"true".equals(environment.getProperty("BAILIAN_EMBEDDING_ENABLED", "false"))) return null;
        if (!JdkHttpTransportPolicy.enforced()) return null;
        String workspace = environment.getProperty("BAILIAN_EMBEDDING_WORKSPACE");
        String value = environment.getProperty("BAILIAN_EMBEDDING_ENDPOINT");
        if (!present(workspace) || !workspace.matches("[a-z0-9][a-z0-9-]{0,99}") || !present(value)) return null;
        try {
            var endpoint = URI.create(value);
            if (!"https".equals(endpoint.getScheme()) || endpoint.getPort() != -1
                    || !NATIVE_PATH.equals(endpoint.getRawPath()) || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null || endpoint.getRawUserInfo() != null
                    || !(workspace + "." + REGION + ".maas.aliyuncs.com").equals(endpoint.getHost())) return null;
            String key = environment.getProperty("DASHSCOPE_API_KEY");
            if (!present(key) || key.chars().anyMatch(c -> c <= 32 || c >= 127)) return null;
            return new Connection(endpoint, key, workspace, REGION);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static boolean loopback(URI endpoint) {
        return endpoint != null && ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                && "127.0.0.1".equals(endpoint.getHost()) && endpoint.getRawUserInfo() == null
                && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null;
    }

    @Override public boolean available() { return JdkHttpTransportPolicy.enforced()
            && endpoint != null && key != null && workspace != null && region != null; }

    @Override public Map<String, String> configuration() {
        // 这是本地协议修订，不冒充供应商不可变快照；密钥及启用开关不属于向量身份。
        return Map.of("provider", "aliyun-bailian", "model", MODEL, "dimension", "1024",
                "outputType", "dense", "adapterRevision", "dashscope-native-dense-v2",
                "textType", "document", "workspace", workspace == null ? "UNVERIFIED" : workspace,
                "region", region == null ? "UNVERIFIED" : region, "endpoint", endpoint == null ? "UNCONFIGURED" : endpoint.toString());
    }

    @Override public Response embed(List<String> texts, Instant deadline) {
        if (!available()) throw new IndexServiceUnavailable();
        if (texts == null || texts.isEmpty() || texts.size() > 10
                || texts.stream().anyMatch(text -> text == null || text.isEmpty()
                || text.codePointCount(0, text.length()) > 1000)) throw new IndexExternalFailure("INVALID_INPUT");
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isNegative() || left.isZero()) throw new IndexExternalFailure("DEADLINE");
        var builder = HttpClient.newBuilder().connectTimeout(left.compareTo(Duration.ofSeconds(5)) < 0 ? left : Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (loopbackTls != null) builder.sslContext(loopbackTls);
        var http = builder.build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(left);
        // 读取流的逐次超时不能代替绝对截止；到期主动关闭传输，仍不证明远端停止。
        var expiration=deadlines.schedule(http::shutdownNow,Math.max(1,left.toMillis()),TimeUnit.MILLISECONDS);
        // 单次显式POST，无SDK、重试拦截器或重新发送循环。响应体有界，不落临时文件。
        try (http) {
            return RestClient.builder().requestFactory(factory).build().post().uri(endpoint)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .body(Map.of("model", MODEL, "input", Map.of("texts", texts),
                            "parameters", Map.of("dimension", 1024, "text_type", "document")))
                    .exchange((request, response) -> {
                        byte[] bytes = response.getBody().readNBytes(1_048_577);
                        if (bytes.length > 1_048_576) throw new IndexExternalFailure("RESPONSE_LIMIT");
                        if (!response.getStatusCode().is2xxSuccessful())
                            throw new IndexExternalFailure("HTTP_" + response.getStatusCode().value(), readReceipt(bytes));
                        return parse(bytes, texts.size());
                    });
        } catch (IndexExternalFailure ex) { throw ex; }
        catch (RuntimeException ex) { throw new IndexExternalFailure("EXTERNAL_UNKNOWN"); }
        finally { expiration.cancel(false); }
    }

    private Response parse(byte[] bytes, int count) {
        Receipt receipt = Receipt.UNKNOWN;
        try {
            var root = mapper.readTree(bytes);
            receipt = receipt(root);
            if (root == null || !root.isObject() || root.has("code") || root.has("error"))
                throw new IndexExternalFailure("INVALID_RESPONSE");
            // 当前原生文档示例为顶层embeddings；兼容原生HTTP的output封装，不接受双重歧义。
            boolean top = root.has("embeddings");
            if (top && root.path("output").has("embeddings")) throw new IndexExternalFailure("INVALID_RESPONSE");
            var embeddings = top ? root.path("embeddings") : root.path("output").path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != count) throw new IndexExternalFailure("INVALID_RESPONSE");
            var vectors = new ArrayList<List<Double>>();
            for (int i = 0; i < embeddings.size(); i++) {
                var item = embeddings.get(i);
                if (!item.path("index").isInt() || item.path("index").asInt() != i
                        || !item.path("embedding").isArray()) throw new IndexExternalFailure("INVALID_MAPPING");
                if (item.path("embedding").size() != KnowledgeIndexPolicy.DIMENSION)
                    throw new IndexExternalFailure("INVALID_DIMENSION");
                var vector = new ArrayList<Double>();
                boolean nonzero = false;
                for (var number : item.path("embedding")) {
                    if (!number.isNumber() || !Double.isFinite(number.asDouble())) throw new IndexExternalFailure("INVALID_VECTOR");
                    double value = number.asDouble();
                    nonzero |= value != 0;
                    vector.add(value);
                }
                if (!nonzero) throw new IndexExternalFailure("ZERO_VECTOR");
                vectors.add(List.copyOf(vector));
            }
            if (receipt.requestId() == null) throw new IndexExternalFailure("INVALID_RESPONSE");
            return new Response(List.copyOf(vectors), receipt.requestId(), receipt.usageTokens());
        } catch (IndexExternalFailure ex) { throw new IndexExternalFailure(ex.category, receipt); }
        catch (RuntimeException invalid) { throw new IndexExternalFailure("INVALID_RESPONSE", receipt); }
    }

    private Receipt readReceipt(byte[] bytes) {
        try { return receipt(mapper.readTree(bytes)); }
        catch (RuntimeException invalid) { return Receipt.UNKNOWN; }
    }

    private static Receipt receipt(tools.jackson.databind.JsonNode root) {
        if (root == null || !root.isObject()) return Receipt.UNKNOWN;
        var request = root.path("request_id");
        var usage = root.path("usage");
        Long total = tokens(usage.path("total_tokens"));
        if (usage.has("prompt_tokens") && !java.util.Objects.equals(total, tokens(usage.path("prompt_tokens")))) total = null;
        return new Receipt(request.isString() ? request.asText() : null, total);
    }

    private static Long tokens(tools.jackson.databind.JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : null;
    }

    // 不使用record，避免自动toString将认证秘密暴露到诊断输出。
    private static final class Connection {
        private final URI endpoint;
        private final String key;
        private final String workspace;
        private final String region;
        private Connection(URI endpoint, String key, String workspace, String region) {
            this.endpoint = endpoint; this.key = key; this.workspace = workspace; this.region = region;
        }
    }
    @PreDestroy public void close() { deadlines.shutdownNow(); }
}

class IndexServiceUnavailable extends RuntimeException { }
class IndexExternalFailure extends RuntimeException {
    final String category;
    final EmbeddingGateway.Receipt receipt;
    IndexExternalFailure(String category) { this(category, EmbeddingGateway.Receipt.UNKNOWN); }
    IndexExternalFailure(String category, EmbeddingGateway.Receipt receipt) { this.category = category; this.receipt = receipt; }
}
