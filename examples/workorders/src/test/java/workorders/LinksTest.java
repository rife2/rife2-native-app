package workorders;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LinksTest {
    private final HttpServer server = server();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>();
    private volatile int status = 200;

    @AfterEach
    void close() {
        server.stop(0);
    }

    @Test
    void httpLinkPostsTheJsonWithTheToken() {
        var answer = Links.http(url(), "secret").exchange("{\"technician\": \"Sam\"}");
        assertEquals("{\"answered\": true}", answer);
        assertEquals("Bearer secret", authorization.get());
        assertEquals("{\"technician\": \"Sam\"}", received.get());
    }

    @Test
    void httpLinkFailsOnAnotherStatus() {
        status = 401;
        var failure = assertThrows(IllegalStateException.class, () -> Links.http(url(), "secret").exchange("{}"));
        assertTrue(failure.getMessage().contains("401"));
    }

    @Test
    void httpLinkFailsWhenTheOfficeIsntThere() {
        var url = url();
        server.stop(0);
        var failure = assertThrows(IllegalStateException.class, () -> Links.http(url, "secret").exchange("{}"));
        assertEquals("The office couldn't be reached", failure.getMessage());
    }

    @Test
    void anOfficeElsewhereHasToUseHttps() {
        var failure = assertThrows(IllegalArgumentException.class, () -> Links.http("http://office.example.com", "secret"));
        assertTrue(failure.getMessage().contains("https"));
        assertNotNull(Links.http("https://office.example.com", "secret"));
        assertNotNull(Links.http("http://localhost:8080", "secret"));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/sync", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var body = "{\"answered\": true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
