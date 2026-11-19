package workorders;

import rife.config.RifeConfig;
import rife.engine.Server;
import rife.engine.SiteRunner;

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
        var site = site(new File(Office.setting("workorders.data", "WORKORDERS_DATA", "data/field")),
            Office.setting("workorders.office", "WORKORDERS_OFFICE", "http://localhost:8080"));
        var server = new Server().port(Integer.parseInt(Office.setting("workorders.port", "WORKORDERS_PORT", "8081")));
        Office.staticFiles(server).start(site);
    }

    /**
     * The site of a device that keeps its orders in a directory. Without the
     * address of an office, it syncs with a demo office in this process.
     */
    public static FieldSite site(File data, String officeUrl) {
        return site(data, officeUrl,
            Office.setting("workorders.technician", "WORKORDERS_TECHNICIAN", Demo.TECHNICIAN),
            Office.setting("workorders.syncToken", "WORKORDERS_SYNC_TOKEN", "demo"));
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
        if (null == officeUrl || officeUrl.isBlank()) {
            // kept next to the device's database, so that its orders stay
            // the same ones across restarts
            var office_store = Store.h2(new File(data, "demo-office").getAbsolutePath());
            office_store.migrate();
            Demo.seed(office_store, LocalDate.now(clock));
            office = Links.runner(new SiteRunner().start(new OfficeSite(office_store, token, Demo.TECHNICIANS, clock)), token);
        } else {
            office = Links.http(officeUrl, token);
        }
        return new FieldSite(store, technician, office, clock);
    }
}
