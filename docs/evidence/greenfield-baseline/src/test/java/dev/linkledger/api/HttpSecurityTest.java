package dev.linkledger.api;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:http-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class HttpSecurityTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void realServletPathsCannotBypassAuthentication() throws Exception {
        for (String path : new String[]{"/api/v1/urls", "/%61pi/v1/urls", "/api;ignored/v1/urls", "/api//v1/urls"}) {
            HttpResponse<String> result = client.send(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                            "{\"url\":\"https://example.com\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).as(path).isBetween(400, 499);
        }
        HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(uri("/actuator/metrics")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.statusCode()).isEqualTo(401);
        assertThat(metrics.body()).doesNotContain("test-secret-key");
        assertThat(metrics.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(metrics.headers().firstValue("Content-Security-Policy")).isPresent();
    }

    @Test
    void knownLengthAndChunkedOversizedBodiesAreRejected() throws Exception {
        byte[] body = ("{\"url\":\"https://example.com/" + "x".repeat(9000) + "\"}").getBytes(StandardCharsets.UTF_8);
        for (HttpRequest.BodyPublisher publisher : new HttpRequest.BodyPublisher[]{
                HttpRequest.BodyPublishers.ofByteArray(body),
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))}) {
            HttpResponse<String> result = client.send(HttpRequest.newBuilder(uri("/api/v1/urls"))
                    .header("X-API-Key", "test-secret-key").header("Content-Type", "application/json")
                    .POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(413);
        }
    }

    @Test
    void validAuthenticatedRequestWorksThroughRealFilter() throws Exception {
        HttpResponse<String> result = client.send(HttpRequest.newBuilder(uri("/api/v1/urls"))
                .header("X-API-Key", "test-secret-key").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(201);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
