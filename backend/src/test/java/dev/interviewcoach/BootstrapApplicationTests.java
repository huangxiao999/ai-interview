package dev.interviewcoach;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.env.Environment;
import org.springframework.context.ApplicationContext;
import org.flywaydb.core.Flyway;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapApplicationTests {

    private final Environment environment;
    private final ApplicationContext context;
    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    BootstrapApplicationTests(Environment environment, ApplicationContext context) {
        this.environment = environment;
        this.context = context;
    }

    @Test
    void bootstrapDoesNotRequirePersistence() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcIndexedSessionRepository.class)).isEmpty();
    }

    @Test
    void healthExposesOnlyApplicationStatus() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        var health = JsonParserFactory.getJsonParser().parseMap(response.body());
        assertThat(health).containsEntry("status", "UP");
        assertThat(health).doesNotContainKeys("components", "details");
    }

    @Test
    void sensitiveEndpointsAndDefaultLoginStayUnavailable() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
        assertThat(get("/login").statusCode()).isEqualTo(403);
    }

    @Test
    void unsafeHealthRequestsAreRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/actuator/health"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:"
                + environment.getRequiredProperty("local.server.port") + path);
    }
}
