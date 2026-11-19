package workorders;

import rife.engine.RequestMethod;
import rife.engine.SiteRequest;
import rife.engine.SiteRunner;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public final class Links {
    private Links() {
    }

    // The office on the network. An address that's wrong, as one typed in
    // the settings of the app can be, fails the sync rather than the site.
    public static Sync.Link http(String officeUrl, String token) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return request -> {
            var sync = sync(officeUrl);
            try {
                var response = client.send(HttpRequest.newBuilder(sync)
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

    /** An address of the office that isn't one, as the settings of the app can have. */
    public static final class WrongAddressException extends IllegalArgumentException {
        WrongAddressException(String message) {
            super(message);
        }
    }

    // where the office's devices sync, an address of its own whether it ends with a slash or not
    static URI sync(String officeUrl) {
        URI office;
        try {
            office = new URI(officeUrl.trim());
        } catch (URISyntaxException e) {
            throw new WrongAddressException("The office's address isn't one: " + officeUrl);
        }
        var scheme = null == office.getScheme() ? "" : office.getScheme().toLowerCase(Locale.ROOT);
        if (null == office.getHost() || office.getRawUserInfo() != null || office.getRawQuery() != null || office.getRawFragment() != null || office.getPort() == 0 || office.getPort() > 65535) {
            throw new WrongAddressException("The office's address is one of a site, like https://office.example.com, not " + officeUrl);
        }
        // the token and the customers' data only travel in the clear on this machine
        if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(office.getHost().toLowerCase(Locale.ROOT)))) {
            throw new WrongAddressException("The office has to be reached over https, not " + officeUrl);
        }
        // also when it was pasted with where the devices sync
        var path = null == office.getRawPath() ? "" : office.getRawPath().replaceAll("/+$", "");
        if (path.endsWith(OfficeSite.SYNC_PATH)) {
            path = path.substring(0, path.length() - OfficeSite.SYNC_PATH.length());
        }
        return URI.create(scheme + "://" + office.getRawAuthority() + path + OfficeSite.SYNC_PATH);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    // an office that runs in this process, for the demo and the tests
    public static Sync.Link runner(SiteRunner office, String token) {
        return request -> {
            var response = office.handle(OfficeSite.SYNC_PATH, new SiteRequest(RequestMethod.POST)
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
