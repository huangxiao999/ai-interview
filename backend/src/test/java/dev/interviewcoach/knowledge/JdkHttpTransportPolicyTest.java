package dev.interviewcoach.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class JdkHttpTransportPolicyTest {
    @Test void mavenForkStartsWithEnforcedPolicy() { assertThat(JdkHttpTransportPolicy.enforced()).isTrue(); }

    @ParameterizedTest @ValueSource(strings={"missing","late","overridden","safe"})
    void onlyActualSafeStartupOptionsCanEnableGateway(String mode) throws Exception {
        var command=new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java").toString());
        if(mode.equals("safe") || mode.equals("overridden")) command.addAll(JdkHttpTransportPolicy.jvmArguments());
        if(mode.equals("overridden")) command.add("-Djdk.httpclient.redirects.retrylimit=2");
        command.addAll(java.util.List.of("-cp",System.getProperty("java.class.path"),Probe.class.getName(),mode));
        var builder=new ProcessBuilder(command).redirectErrorStream(true);
        for(String name:java.util.List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS")) builder.environment().remove(name);
        var process=builder.start();
        try {
            assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage(output).isZero();
            assertThat(output).contains("available="+mode.equals("safe"));
        } finally { if(process.isAlive())process.destroyForcibly(); }
    }

    static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }
    public static class Probe {
        public static void main(String[] args) {
            if(args[0].equals("late") || args[0].equals("overridden")) {
                // 运行中补安全值也不能掩盖缺失/被覆盖的启动值。
                for(String option:JdkHttpTransportPolicy.jvmArguments()) {
                    int equals=option.indexOf('=');System.setProperty(option.substring(2,equals),option.substring(equals+1));
                }
            }
            var env=new MockEnvironment().withProperty("BAILIAN_EMBEDDING_ENABLED","true")
                    .withProperty("BAILIAN_EMBEDDING_WORKSPACE","ws-fixture")
                    .withProperty("BAILIAN_EMBEDDING_ENDPOINT","https://ws-fixture.cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding")
                    .withProperty("DASHSCOPE_API_KEY","synthetic-key");
            var gateway=new AliyunEmbeddingGateway(env,JsonMapper.builder().build());
            try { System.out.println("available="+gateway.available()); }
            finally { gateway.close(); }
        }
    }
}
