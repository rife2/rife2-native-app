package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rife.engine.RequestMethod;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static workorders.Browser.form;
import static workorders.Fixture.*;

// a day of work, through the pages of the office and of a technician's device
class WorkdayTest {
    private final Store officeStore = store();
    private final Store deviceStore = store();
    private final Browser office = new Browser(new OfficeSite(officeStore, TOKEN, TECHNICIANS, CLOCK));
    private final Browser device = new Browser(new FieldSite(deviceStore, "Sam", Links.runner(office.runner, TOKEN), CLOCK));

    @AfterEach
    void close() {
        device.close();
        office.close();
        Fixture.close(officeStore);
        Fixture.close(deviceStore);
    }

    @Test
    void fromDispatchToReport() {
        // the dispatcher schedules a job
        office.get("/new");
        var scheduled = office.post("/new", form("customer", "Bakery Lindqvist", "address", "12 Harbour Street", "phone", "+44 20 7946 0101",
            "equipment", "Boiler", "problem", "Cuts out in the morning.", "technician", "Sam", "scheduledFor", "2026-11-17", "arrival", "08:00 to 10:00"));
        var number = officeStore.orders().get(0).getNumber();
        assertTrue(scheduled.contains(number));

        // the technician syncs in the morning
        device.get("/");
        var today = device.post("/sync", form());
        assertTrue(today.contains("received 1 new"));
        assertTrue(FieldSiteTest.section(today.text(), "<h2>Today</h2>", "<h2>Later</h2>").contains("Bakery Lindqvist"));
        var url = today.attribute("href", "class=\"job\"");
        var order = device.get(url);
        assertTrue(order.contains("Cuts out in the morning."));

        // and works on site, without a connection
        var path = order.path();
        var started = device.post(path + "/start", form());
        var first = deviceStore.checklist(deviceStore.orders().get(0).getId());
        for (var item : first) {
            device.hx(RequestMethod.PATCH, path + "/check/" + item.getId(), form("done", "true"));
        }
        device.hx(RequestMethod.POST, path + "/parts", form("part", "Ignition electrode", "quantity", "1"));
        device.upload(path + "/photos", "photo", "burner.png", "image/png", PIXEL);

        var review = device.get(started.attribute("href", "class=\"button\" href").replace(device.runner.getServerRootUrl(), ""));
        assertTrue(review.contains("Ignition electrode"));
        assertTrue(review.contains("1 photo."));
        var report = next(review, form("next", "1"));
        var signature = next(report, form("notes", "Replaced the ignition electrode.", "minutes", "55", "next", "1"));
        var done = next(signature, form("signedBy", "Anna Lindqvist",
            "signature", "data:image/png;base64," + Base64.getEncoder().encodeToString(PIXEL), "finish", "1"));
        assertTrue(done.contains("signed by Anna Lindqvist"));
        assertTrue(device.get("/").contains("<strong>1</strong> finished, waiting to be sent."));

        // back in range of the network
        var synced = device.post("/sync", form());
        assertTrue(synced.contains("Sent 1 finished, received 0 new."));
        assertTrue(synced.contains("Nothing waiting to be sent."));
        assertTrue(FieldSiteTest.section(synced.text(), "<h2>Done</h2>", "</main>").contains("Sent"));

        // the office reads the report
        var board = office.get("/");
        assertTrue(board.contains("status-finished"));
        var filed = office.get("/order/" + officeStore.orders().get(0).getId());
        assertTrue(filed.contains("Replaced the ignition electrode."));
        assertTrue(filed.contains("after 55 minutes, signed by Anna Lindqvist."));
        assertTrue(filed.contains("<h3>Signature</h3>"));
        assertTrue(filed.contains("<h3>Photos</h3>"));
        assertTrue(filed.contains("1 &times; Ignition electrode"));
        assertFalse(filed.contains("not done"));
    }

    private Browser.Page next(Browser.Page page, Map<String, String> fields) {
        var submitted = new LinkedHashMap<>(page.hidden());
        submitted.remove("signature");
        submitted.putAll(fields);
        return device.post("/sign-off", submitted);
    }
}
