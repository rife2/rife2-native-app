package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rife.engine.RequestMethod;
import rife.engine.SiteRunner;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static workorders.Browser.form;
import static workorders.Fixture.*;

class FieldSiteTest {
    private final Store office = store();
    private final Store device = store();
    private final SiteRunner officeRunner = new SiteRunner().start(new OfficeSite(office, TOKEN, TECHNICIANS, CLOCK));
    private Sync.Link link = Links.runner(officeRunner, TOKEN);
    private final Browser browser = new Browser(new FieldSite(device, "Sam", request -> link.exchange(request), CLOCK));

    @AfterEach
    void close() {
        browser.close();
        officeRunner.stop();
        Fixture.close(office);
        Fixture.close(device);
    }

    @Test
    void emptyDeviceSaysThereIsNothing() {
        var today = browser.get("/");
        assertEquals(200, today.status());
        assertTrue(today.contains("<p>Sam, Tuesday 17 November</p>"));
        assertTrue(today.contains("Nothing waiting to be sent."));
        assertTrue(today.contains("There are no orders on this device yet."));
        assertFalse(today.contains("<h2>Today</h2>"));
    }

    @Test
    void syncFillsTodayAndLater() {
        order(office, TODAY, "13:00 to 15:00", "Elevator", "Sam");
        order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        order(office, TODAY.plusDays(1), "08:00 to 10:00", "Solar inverter", "Sam");

        var synced = sync();
        assertEquals("/", synced.path());
        assertTrue(synced.contains("Sent 0 finished, received 3 new."));
        var today = section(synced.text(), "<h2>Today</h2>", "<h2>Later</h2>");
        assertTrue(today.indexOf("Customer of Boiler") < today.indexOf("Customer of Elevator"), "today is sorted by arrival");
        assertFalse(today.contains("Solar inverter"));
        var later = section(synced.text(), "<h2>Later</h2>", "<h2>Done</h2>");
        assertTrue(later.contains("Wednesday 18 November, 08:00 to 10:00"));
        assertTrue(later.contains("Customer of Solar inverter"));
    }

    @Test
    void failedSyncKeepsEverythingAndSaysSo() {
        link = request -> {
            throw new IllegalStateException("offline");
        };
        var page = sync();
        assertEquals("/", page.path());
        assertTrue(page.contains("The office couldn't be reached."));
    }

    @Test
    void scheduledOrderCanOnlyBeStarted() {
        var order = synced("Boiler");
        var page = browser.get("/order/" + order.getId());
        assertEquals(200, page.status());
        assertTrue(page.contains("Customer of Boiler"));
        assertTrue(page.contains("href=\"tel:+442079460000\""));
        assertTrue(page.contains("Start the work"));
        assertFalse(page.contains("id=\"checklist\""));

        var item = device.checklist(order.getId()).get(0);
        assertEquals(409, browser.hx(RequestMethod.PATCH, "/order/" + order.getId() + "/check/" + item.getId(), form("done", "true")).status());
        assertEquals(409, browser.hx(RequestMethod.POST, "/order/" + order.getId() + "/parts", form("part", "Filter", "quantity", "1")).status());
        assertEquals(409, browser.upload("/order/" + order.getId() + "/photos", "photo", "p.png", "image/png", PIXEL).status());
        assertFalse(device.checklistItem(order.getId(), item.getId()).isDone());
        assertTrue(device.parts(order.getId()).isEmpty());
    }

    @Test
    void startingShowsTheWork() {
        var order = synced("Boiler");
        var started = start(order);
        assertEquals("/order/" + order.getId(), started.path());
        assertEquals(WorkOrder.STARTED, device.order(order.getId()).getStatus());
        assertTrue(started.contains("id=\"checklist\""));
        assertTrue(started.contains("id=\"parts\""));
        assertTrue(started.contains("id=\"photos\""));
        assertTrue(started.contains("/sign-off?order=" + order.getId()));
        // it can't be started twice
        assertEquals(409, browser.post("/order/" + order.getId() + "/start", form()).status());
    }

    @Test
    void checklistItemsAreTickedAndUnticked() {
        var order = synced("Boiler");
        start(order);
        var item = device.checklist(order.getId()).get(1);
        var url = "/order/" + order.getId() + "/check/" + item.getId();

        var ticked = browser.hx(RequestMethod.PATCH, url, form("done", "true"));
        assertEquals(200, ticked.status());
        assertTrue(ticked.text().trim().startsWith("<li id=\"check-" + item.getId() + "\">"));
        assertTrue(ticked.contains(" checked>"));
        assertTrue(device.checklistItem(order.getId(), item.getId()).isDone());

        // an unticked checkbox isn't sent
        var unticked = browser.hx(RequestMethod.PATCH, url, form());
        assertFalse(unticked.contains(" checked>"));
        assertFalse(device.checklistItem(order.getId(), item.getId()).isDone());

        var other = synced("Elevator");
        assertEquals(404, browser.hx(RequestMethod.PATCH, "/order/" + order.getId() + "/check/" + device.checklist(other.getId()).get(0).getId(), form("done", "true")).status());
    }

    @Test
    void partsAreAddedAndRemoved() {
        var order = synced("Boiler");
        start(order);
        var added = browser.hx(RequestMethod.POST, "/order/" + order.getId() + "/parts", form("part", "Gasket <set>", "quantity", "2"));
        assertEquals(200, added.status());
        assertTrue(added.text().trim().startsWith("<section class=\"card\" id=\"parts\">"));
        assertTrue(added.contains("2 &times; Gasket &lt;set&gt;"));
        var part = device.parts(order.getId()).get(0);
        assertEquals("Gasket <set>", part.getPart());

        var removed = browser.hx(RequestMethod.DELETE, "/order/" + order.getId() + "/parts/" + part.getId(), null);
        assertEquals(200, removed.status());
        assertTrue(removed.contains("No parts used yet."));
        assertTrue(device.parts(order.getId()).isEmpty());
    }

    @Test
    void invalidPartsAreRefused() {
        var order = synced("Boiler");
        start(order);
        for (var invalid : new String[][]{{"", "1"}, {"Filter", "0"}, {"Filter", "100"}, {"Filter", "many"}, {"x".repeat(81), "1"}}) {
            var page = browser.hx(RequestMethod.POST, "/order/" + order.getId() + "/parts", form("part", invalid[0], "quantity", invalid[1]));
            assertTrue(page.contains("A part needs a name"), String.join(",", invalid));
        }
        assertTrue(device.parts(order.getId()).isEmpty());
    }

    @Test
    void photosAreUploadedAndServed() {
        var order = synced("Boiler");
        start(order);
        var uploaded = browser.upload("/order/" + order.getId() + "/photos", "photo", "pixel.png", "image/png", PIXEL);
        assertEquals(200, uploaded.status());
        assertTrue(uploaded.text().trim().startsWith("<section class=\"card\" id=\"photos\">"));
        var photo = device.photos(order.getId(), Photo.PHOTO).get(0);
        assertEquals(Fixture.CLOCK.instant(), photo.getTaken());
        assertTrue(uploaded.contains("/photo/" + photo.getId()));

        var served = browser.get("/photo/" + photo.getId());
        assertEquals(200, served.status());
        assertEquals("image/png", served.header("Content-Type"));
        assertTrue(served.header("Cache-Control").contains("immutable"));
        assertArrayEquals(PIXEL, served.bytes());
        assertEquals(404, browser.get("/photo/999").status());
    }

    @Test
    void uploadThatIsntAnImageIsRefused() {
        var order = synced("Boiler");
        start(order);
        var page = browser.upload("/order/" + order.getId() + "/photos", "photo", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(page.contains("That wasn't an image"));
        var missing = browser.hx(RequestMethod.POST, "/order/" + order.getId() + "/photos", form());
        assertTrue(missing.contains("That wasn't an image"));
        assertTrue(device.photos(order.getId(), Photo.PHOTO).isEmpty());
    }

    @Test
    void tooLargePhotoIsRefused() {
        var order = synced("Boiler");
        start(order);
        var page = browser.upload("/order/" + order.getId() + "/photos", "photo", "big.png", "image/png", new byte[FieldSite.PHOTO_LIMIT + 1]);
        assertTrue(page.contains("That wasn't an image, or it was too large."));
        assertTrue(device.photos(order.getId(), Photo.PHOTO).isEmpty());
    }

    @Test
    void unknownOrdersAreMissing() {
        assertEquals(404, browser.get("/order/999").status());
        browser.get("/");
        assertEquals(404, browser.post("/order/999/start", form()).status());
    }

    @Test
    void changesWithoutTheCsrfTokenAreRefused() {
        var order = synced("Boiler");
        browser.get("/");
        var refused = browser.send(RequestMethod.POST, "/order/" + order.getId() + "/start", "application/x-www-form-urlencoded", new byte[0], false);
        assertEquals(403, refused.status());
        assertEquals(WorkOrder.SCHEDULED, device.order(order.getId()).getStatus());
        assertEquals(403, browser.send(RequestMethod.POST, "/sync", "application/x-www-form-urlencoded", new byte[0], false).status());
    }

    @Test
    void staticFilesAreServed() {
        var css = browser.get("/css/workorders.css");
        assertEquals(200, css.status());
        assertTrue(css.header("Content-Type").startsWith("text/css"));
        assertEquals(200, browser.get("/js/htmx.min.js").status());
        assertEquals(200, browser.get("/js/signature.js").status());
    }

    @Test
    void photoOfAFewMegabytesIsAccepted() {
        var order = synced("Boiler");
        start(order);
        var camera = new byte[3 * 1024 * 1024];
        var page = browser.upload("/order/" + order.getId() + "/photos", "photo", "IMG_0001.jpeg", "image/jpeg", camera);
        assertFalse(page.contains("That wasn't an image"));
        var photos = device.photos(order.getId(), Photo.PHOTO);
        assertEquals(1, photos.size());
        assertEquals(camera.length, device.photo(photos.get(0).getId()).getData().length);
    }

    @Test
    void scriptsCantBeUploadedAsPhotos() {
        var order = synced("Boiler");
        start(order);
        var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        assertTrue(browser.upload("/order/" + order.getId() + "/photos", "photo", "x.svg", "image/svg+xml", svg).contains("That wasn't an image"));
        assertTrue(device.photos(order.getId(), Photo.PHOTO).isEmpty());
    }

    @Test
    void servedPhotosCantRunScripts() {
        var order = synced("Boiler");
        var stored = new Photo();
        stored.setOrderId(order.getId());
        stored.setContentType("text/html");
        stored.setData("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        stored.setTaken(CLOCK.instant());
        device.add(stored);
        var served = browser.get("/photo/" + stored.getId());
        assertEquals("application/octet-stream", served.header("Content-Type"));
        assertEquals("nosniff", served.header("X-Content-Type-Options"));
        assertTrue(served.header("Content-Security-Policy").contains("sandbox"));
    }

    @Test
    void finishedOrderCantBeChanged() {
        var order = synced("Boiler");
        start(order);
        var part = new PartUsed();
        part.setOrderId(order.getId());
        part.setPart("Filter");
        device.add(part);
        var finished = device.order(order.getId());
        finished.setStatus(WorkOrder.FINISHED);
        device.save(finished);

        var item = device.checklist(order.getId()).get(0);
        var path = "/order/" + order.getId();
        var refused = browser.hx(RequestMethod.PATCH, path + "/check/" + item.getId(), form("done", "true"));
        assertEquals(409, refused.status());
        assertEquals("This order is finished.", refused.text());
        assertEquals(409, browser.hx(RequestMethod.POST, path + "/parts", form("part", "Filter", "quantity", "1")).status());
        assertEquals(409, browser.hx(RequestMethod.DELETE, path + "/parts/" + part.getId(), null).status());
        assertEquals(409, browser.upload(path + "/photos", "photo", "p.png", "image/png", PIXEL).status());
        assertFalse(device.checklistItem(order.getId(), item.getId()).isDone());
        assertEquals(1, device.parts(order.getId()).size());
        assertTrue(device.photos(order.getId(), Photo.PHOTO).isEmpty());
    }

    @Test
    void overdueOrderIsTodaysWorkWithItsDate() {
        order(office, TODAY.minusDays(1), "13:00 to 15:00", "Elevator", "Sam");
        var today = FieldSiteTest.section(sync().text(), "<h2>Today</h2>", "<h2>Later</h2>");
        assertTrue(today.contains("Monday 16 November, 13:00 to 15:00"));
        assertTrue(today.contains("Customer of Elevator"));
    }

    @Test
    void syncsAtTheSameTimeDontReceiveAnOrderTwice()
    throws Exception {
        order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var office_link = link;
        // slow enough for the second sync to arrive while the first is still busy
        link = request -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return office_link.exchange(request);
        };
        browser.get("/");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> browser.send(RequestMethod.POST, "/sync", "application/x-www-form-urlencoded", new byte[0], true));
            var second = pool.submit(() -> browser.send(RequestMethod.POST, "/sync", "application/x-www-form-urlencoded", new byte[0], true));
            assertFalse(first.get().header("Location").contains("sync=failed"));
            assertFalse(second.get().header("Location").contains("sync=failed"));
        } finally {
            pool.shutdown();
        }
        assertEquals(1, device.orders().size());
    }

    @Test
    void reportsThatTheOfficeRefusedAreMentioned() {
        var page = browser.get("/?sent=0&refused=2&received=0");
        assertTrue(page.contains("The office didn't accept 2, they're still waiting here."));
    }

    @Test
    void signOffsLastAWorkingDay() {
        assertTrue(rife.config.RifeConfig.engine().getContinuationDuration() >= java.util.concurrent.TimeUnit.HOURS.toMillis(10));
    }

    private Browser.Page sync() {
        browser.get("/");
        return browser.post("/sync", form());
    }

    private WorkOrder synced(String equipment) {
        var scheduled = order(office, TODAY, "08:00 to 10:00", equipment, "Sam");
        Sync.run(device, "Sam", link);
        return device.orderFromOffice(scheduled.getId());
    }

    private Browser.Page start(WorkOrder order) {
        browser.get("/order/" + order.getId());
        return browser.post("/order/" + order.getId() + "/start", form());
    }

    static String section(String text, String from, String to) {
        return text.substring(text.indexOf(from), text.indexOf(to));
    }
}
