package com.example.booking.ratelimit;

import com.example.booking.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Sends real HTTP requests to the embedded Tomcat (forwarded-header handling happens in Tomcat, so MockMvc cannot
 * exercise it) and exposes which rate-limit identities ended up in Redis.
 */
abstract class ClientIpTestSupport extends IntegrationTestSupport {

    static final String LOOPBACK_PEER = "(127\\.0\\.0\\.1|0:0:0:0:0:0:0:1)";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    protected int loginAttempt(String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.com\",\"password\":\"Wrong-password1\"}"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /** The identities (the part after {@code rl:login:}) that have a login counter in Redis. */
    protected Set<String> loginIdentities() {
        return redis.keys("rl:login:*").stream().map(key -> key.substring("rl:login:".length()))
                .collect(Collectors.toSet());
    }
}
