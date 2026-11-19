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
        // what the settings of the app say fails the sync, not the site
        var link = Links.http("http://office.example.com", "secret");
        var failure = assertThrows(IllegalArgumentException.class, () -> link.exchange("{}"));
        assertTrue(failure.getMessage().contains("https"));
        assertEquals("https://office.example.com/api/sync", Links.sync("https://office.example.com").toString());
        assertEquals("http://localhost:8080/api/sync", Links.sync("http://localhost:8080").toString());
        assertThrows(IllegalArgumentException.class, () -> Links.sync("ftp://localhost/"));
    }

    @Test
    void anAddressAsItsTypedInTheSettings() {
        assertEquals("https://office.example.com/api/sync", Links.sync(" https://office.example.com/ ").toString());
        assertEquals("https://office.example.com/api/sync", Links.sync("HTTPS://office.example.com").toString());
        assertEquals("http://LOCALHOST:8080/api/sync", Links.sync("http://LOCALHOST:8080").toString());
        assertEquals("https://example.com/office/api/sync", Links.sync("https://example.com/office/").toString());
        assertEquals("https://office.example.com/api/sync", Links.sync("https://office.example.com/api/sync").toString());
        assertEquals("http://[::1]:8080/api/sync", Links.sync("http://[::1]:8080").toString());
        for (var wrong : new String[] {"office.example.com", "https://office example.com", "https://office.example.com?x=1", "https://office.example.com/#f",
                                       "https:office", "https://", "http://office.example.com", "https://user:secret@office.example.com",
                                       "https://office.example.com:99999", "http://localhost@office.example.com"}) {
            assertThrows(Links.WrongAddressException.class, () -> Links.sync(wrong), wrong);
        }
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
