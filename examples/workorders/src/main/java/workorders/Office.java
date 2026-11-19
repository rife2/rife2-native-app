package workorders;

import rife.engine.Server;

import java.io.File;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Runs the office as a web application.
 */
public final class Office {
    private Office() {
    }

    public static void main(String[] args) {
        var data = new File(setting("workorders.data", "WORKORDERS_DATA", "data/office"));
        data.mkdirs();
        var store = Store.h2(new File(data, "office").getAbsolutePath());
        store.migrate();
        if (Boolean.parseBoolean(setting("workorders.demo", "WORKORDERS_DEMO", "true"))) {
            Demo.seed(store, LocalDate.now());
        }
        var server = new Server().port(Integer.parseInt(setting("workorders.port", "WORKORDERS_PORT", "8080")));
        var technicians = List.of(setting("workorders.technicians", "WORKORDERS_TECHNICIANS", String.join(",", Demo.TECHNICIANS)).split("\\s*,\\s*"));
        staticFiles(server).start(new OfficeSite(store, setting("workorders.syncToken", "WORKORDERS_SYNC_TOKEN", "demo"), technicians, Clock.systemDefaultZone()));
    }

    // the files of the sources while developing, those of the jar otherwise
    static Server staticFiles(Server server) {
        return new File("src/main/webapp").isDirectory() ? server.staticResourceBase("src/main/webapp") : server.staticUberJarResourceBase("webapp");
    }

    static String setting(String property, String variable, String fallback) {
        var value = System.getProperty(property);
        if (null == value) {
            value = System.getenv(variable);
        }
        return null == value ? fallback : value;
    }
}
