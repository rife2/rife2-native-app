package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rife.engine.RequestMethod;
import rife.engine.SiteCookieJar;
import rife.engine.SiteRequest;
import rife.engine.SiteRunner;
import rife.ioc.HierarchicalProperties;
import rife.nativeapp.NativeApp;

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
        var site = Field.site(data, null, new HierarchicalProperties());
        var runner = new SiteRunner().cookieJar(new SiteCookieJar()).start(site);
        try {
            assertTrue(sync(runner).contains("received=4"));
        } finally {
            runner.stop();
        }
        assertTrue(new File(data, "demo-office.mv.db").exists());

        // a restart keeps the device's orders and the demo office's, so that
        // the same orders aren't received a second time
        var restarted = Field.site(data, null, new HierarchicalProperties());
        var again = new SiteRunner().cookieJar(new SiteCookieJar()).start(restarted);
        try {
            assertEquals(4, restarted.store().orders().size());
            assertTrue(sync(again).contains("received=0"));
            assertEquals(4, restarted.store().orders().size());
        } finally {
            again.stop();
        }
    }

    // as the app has it, which finds the site through its SiteProvider and tells it its settings
    @Test
    void theAppsSiteWithTheSettingsThatTheAppTellsIt(@TempDir File data) {
        try (var app = new NativeApp().property(NativeApp.DATA_PROPERTY, data.getAbsolutePath()).property("workorders.technician", "Jo Bloggs")) {
            var today = app.runner().handle("/");
            assertEquals(200, today.getStatus());
            assertTrue(today.getText().contains("Jo Bloggs"));
            assertTrue(new File(data, "field.mv.db").exists());
        }
    }

    // an address that's set to nothing is the demo office's, also where another office is the default
    @Test
    void anEmptyOfficeAddressIsTheDemoOffice(@TempDir File data) {
        var properties = new HierarchicalProperties().put("workorders.office", "").put("workorders.port", "");
        assertEquals("", Field.office(properties, "http://localhost:8080"));
        // another setting that's set to nothing isn't set
        assertEquals("8081", Settings.value(properties, "workorders.port", "8081"));
        assertEquals("http://localhost:8080", Field.office(new HierarchicalProperties(), "http://localhost:8080"));
        assertEquals("https://office.example.com", Field.office(new HierarchicalProperties().put("WORKORDERS_OFFICE", "https://office.example.com"), null));
        try (var app = new NativeApp().property(NativeApp.DATA_PROPERTY, data.getAbsolutePath()).property("workorders.office", "")) {
            assertTrue(sync(app.runner()).contains("received=4"));
        }
    }

    // what someone types in the settings of the app fails the sync, which says so, not the whole app
    @Test
    void aWrongOfficeAddressFailsTheSyncNotTheApp(@TempDir File data) {
        for (var office : new String[] {"office.example.com", "http://office.example.com", "https://office example.com"}) {
            try (var app = new NativeApp().property(NativeApp.DATA_PROPERTY, new File(data, String.valueOf(office.hashCode())).getAbsolutePath())
                .property("workorders.office", office)) {
                var runner = app.runner();
                assertTrue(sync(runner).contains("sync=address"), office);
                var today = runner.handle("/?sync=address");
                assertEquals(200, today.getStatus());
                assertTrue(today.getText().contains("address in the settings of the app isn't right"));
            }
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
