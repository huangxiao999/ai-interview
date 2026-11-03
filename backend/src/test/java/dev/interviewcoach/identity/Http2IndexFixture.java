package dev.interviewcoach.identity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** 合成证书+本机Node HTTP/2故障服务；权限、业务状态及PG仓储不在此替代。 */
final class Http2IndexFixture implements AutoCloseable {
    private static final String PASSWORD="synthetic-test-store";
    final Path store;
    final Path counts;
    final URI endpoint;
    private final Process server;

    Http2IndexFixture(String database) throws Exception {
        Path directory=Path.of("../.cache",database+"-http2").toAbsolutePath();Files.createDirectories(directory);
        store=directory.resolve("fixture.p12");counts=directory.resolve("counts.json");
        Path ready=directory.resolve("ready.json");
        var keytool=new ProcessBuilder(tool("keytool"),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048",
                "-validity","2","-dname","CN=localhost","-ext","SAN=IP:127.0.0.1,DNS:localhost",
                "-storetype","PKCS12","-keystore",store.toString(),"-storepass",PASSWORD,"-keypass",PASSWORD,"-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        try { assertThat(keytool.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(keytool.exitValue()).isZero(); }
        finally { if(keytool.isAlive())keytool.destroyForcibly(); }
        server=new ProcessBuilder("node",Path.of("../scripts/Verify-P3IndexHttp2.mjs").toAbsolutePath().toString(),
                store.toString(),ready.toString(),counts.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("server.log").toFile()).start();
        try {
            for(int i=0;i<100 && !Files.exists(ready);i++) { if(!server.isAlive())throw new IOException("HTTP2 fixture exited");Thread.sleep(100); }
            assertThat(Files.exists(ready)).isTrue();
            int port=JsonMapper.builder().build().readTree(Files.readString(ready)).path("port").asInt();
            endpoint=URI.create("https://127.0.0.1:"+port+"/embedding");
        } catch(Exception|Error failure) { close();throw failure; }
    }

    static SSLContext trust(Path store) throws Exception {
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,PASSWORD.toCharArray());}
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(keys);
        var context=SSLContext.getInstance("TLS");context.init(null,factory.getTrustManagers(),null);return context;
    }
    int calls(String marker) throws Exception { return JsonMapper.builder().build().readTree(Files.readString(counts)).path(marker).asInt(); }

    void defaultJdkControl() throws Exception {
        var builder=new ProcessBuilder(tool("java"),"-cp",System.getProperty("java.class.path"),Control.class.getName(),store.toString(),endpoint.toString())
                .redirectErrorStream(true).redirectOutput(store.getParent().resolve("default-jdk-control.log").toFile());
        for(String name:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"))builder.environment().remove(name);
        var process=builder.start();
        try { assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero(); }
        finally { if(process.isAlive())process.destroyForcibly(); }
        assertThat(calls("[H2_CONTROL]")).isEqualTo(2);
    }
    private static String tool(String name) { return Path.of(System.getProperty("java.home"),"bin",name+(System.getProperty("os.name").startsWith("Windows")?".exe":"")).toString(); }
    @Override public void close() {
        server.destroy();try { if(!server.waitFor(3,TimeUnit.SECONDS))server.destroyForcibly(); }
        catch(InterruptedException interrupted){server.destroyForcibly();Thread.currentThread().interrupt();}
    }

    /** 未加修补参数的独立JVM控制组，只检验底层传输，不模拟任何本地业务状态。 */
    public static class Control {
        public static void main(String[] args) throws Exception {
            try(var http=HttpClient.newBuilder().sslContext(trust(Path.of(args[0]))).connectTimeout(Duration.ofSeconds(3)).build()) {
                var request=HttpRequest.newBuilder(URI.create(args[1])).timeout(Duration.ofSeconds(5))
                        .header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"qwen3.7-text-embedding-flash\",\"input\":{\"texts\":[\"[H2_CONTROL] synthetic\"]},\"parameters\":{\"dimension\":1024,\"text_type\":\"document\"}}" )).build();
                try { http.send(request,HttpResponse.BodyHandlers.ofString());throw new AssertionError("Refused stream unexpectedly succeeded"); }
                catch(IOException expected){System.out.println("HTTP2 control refused; stream count retained by supplier fixture");}
            }
        }
    }
}
