package workorders.ios;

import com.sun.net.httpserver.HttpServer;
import rife.engine.*;
import rife.nativeapp.NativeApp;
import rife.tools.FileUtils;
import workorders.Demo;
import workorders.OfficeSite;
import workorders.Store;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The technician's site as the app runs it, through rife2-native-app, on
 * the JVM.
 */
public final class Embedded {
    private static NativeApp app_;
    // each request has a number of its own, as in the app
    private static final AtomicLong NUMBERS = new AtomicLong();

    private Embedded() {
    }

    /**
     * Goes through days of work the way the web view does, which checks a
     * build on the JVM in seconds and shows the GraalVM tracing agent
     * everything that the native library needs: a first day with the demo
     * office, a second launch on the same database, and a day with an office
     * that's reached over HTTP.
     */
    public static void main(String[] args)
    throws Exception {
        var data = new File(args.length > 0 ? args[0] : "build/embedded");
        // the days start without orders on the device
        if (data.exists()) {
            FileUtils.deleteDirectory(data);
        }
        relaunch(new File(data, "demo"), null, null);
        new Walk().firstDay();
        relaunch(new File(data, "demo"), null, null);
        new Walk().secondLaunch();

        var office_store = Store.h2(new File(data, "office/office").getAbsolutePath());
        office_store.migrate();
        Demo.seed(office_store, LocalDate.now());
        var office = new SiteRunner().start(new OfficeSite(office_store, "walk", Demo.TECHNICIANS, Clock.systemDefaultZone()));
        var server = officeOverHttp(office);
        try {
            relaunch(new File(data, "http"), "http://127.0.0.1:" + server.getAddress().getPort(), "walk");
            new Walk().firstDay();
            // an office that can't be reached anymore
            server.stop(0);
            new Walk().unreachableOffice();
        } finally {
            server.stop(0);
            office.stop();
            app_.stop();
        }
        System.out.println("The walk through the days of work passed");
    }

    // what the app tells the site, as it does when it starts again
    private static void relaunch(File data, String office, String token) {
        if (app_ != null) {
            app_.stop();
        }
        app_ = new NativeApp().property(NativeApp.DATA_PROPERTY, data.getAbsolutePath());
        if (office != null) {
            app_.property("workorders.office", office);
        }
        if (token != null) {
            app_.property("workorders.syncToken", token);
        }
    }

    // the office's sync endpoint on this machine, the way a device reaches it
    private static HttpServer officeOverHttp(SiteRunner office)
    throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(OfficeSite.SYNC_PATH, exchange -> {
            var request = new SiteRequest(RequestMethod.valueOf(exchange.getRequestMethod()))
                .contentType(exchange.getRequestHeaders().getFirst("Content-Type"))
                .bodyAsBytes(exchange.getRequestBody().readAllBytes());
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            var response = office.handle(OfficeSite.SYNC_PATH, request);
            var body = response.getBytes();
            exchange.sendResponseHeaders(response.getStatus(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static final class Walk {
        private static final Pattern TOKEN = Pattern.compile("\"X-CSRF-Token\": ?\"([^\"]+)\"|name=\"csrfToken\" value=\"([^\"]+)\"");
        private static final Pattern HIDDEN = Pattern.compile("<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\"");
        private String token_;
        private String location_;

        void firstDay() {
            expect(get("/css/workorders.css"), "workorders-css", ".jobs");
            expect(get("/js/htmx.min.js"), "htmx", "htmx");
            expect(get("/js/signature.js"), "signature-js", "toDataURL");
            expect(get("/"), "today", "no orders on this device yet");
            expect(send("POST", "/sync", (byte[]) null, null, false), "without-token", "403");
            var today = expect(post("/sync", ""), "sync", "received");
            var order = today.replaceFirst("(?s).*?class=\"job\" href=\"" + Pattern.quote(root()) + "([^\"]+)\".*", "$1");
            expect(get(order), "order", "Start the work");
            expect(send("PATCH", order + "/check/1", "done=true", "application/x-www-form-urlencoded"), "not-started", "409");
            var started = expect(post(order + "/start", ""), "start", "Sign off");
            var item = started.replaceFirst("(?s).*?hx-patch=\"" + Pattern.quote(root()) + "([^\"]+)\".*", "$1");
            expect(send("PATCH", item, "done=true", "application/x-www-form-urlencoded"), "check", "checked");
            expect(send("POST", order + "/parts", "part=&quantity=0", "application/x-www-form-urlencoded"), "part-refused", "A part needs a name");
            var parts = expect(send("POST", order + "/parts", "part=Filter&quantity=2", "application/x-www-form-urlencoded"), "part", "Filter");
            var removal = parts.replaceFirst("(?s).*?hx-delete=\"" + Pattern.quote(root()) + "([^\"]+)\".*", "$1");
            expect(send("DELETE", removal, (byte[]) null, null), "part-removed", "No parts used yet.");
            send("POST", order + "/parts", "part=Gasket+set&quantity=1", "application/x-www-form-urlencoded");
            expect(upload(order + "/photos", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)), "photo-refused", "That wasn't an image");
            // the size of a photo of a phone's camera
            var photos = expect(upload(order + "/photos", "IMG_0001.jpeg", "image/jpeg", new byte[3 * 1024 * 1024]), "photo", "/photo/");
            var photo = photos.replaceFirst("(?s).*?<img src=\"" + Pattern.quote(root()) + "([^\"]+)\".*", "$1");
            expect(get(photo), "photo-served", "200");

            var id = order.replaceAll("\\D", "");
            var review = expect(get("/sign-off?order=" + id), "review", "Step 1 of 3");
            var unconfirmed = expect(step(review, "next=1"), "review-unconfirmed", "Confirm this or go back");
            var report = expect(step(unconfirmed, "skip=true&next=1"), "report", "Step 2 of 3");
            var refused = expect(step(report, "notes=&minutes=2&next=1"), "report-refused", "Describe what you did.");
            var back = expect(step(refused, "notes=Replaced+the+gasket.&minutes=40&back=1"), "back-to-review", "Step 1 of 3");
            var again = expect(step(back, "skip=true&next=1"), "report-again", "Step 2 of 3");
            var signature = expect(step(again, "notes=Replaced+the+gasket.&minutes=40&next=1"), "signature", "Step 3 of 3");
            var unsigned = expect(step(signature, "signedBy=Jo&signature=&finish=1"), "signature-refused", "both needed");
            var report_back = expect(step(unsigned, "signedBy=Jo&back=1"), "back-to-report", "Step 2 of 3");
            var signature_again = expect(step(report_back, "notes=Replaced+the+gasket.&minutes=40&next=1"), "signature-again", "Step 3 of 3");
            expect(step(signature_again, "signedBy=Jo&signature=" + encode("data:image/png;base64," + Base64.getEncoder().encodeToString(PIXEL)) + "&finish=1"),
                "finished", "signed by Jo");
            expect(post("/sign-off", "contId=gone&order=" + id), "sign-off-ended", "Finished");
            expect(send("POST", order + "/photos", (byte[]) null, null), "finished-refused", "409");
            expect(post("/sync", ""), "sync-again", "Sent 1 finished");
            expect(get("/order/9999"), "missing", "isn't on this device");
        }

        void secondLaunch() {
            var today = expect(get("/"), "relaunched", "<h2>Done</h2>");
            expect(today, "relaunched-kept", "Sent");
            expect(post("/sync", ""), "relaunched-sync", "received 0 new");
        }

        void unreachableOffice() {
            get("/");
            expect(post("/sync", ""), "unreachable", "couldn't be reached");
        }

        private String step(String page, String fields) {
            var form = new StringBuilder();
            var hidden = HIDDEN.matcher(page);
            while (hidden.find()) {
                if (!hidden.group(1).equals("signature")) {
                    form.append(encode(hidden.group(1))).append('=').append(encode(hidden.group(2))).append('&');
                }
            }
            return post("/sign-off", form + fields);
        }

        private String get(String path) {
            return send("GET", path, (byte[]) null, null);
        }

        private String post(String path, String form) {
            return send("POST", path, form, "application/x-www-form-urlencoded");
        }

        private String upload(String path, String name, String type, byte[] content) {
            var boundary = "----walk";
            var body = new ByteArrayOutputStream();
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"" + name + "\"\r\n" +
                "Content-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(content);
            body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return send("POST", path, body.toByteArray(), "multipart/form-data; boundary=" + boundary);
        }

        private String send(String method, String path, String body, String contentType) {
            return send(method, path, null == body ? null : body.getBytes(StandardCharsets.UTF_8), contentType);
        }

        private String send(String method, String path, byte[] body, String contentType) {
            return send(method, path, body, contentType, true);
        }

        // follows redirects like the app does
        private String send(String method, String path, byte[] body, String contentType, boolean withToken) {
            for (var hops = 0; hops < 5; hops++) {
                var headers = new StringBuilder();
                if (contentType != null) {
                    headers.append("Content-Type: ").append(contentType).append('\n');
                }
                if (withToken && token_ != null && !method.equals("GET")) {
                    headers.append("X-CSRF-Token: ").append(token_).append('\n');
                }
                var status = new int[1];
                var answer = new ByteArrayOutputStream();
                var failure = new String[1];
                app_.handle(NUMBERS.incrementAndGet(), path.contains("://") ? path : root() + path, method, headers.toString(), body, new SiteResponseListener() {
                    public void committed(SiteResponse response) {
                        status[0] = response.getStatus();
                        location_ = response.getHeader("Location");
                    }

                    public void content(byte[] bytes, int offset, int length) {
                        answer.write(bytes, offset, length);
                    }

                    public void completed() {
                    }

                    public void failed(Throwable exception) {
                        failure[0] = String.valueOf(exception);
                    }

                    public void cancelled() {
                        failure[0] = "cancelled";
                    }
                });
                if (failure[0] != null) {
                    throw new IllegalStateException(method + " " + path + " failed: " + failure[0]);
                }
                if (status[0] >= 300 && status[0] < 400 && location_ != null) {
                    path = URI.create(root() + "/").resolve(location_).toString();
                    method = "GET";
                    body = null;
                    contentType = null;
                    continue;
                }
                var text = answer.toString(StandardCharsets.ISO_8859_1);
                var token = TOKEN.matcher(text);
                if (token.find()) {
                    token_ = token.group(1) != null ? token.group(1) : token.group(2);
                }
                return status[0] + "\n" + text;
            }
            throw new IllegalStateException("Too many redirects for " + path);
        }

        private static String expect(String answer, String what, String fragment) {
            if (!answer.contains(fragment)) {
                throw new IllegalStateException("The " + what + " answer doesn't have '" + fragment + "':\n" + answer);
            }
            System.out.println(what + ": " + answer.substring(0, answer.indexOf('\n')));
            return answer;
        }

        private static String root() {
            return NativeApp.DEFAULT_SCHEME + "://" + NativeApp.DEFAULT_HOST;
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private static final byte[] PIXEL = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
}
