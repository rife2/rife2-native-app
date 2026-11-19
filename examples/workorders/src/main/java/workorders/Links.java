package workorders;

import rife.engine.RequestMethod;
import rife.engine.SiteRequest;
import rife.engine.SiteRunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class Links {
    private Links() {
    }

    // the office on the network
    public static Sync.Link http(String officeUrl, String token) {
        // the token and the customers' data only travel in the clear on this machine
        var uri = URI.create(officeUrl);
        if (!"https".equals(uri.getScheme()) && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("The office has to be reached over https, not " + officeUrl);
        }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return request -> {
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(officeUrl + "/api/sync"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(request, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("The office answered the sync with " + response.statusCode());
                }
                return response.body();
            } catch (IOException e) {
                throw new IllegalStateException("The office couldn't be reached", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The sync was interrupted", e);
            }
        };
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    // an office that runs in this process, for the demo and the tests
    public static Sync.Link runner(SiteRunner office, String token) {
        return request -> {
            var response = office.handle("/api/sync", new SiteRequest(RequestMethod.POST)
                .contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .bodyAsBytes(request.getBytes(StandardCharsets.UTF_8)));
            if (response.getStatus() != 200) {
                throw new IllegalStateException("The office answered the sync with " + response.getStatus());
            }
            return response.getText();
        };
    }
}
