package workorders;

import rife.config.RifeConfig;
import rife.engine.Server;
import rife.engine.SiteRunner;
import rife.ioc.HierarchicalProperties;

import java.io.File;
import java.time.Clock;
import java.time.LocalDate;

/**
 * The technician's site: in a browser next to the office while developing,
 * or inside the app on a device.
 */
public final class Field {
    private Field() {
    }

    public static void main(String[] args) {
        var properties = HierarchicalProperties.createSystemInstance();
        var site = site(new File(Settings.value(properties, "workorders.data", "data/field")), office(properties, "http://localhost:8080"), properties);
        var server = new Server().port(Integer.parseInt(Settings.value(properties, "workorders.port", "8081")));
        Office.staticFiles(server).start(site);
    }

    /**
     * The address of the office that a device syncs with; an empty one is
     * the demo office in this process.
     */
    public static String office(HierarchicalProperties properties, String fallback) {
        return Settings.raw(properties, "workorders.office", fallback);
    }

    /**
     * The site of a device that keeps its orders in a directory, with the
     * technician and the sync token of its settings. Without the address of
     * an office, it syncs with a demo office in this process.
     */
    public static FieldSite site(File data, String officeUrl, HierarchicalProperties properties) {
        return site(data, officeUrl, Settings.value(properties, "workorders.technician", Demo.TECHNICIAN),
            Settings.value(properties, "workorders.syncToken", Demo.SYNC_TOKEN));
    }

    public static FieldSite site(File data, String officeUrl, String technician, String token) {
        data.mkdirs();
        // the uploads go to a directory of the app rather than the system's
        var uploads = new File(data, "uploads");
        uploads.mkdirs();
        RifeConfig.engine().setFileUploadPath(uploads.getAbsolutePath());
        var store = Store.h2(new File(data, "field").getAbsolutePath());
        store.migrate();
        var clock = Clock.systemDefaultZone();
        Sync.Link office;
        Runnable demo = () -> {};
        if (null == officeUrl || officeUrl.isBlank()) {
            // kept next to the device's database, so that its orders stay
            // the same ones across restarts
            var office_store = Store.h2(new File(data, "demo-office").getAbsolutePath());
            office_store.migrate();
            Demo.seed(office_store, LocalDate.now(clock));
            var office_runner = new SiteRunner().start(new OfficeSite(office_store, token, Demo.TECHNICIANS, clock));
            office = Links.runner(office_runner, token);
            // it goes with the device's site, and its database with it
            demo = office_runner::stop;
        } else {
            office = Links.http(officeUrl, token);
        }
        return new FieldSite(store, technician, office, clock, demo);
    }
}
