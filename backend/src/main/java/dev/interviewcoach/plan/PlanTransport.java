package dev.interviewcoach.plan;

import dev.interviewcoach.knowledge.JdkHttpTransportPolicy;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 首片只支持明确隔离的外部HTTP故障适配。普通local/Docker及真实供应商始终不可用，不读取Key。 */
@Component @Profile("local")
final class PlanTransport {
    private final URI endpoint;
    private final ObjectMapper mapper;
    private final String database;
    private final java.util.concurrent.ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"plan-http-deadline");t.setDaemon(true);return t;});
    PlanTransport(Environment env,JdbcTemplate jdbc,ObjectMapper mapper){
        this.mapper=mapper;
        if(!env.getProperty("interview.plan.proof-enabled",Boolean.class,false)){endpoint=null;database=null;return;}
        database=jdbc.queryForObject("SELECT current_database()",String.class);
        String marker=env.getProperty("P4_PLAN_TEST_DATABASE");
        String comment=jdbc.queryForObject("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()",String.class);
        URI uri=URI.create(env.getRequiredProperty("interview.plan.proof-endpoint"));
        if(!Arrays.asList(env.getActiveProfiles()).contains("plan-verification") || database==null || !database.matches("p4dt01_verify_[a-f0-9]{16}")
                || !database.equals(marker) || !"p4-plan-isolation-v1".equals(comment)
                || !"false".equals(env.getProperty("BAILIAN_EMBEDDING_ENABLED","false")) || !JdkHttpTransportPolicy.enforced()
                || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1
                || !"".equals(uri.getPath()) || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getRawUserInfo()!=null)
            throw new IllegalStateException("Plan proof requires a marked new isolated PG16 database and loopback-only supplier; real service remains closed");
        endpoint=uri;
    }
    boolean available(){return endpoint!=null;}
    record Receipt(String requestId,Long inputTokens,Long outputTokens){ }
    record Reply(JsonNode content,Receipt receipt){ }
    static final class Failure extends RuntimeException{
        final String category; final Receipt receipt;
        Failure(String category,Receipt receipt){super(category);this.category=category;this.receipt=receipt;}
    }
    Reply send(String kind,Object input,Instant deadline){
        if(!available())throw new Failure("SERVICE_UNAVAILABLE",new Receipt(null,null,null));
        Duration remaining=Duration.between(Instant.now(),deadline);
        if(remaining.isNegative() || remaining.isZero())throw new Failure("EXTERNAL_UNKNOWN",new Receipt(null,null,null));
        HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(remaining.compareTo(Duration.ofSeconds(3))<0?remaining:Duration.ofSeconds(3)).build();
        var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(remaining);
        var expiration=deadlines.schedule(http::shutdownNow,Math.max(1,remaining.toMillis()),TimeUnit.MILLISECONDS);
        // 一个显式POST，无HTTP重试循环；到期关闭实际客户端，但不推断远端没有执行/收费。
        try(http){
            return RestClient.builder().requestFactory(factory).build().post().uri(endpoint.resolve("/"+kind.toLowerCase(java.util.Locale.ROOT)))
                    .header("X-Proof-Database",database).body(input).exchange((request,response)->{
                        byte[] bytes=response.getBody().readNBytes(262145);
                        if(bytes.length>262144)throw new Failure("RESPONSE_LIMIT",new Receipt(null,null,null));
                        JsonNode root;
                        try{root=mapper.readTree(bytes);}catch(RuntimeException malformed){throw new Failure("INVALID_RESPONSE",new Receipt(null,null,null));}
                        var receipt=receipt(root);
                        if(!response.getStatusCode().is2xxSuccessful())throw new Failure("HTTP_"+response.getStatusCode().value(),receipt);
                        return new Reply(root.path("content"),receipt);
                    });
        }catch(Failure failure){throw failure;}
        catch(RuntimeException unknown){throw new Failure("EXTERNAL_UNKNOWN",new Receipt(null,null,null));}
        finally{expiration.cancel(false);}
    }
    private static Receipt receipt(JsonNode root){
        if(root==null)return new Receipt(null,null,null);
        var id=root.path("requestId");String request=id.isString()?id.asString():null;
        if(request!=null && (request.isBlank() || request.length()>200 || request.chars().anyMatch(Character::isISOControl)))request=null;
        return new Receipt(request,nonnegative(root.path("inputTokens")),nonnegative(root.path("outputTokens")));
    }
    private static Long nonnegative(JsonNode node){return node.isIntegralNumber() && node.canConvertToLong() && node.asLong()>=0?node.asLong():null;}
    @PreDestroy void close(){deadlines.shutdownNow();}
}
