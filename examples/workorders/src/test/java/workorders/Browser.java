package workorders;

import rife.engine.RequestMethod;
import rife.engine.Site;
import rife.engine.SiteCookieJar;
import rife.engine.SiteRequest;
import rife.engine.SiteRunner;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Acts as the web view of the app: it keeps the cookies, sends the CSRF
 * token of the last page like its forms and htmx do, and follows redirects.
 */
final class Browser implements AutoCloseable {
    private static final Pattern TOKEN = Pattern.compile("\"X-CSRF-Token\": ?\"([^\"]+)\"|name=\"csrfToken\" value=\"([^\"]+)\"");

    final SiteRunner runner;
    private String token_;

    Browser(Site site) {
        runner = new SiteRunner().cookieJar(new SiteCookieJar()).staticResourceBase("src/main/webapp").start(site);
    }

    record Page(int status, String url, Map<String, String> headers, String text, byte[] bytes) {
        String header(String name) {
            for (var header : headers.entrySet()) {
                if (header.getKey().equalsIgnoreCase(name)) {
                    return header.getValue();
                }
            }
            return null;
        }

        String path() {
            return URI.create(url).getRawPath();
        }

        // the hidden inputs of the forms, like the continuation they resume
        Map<String, String> hidden() {
            var hidden = new LinkedHashMap<String, String>();
            var inputs = Pattern.compile("<input[^>]*type=\"hidden\"[^>]*>").matcher(text);
            while (inputs.find()) {
                var name = Pattern.compile("name=\"([^\"]*)\"").matcher(inputs.group());
                var value = Pattern.compile("value=\"([^\"]*)\"").matcher(inputs.group());
                if (name.find()) {
                    hidden.put(name.group(1), value.find() ? value.group(1) : "");
                }
            }
            return hidden;
        }

        boolean contains(String fragment) {
            return text.contains(fragment);
        }

        // the value of an attribute of the first element that has another attribute
        String attribute(String attribute, String near) {
            var matcher = Pattern.compile("<[^>]*" + Pattern.quote(near) + "[^>]*>").matcher(text);
            if (!matcher.find()) {
                return null;
            }
            var value = Pattern.compile(attribute + "=\"([^\"]*)\"").matcher(matcher.group());
            return value.find() ? value.group(1).replace("&amp;", "&") : null;
        }
    }

    Page get(String url) {
        return follow(send(RequestMethod.GET, url, null, null, true));
    }

    Page post(String url, Map<String, String> form) {
        return follow(send(RequestMethod.POST, url, "application/x-www-form-urlencoded", encode(form), true));
    }

    // what htmx sends, which isn't followed and doesn't replace the page
    Page hx(RequestMethod method, String url, Map<String, String> form) {
        var request = request(method, url, null == form ? null : "application/x-www-form-urlencoded", null == form ? null : encode(form), true);
        request.header("HX-Request", "true");
        return page(url, request);
    }

    Page upload(String url, String name, String fileName, String contentType, byte[] content) {
        var boundary = "----" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName +
            "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = request(RequestMethod.POST, url, "multipart/form-data; boundary=" + boundary, body.toByteArray(), true);
        request.header("HX-Request", "true");
        return page(url, request);
    }

    Page send(RequestMethod method, String url, String contentType, byte[] body, boolean withToken) {
        return page(url, request(method, url, contentType, body, withToken));
    }

    private SiteRequest request(RequestMethod method, String url, String contentType, byte[] body, boolean withToken) {
        var request = new SiteRequest(method);
        if (contentType != null) {
            request.contentType(contentType);
        }
        if (body != null) {
            request.bodyAsBytes(body);
        }
        if (withToken && token_ != null && method != RequestMethod.GET) {
            request.header("X-CSRF-Token", token_);
        }
        return request;
    }

    private Page page(String url, SiteRequest request) {
        var absolute = url.contains("://") ? url : runner.getServerRootUrl() + url;
        var response = runner.handle(absolute, request);
        var page = new Page(response.getStatus(), absolute, response.getSingleValueHttpHeaders(), response.getText(), response.getBytes());
        var token = TOKEN.matcher(page.text());
        if (token.find()) {
            token_ = token.group(1) != null ? token.group(1) : token.group(2);
        }
        return page;
    }

    private Page follow(Page page) {
        var hops = 0;
        while (page.status() >= 300 && page.status() < 400 && page.header("Location") != null && hops++ < 5) {
            page = send(RequestMethod.GET, URI.create(page.url()).resolve(page.header("Location")).toString(), null, null, true);
        }
        return page;
    }

    static byte[] encode(Map<String, String> form) {
        var encoded = new StringBuilder();
        for (var field : form.entrySet()) {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, String> form(String... pairs) {
        var form = new LinkedHashMap<String, String>();
        for (var i = 0; i < pairs.length; i += 2) {
            form.put(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    @Override
    public void close() {
        runner.stop();
    }
}
