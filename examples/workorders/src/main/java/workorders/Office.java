package workorders;

import rife.engine.Server;
import rife.ioc.HierarchicalProperties;

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
        var properties = HierarchicalProperties.createSystemInstance();
        var data = new File(Settings.value(properties, "workorders.data", "data/office"));
        data.mkdirs();
        var store = Store.h2(new File(data, "office").getAbsolutePath());
        store.migrate();
        var clock = Clock.systemDefaultZone();
        if (Boolean.parseBoolean(Settings.value(properties, "workorders.demo", "true"))) {
            Demo.seed(store, LocalDate.now(clock));
        }
        var server = new Server().port(Integer.parseInt(Settings.value(properties, "workorders.port", "8080")));
        var technicians = List.of(Settings.value(properties, "workorders.technicians", String.join(",", Demo.TECHNICIANS)).strip().split("\\s*,\\s*"));
        staticFiles(server).start(new OfficeSite(store, Settings.value(properties, "workorders.syncToken", Demo.SYNC_TOKEN), technicians, clock));
    }

    // the files of the sources while developing, those of the jar otherwise
    static Server staticFiles(Server server) {
        return new File("src/main/webapp").isDirectory() ? server.staticResourceBase("src/main/webapp") : server.staticUberJarResourceBase("webapp");
    }
}
