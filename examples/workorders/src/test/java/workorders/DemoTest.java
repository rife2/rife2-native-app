package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rife.engine.RequestMethod;
import rife.engine.SiteCookieJar;
import rife.engine.SiteRequest;
import rife.engine.SiteRunner;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static workorders.Fixture.*;

class DemoTest {
    private final Store store = store();

    @AfterEach
    void close() {
        Fixture.close(store);
    }

    @Test
    void seedsTheTechniciansDayOnce() {
        Demo.seed(store, TODAY);
        Demo.seed(store, TODAY);
        var orders = store.orders();
        assertEquals(5, orders.size());
        var sams = store.scheduledFor(Demo.TECHNICIAN);
        assertEquals(3, sams.stream().filter(order -> order.getScheduledFor().equals(TODAY)).count());
        assertEquals(4, sams.size());
        assertTrue(orders.stream().allMatch(order -> !store.checklist(order.getId()).isEmpty()));
    }

    @Test
    void seedsAgainOnceNothingIsPlannedAnymore() {
        Demo.seed(store, TODAY);
        // the day after tomorrow, the demo's orders are all in the past
        Demo.seed(store, TODAY.plusDays(2));
        assertEquals(10, store.orders().size());
        assertEquals(4, store.orders().stream().filter(order -> order.getScheduledFor().equals(TODAY.plusDays(2))).count());
    }

    @Test
    void deviceWithoutAnOfficeSyncsWithTheDemoOffice(@TempDir File data) {
        var site = Field.site(data, null);
        var runner = new SiteRunner().cookieJar(new SiteCookieJar()).start(site);
        try {
            assertTrue(sync(runner).contains("received=4"));
        } finally {
            runner.stop();
            site.store().close();
        }
        assertTrue(new File(data, "demo-office.mv.db").exists());

        // a restart keeps the device's orders and the demo office's, so that
        // the same orders aren't received a second time
        var restarted = Field.site(data, null);
        var again = new SiteRunner().cookieJar(new SiteCookieJar()).start(restarted);
        try {
            assertEquals(4, restarted.store().orders().size());
            assertTrue(sync(again).contains("received=0"));
            assertEquals(4, restarted.store().orders().size());
        } finally {
            again.stop();
            restarted.store().close();
        }
    }

    private static String sync(SiteRunner runner) {
        var today = runner.handle("/");
        assertEquals(200, today.getStatus());
        var token = today.getText().replaceAll("(?s).*name=\"csrfToken\" value=\"([^\"]+)\".*", "$1");
        var synced = runner.handle("/sync", new SiteRequest(RequestMethod.POST).header("X-CSRF-Token", token));
        assertEquals(302, synced.getStatus());
        return synced.getHeader("Location");
    }
}
