package dev.interviewcoach.knowledge;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunEmbeddingGatewayTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String ENDPOINT = "https://ws-fixture.cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    @Test void disabledDoesNotReadCredentialsOrPermitDispatch() {
        var env = new MockEnvironment() {
            @Override public String getProperty(String key) {
                if (key.equals("DASHSCOPE_API_KEY")) throw new AssertionError("Disabled gateway must not read Key");
                return super.getProperty(key);
            }
        };
        var gateway = new AliyunEmbeddingGateway(env, JSON);
        try {
            assertThat(gateway.available()).isFalse();
            assertThatThrownBy(() -> gateway.embed(List.of("synthetic"), Instant.now().plusSeconds(1)))
                    .isInstanceOf(IndexServiceUnavailable.class);
        } finally { gateway.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-key", "blank-key", "linebreak-key", "control-key", "non-ascii-key", "missing-workspace", "missing-endpoint",
            "base-url", "compatible-url", "http", "userinfo", "query", "fragment", "port", "foreign-host",
            "wrong-workspace", "wrong-region", "invalid-uri", "invalid-flag", "false"})
    void incompleteOrUnsafeConfigurationStaysUnavailable(String kind) {
        var env = enabled();
        switch (kind) {
            case "missing-key" -> env = new MockEnvironment().withProperty("BAILIAN_EMBEDDING_ENABLED", "true")
                    .withProperty("BAILIAN_EMBEDDING_WORKSPACE", "ws-fixture").withProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT);
            case "blank-key" -> env.setProperty("DASHSCOPE_API_KEY", "  ");
            case "linebreak-key" -> env.setProperty("DASHSCOPE_API_KEY", "synthetic\nkey");
            case "control-key" -> env.setProperty("DASHSCOPE_API_KEY", "synthetic" + (char) 0 + "key");
            case "non-ascii-key" -> env.setProperty("DASHSCOPE_API_KEY", "synthetic密钥");
            case "missing-workspace" -> env.setProperty("BAILIAN_EMBEDDING_WORKSPACE", "");
            case "missing-endpoint" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", "");
            case "base-url" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", "https://ws-fixture.cn-beijing.maas.aliyuncs.com/api/v1");
            case "compatible-url" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT.replace("/api/v1/services/embeddings/text-embedding/text-embedding", "/compatible-mode/v1/embeddings"));
            case "http" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT.replace("https:", "http:"));
            case "userinfo" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT.replace("https://", "https://credential@"));
            case "query" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT + "?key=do-not-store");
            case "fragment" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT + "#fragment");
            case "port" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT.replace(".com/", ".com:443/"));
            case "foreign-host" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT.replace("maas.aliyuncs.com", "example.test"));
            case "wrong-workspace" -> env.setProperty("BAILIAN_EMBEDDING_WORKSPACE", "ws-other");
            case "wrong-region" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT.replace("cn-beijing", "us-east-1"));
            case "invalid-uri" -> env.setProperty("BAILIAN_EMBEDDING_ENDPOINT", "not a URI");
            case "invalid-flag" -> env.setProperty("BAILIAN_EMBEDDING_ENABLED", "yes");
            case "false" -> env.setProperty("BAILIAN_EMBEDDING_ENABLED", "false");
            default -> throw new AssertionError(kind);
        }
        var gateway = new AliyunEmbeddingGateway(env, JSON);
        try {
            assertThat(gateway.available()).isFalse();
            assertThat(gateway.configuration().toString()).doesNotContain("credential", "do-not-store", "synthetic-key");
        } finally { gateway.close(); }
    }

    @Test void configurationPinsModelAndProtocolButNotAuthenticationSecret() {
        var one = new AliyunEmbeddingGateway(enabled(), JSON);
        var two = new AliyunEmbeddingGateway(enabled().withProperty("DASHSCOPE_API_KEY", "rotated-synthetic-key"), JSON);
        try {
            assertThat(one.available()).isTrue();assertThat(two.available()).isTrue();
            assertThat(one.configuration()).isEqualTo(two.configuration()).containsEntry("model", "qwen3.7-text-embedding-flash")
                    .containsEntry("dimension", "1024").containsEntry("textType", "document").containsEntry("outputType", "dense")
                    .containsEntry("adapterRevision", "dashscope-native-dense-v2").containsEntry("region", "cn-beijing");
            assertThat(one.configuration().toString()).doesNotContain("synthetic-key", "rotated-synthetic-key");
        } finally { one.close();two.close(); }
    }

    @Test void springImportsSyntheticDotEnvAsPropertiesAndRespectsExplicitDisable(@TempDir Path directory) throws Exception {
        var dotEnv = directory.resolve(".env");
        Files.writeString(dotEnv, "BAILIAN_EMBEDDING_ENABLED=true\nDASHSCOPE_API_KEY=synthetic-key\nBAILIAN_EMBEDDING_WORKSPACE=ws-fixture\nBAILIAN_EMBEDDING_ENDPOINT=" + ENDPOINT + "\n", StandardCharsets.UTF_8);
        var config = directory.resolve("gateway.properties");
        Files.writeString(config, "spring.config.import=" + dotEnv.toUri().toASCIIString() + "[.properties]\n", StandardCharsets.UTF_8);
        var runner = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=" + config.toUri().toASCIIString(), "spring.profiles.active=local")
                .withUserConfiguration(GatewayConfiguration.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AliyunEmbeddingGateway.class).available()).isTrue();
            assertThat(context.getBean(AliyunEmbeddingGateway.class).configuration()).containsEntry("endpoint", ENDPOINT);
        });
        runner.withPropertyValues("BAILIAN_EMBEDDING_ENABLED=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AliyunEmbeddingGateway.class).available()).isFalse();
        });
    }

    @Test void engineeringInputAndExpiredDeadlineAreRejectedBeforeNetwork() {
        var gateway = new AliyunEmbeddingGateway(URI.create("http://127.0.0.1:1/unused"), "synthetic-key", "fixture", "fixture", JSON);
        try {
            for (var texts : List.of(List.<String>of(), List.of(""), List.of("😀".repeat(1001)), Collections.nCopies(11, "x"))) {
                assertThatThrownBy(() -> gateway.embed(texts, Instant.now().plusSeconds(2)))
                        .isInstanceOfSatisfying(IndexExternalFailure.class, failure -> assertThat(failure.category).isEqualTo("INVALID_INPUT"));
            }
            assertThatThrownBy(() -> gateway.embed(List.of("synthetic"), Instant.now().minusSeconds(1)))
                    .isInstanceOfSatisfying(IndexExternalFailure.class, failure -> assertThat(failure.category).isEqualTo("DEADLINE"));
        } finally { gateway.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"top", "wrapped", "usage-missing", "usage-negative", "usage-fraction", "usage-overflow",
            "usage-contradiction", "usage-prompt-only", "usage-invalid-prompt", "mapping", "dimension", "zero", "nonfinite",
            "partial", "bad-request-id", "missing-request-id", "both-shapes", "compatible-shape", "error", "malformed",
            "429", "503", "redirect", "drop", "oversize"})
    void nativeProtocolUsesOneExplicitPostAndRejectsUnusableResults(String kind) throws Exception {
        var calls = new AtomicInteger();
        var request = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var response = payload(kind);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embedding", exchange -> {
            calls.incrementAndGet();request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (kind.equals("drop")) { exchange.close();return; }
            int status = kind.equals("429") ? 429 : kind.equals("503") ? 503 : kind.equals("redirect") ? 307 : 200;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/embedding");
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            finally { exchange.close(); }
        });
        server.start();
        var gateway = new AliyunEmbeddingGateway(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embedding"), "synthetic-key", "fixture", "fixture", JSON);
        try {
            boolean success = kind.equals("top") || kind.equals("wrapped") || kind.startsWith("usage-");
            if (success) {
                var result = gateway.embed(List.of("synthetic"), Instant.now().plusSeconds(5));
                assertThat(result.requestId()).isEqualTo("synthetic-request");
                assertThat(result.vectors()).hasSize(1);assertThat(result.vectors().getFirst()).hasSize(1024);
                if (kind.startsWith("usage-")) assertThat(result.usageTokens()).isNull();
                else assertThat(result.usageTokens()).isEqualTo(12L);
            } else assertThatThrownBy(() -> gateway.embed(List.of("synthetic"), Instant.now().plusSeconds(5)))
                    .isInstanceOfSatisfying(IndexExternalFailure.class, failure -> {
                        if (List.of("dimension", "mapping", "zero", "partial", "both-shapes", "compatible-shape", "error", "429", "503", "redirect", "nonfinite").contains(kind)) {
                            assertThat(failure.receipt.requestId()).isEqualTo("synthetic-request");
                            assertThat(failure.receipt.usageTokens()).isEqualTo(12L);
                        }
                        if (kind.equals("drop") || kind.equals("malformed") || kind.equals("oversize")) {
                            assertThat(failure.receipt.requestId()).isNull();assertThat(failure.receipt.usageTokens()).isNull();
                        }
                        if (kind.equals("bad-request-id") || kind.equals("missing-request-id")) {
                            assertThat(failure.receipt.requestId()).isNull();assertThat(failure.receipt.usageTokens()).isEqualTo(12L);
                        }
                    });
            assertThat(calls.get()).as("No hidden retries or redirects: %s", kind).isEqualTo(1);
            assertThat(authorization.get()).isEqualTo("Bearer synthetic-key");
            var sent = JSON.readTree(request.get());
            assertThat(sent.path("model").asText()).isEqualTo("qwen3.7-text-embedding-flash");
            assertThat(sent.path("input").path("texts").get(0).asText()).isEqualTo("synthetic");
            assertThat(sent.path("parameters").path("dimension").asInt()).isEqualTo(1024);
            assertThat(sent.path("parameters").path("text_type").asText()).isEqualTo("document");
            assertThat(sent.path("parameters").size()).isEqualTo(2); // 不发送文档未明确支持的sparse/output_type参数。
        } finally { gateway.close();server.stop(0); }
    }

    private static MockEnvironment enabled() {
        return new MockEnvironment().withProperty("BAILIAN_EMBEDDING_ENABLED", "true")
                .withProperty("DASHSCOPE_API_KEY", "synthetic-key").withProperty("BAILIAN_EMBEDDING_WORKSPACE", "ws-fixture")
                .withProperty("BAILIAN_EMBEDDING_ENDPOINT", ENDPOINT);
    }

    private static byte[] payload(String kind) {
        var vector = new ArrayList<Double>(Collections.nCopies(kind.equals("dimension") ? 2 : 1024, 0d));
        vector.set(0, kind.equals("zero") ? 0d : 1d);
        var item = Map.of("index", kind.equals("mapping") ? 1 : 0, "embedding", vector);
        var embeddings = kind.equals("partial") ? List.of() : List.of(item);
        var root = new LinkedHashMap<String, Object>();
        root.put("embeddings", embeddings);root.put("request_id", "synthetic-request");
        root.put("usage", Map.of("prompt_tokens", 12, "total_tokens", 12));
        switch (kind) {
            case "wrapped" -> { root.remove("embeddings");root.put("output", Map.of("embeddings", embeddings)); }
            case "usage-missing" -> root.remove("usage");
            case "usage-negative" -> root.put("usage", Map.of("total_tokens", -1));
            case "usage-fraction" -> root.put("usage", Map.of("total_tokens", 1.5));
            case "usage-overflow" -> root.put("usage", Map.of("total_tokens", new java.math.BigInteger("9223372036854775808")));
            case "usage-contradiction" -> root.put("usage", Map.of("prompt_tokens", 11, "total_tokens", 12));
            case "usage-prompt-only" -> root.put("usage", Map.of("prompt_tokens", 12));
            case "usage-invalid-prompt" -> root.put("usage", Map.of("prompt_tokens", "12", "total_tokens", 12));
            case "bad-request-id" -> root.put("request_id", "bad\nrequest");
            case "missing-request-id" -> root.remove("request_id");
            case "both-shapes" -> root.put("output", Map.of("embeddings", embeddings));
            case "compatible-shape" -> { root.remove("embeddings");root.put("data", embeddings); }
            case "error" -> root.put("code", "SyntheticError");
            case "malformed" -> { return "{invalid".getBytes(StandardCharsets.UTF_8); }
            case "oversize" -> { return " ".repeat(1_048_577).getBytes(StandardCharsets.UTF_8); }
            default -> { }
        }
        String json = JSON.writeValueAsString(root);
        if (kind.equals("nonfinite")) json = json.replace("1.0,", "1e999,");
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AliyunEmbeddingGateway.class)
    static class GatewayConfiguration {
        @Bean ObjectMapper objectMapper() { return JSON; }
    }
}
