package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rife.engine.RequestMethod;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static workorders.Browser.form;
import static workorders.Fixture.*;

class OfficeSiteTest {
    private final Store office = store();
    private final Browser browser = new Browser(new OfficeSite(office, TOKEN, TECHNICIANS, CLOCK));

    @AfterEach
    void close() {
        browser.close();
        Fixture.close(office);
    }

    @Test
    void emptyBoard() {
        var board = browser.get("/");
        assertEquals(200, board.status());
        assertTrue(board.contains("No orders yet."));
    }

    @Test
    void boardGroupsTheOrdersByDay() {
        var tomorrow = order(office, TODAY.plusDays(1), "08:00 to 10:00", "Elevator", "Alex");
        var today = order(office, TODAY, "13:00 to 15:00", "Boiler", "Sam");
        var board = browser.get("/");
        var first = board.text().indexOf("<h2>Tuesday 17 November</h2>");
        var second = board.text().indexOf("<h2>Wednesday 18 November</h2>");
        assertTrue(first > 0 && second > first);
        var today_rows = board.text().substring(first, second);
        assertTrue(today_rows.contains(today.getNumber()));
        assertFalse(today_rows.contains(tomorrow.getNumber()));
        assertTrue(board.text().substring(second).contains(tomorrow.getNumber()));
        assertTrue(board.contains("href=\"" + browser.runner.getServerRootUrl() + "/order/" + today.getId() + "\""));
    }

    @Test
    void newOrderFormStartsOnToday() {
        var page = browser.get("/new");
        assertEquals(200, page.status());
        assertTrue(page.contains("name=\"scheduledFor\" value=\"2026-11-17\""));
        for (var equipment : WorkOrderMetaData.EQUIPMENT) {
            assertTrue(page.contains("<option value=\"" + equipment + "\">"), equipment);
        }
        for (var arrival : WorkOrderMetaData.ARRIVALS) {
            assertTrue(page.contains("<option value=\"" + arrival + "\">"), arrival);
        }
    }

    @Test
    void newOrderIsScheduledWithItsChecklist() {
        browser.get("/new");
        var created = browser.post("/new", form("customer", "Ward & Daughters", "address", "5 Mill Road", "phone", "",
            "equipment", "Heat pump", "problem", "Noisy", "technician", "Sam", "scheduledFor", "2026-11-18", "arrival", "10:00 to 12:00"));
        assertTrue(created.path().startsWith("/order/"), created.text().replaceAll("(?s).*(<ul class=\"errors\".*?</ul>).*", "$1"));
        var order = office.orders().get(0);
        assertEquals("Ward & Daughters", order.getCustomer());
        assertEquals(TODAY.plusDays(1), order.getScheduledFor());
        assertEquals(WorkOrder.SCHEDULED, order.getStatus());
        assertTrue(created.contains("Ward &amp; Daughters"));
        assertTrue(created.contains(Checklists.forEquipment("Heat pump").get(0)));
        assertFalse(created.contains("Report"));
    }

    @Test
    void newOrderIsValidated() {
        browser.get("/new");
        var refused = browser.post("/new", form("customer", "", "address", "", "equipment", "Toaster", "problem", "",
            "technician", "", "scheduledFor", "", "arrival", "midnight"));
        assertEquals(200, refused.status());
        for (var message : new String[]{"Who is the customer?", "Where does the technician go?", "Describe the problem.",
                                        "Who does the job?", "Pick a date.", "Pick the equipment.", "Pick an arrival time."}) {
            assertTrue(refused.contains(message), message);
        }
        var too_long = browser.post("/new", form("customer", "x".repeat(81), "address", "Here", "equipment", "Boiler", "problem", "Broken",
            "technician", "Sam", "scheduledFor", "2026-11-18", "arrival", "08:00 to 10:00"));
        assertTrue(too_long.contains("That is too long."));
        assertTrue(too_long.contains("value=\"Here\""), "what was entered stays");
        assertTrue(office.orders().isEmpty());
    }

    @Test
    void onlyKnownTechniciansGetOrders() {
        var page = browser.get("/new");
        assertTrue(page.contains("<option value=\"Sam\" >Sam</option>"));
        var refused = browser.post("/new", form("customer", "X", "address", "Here", "equipment", "Boiler", "problem", "Broken",
            "technician", "Sma", "scheduledFor", "2026-11-18", "arrival", "08:00 to 10:00"));
        assertTrue(refused.contains("Pick one of the technicians."));
        assertTrue(office.orders().isEmpty());
    }

    @Test
    void orderShowsTheReportOnceItsFinished() {
        var order = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var planned = browser.get("/order/" + order.getId());
        assertTrue(planned.contains("Scheduled"));
        assertFalse(planned.contains("<h2>Report</h2>"));

        order.setNotes("Replaced <the> valve.");
        order.setMinutes(30);
        order.setSignedBy("Jo");
        order.setFinishedAt(Instant.parse("2026-11-17T10:00:00Z"));
        order.setStatus(WorkOrder.FINISHED);
        office.save(order);
        var photo = new Photo();
        photo.setOrderId(order.getId());
        photo.setContentType("image/png");
        photo.setData(PIXEL);
        photo.setTaken(Instant.parse("2026-11-17T09:50:00Z"));
        office.add(photo);

        var finished = browser.get("/order/" + order.getId());
        assertTrue(finished.contains("<h2>Report</h2>"));
        assertTrue(finished.contains("Replaced &lt;the&gt; valve."));
        assertTrue(finished.contains("after 30 minutes, signed by Jo."));
        assertTrue(finished.contains("<h3>Photos</h3>"));
        assertFalse(finished.contains("<h3>Signature</h3>"));
        assertArrayEquals(PIXEL, browser.get("/photo/" + photo.getId()).bytes());
    }

    @Test
    void unknownOrderIsMissing() {
        assertEquals(404, browser.get("/order/999").status());
        assertEquals(404, browser.get("/photo/999").status());
    }

    @Test
    void schedulingWithoutTheCsrfTokenIsRefused() {
        var refused = browser.send(RequestMethod.POST, "/new", "application/x-www-form-urlencoded", Browser.encode(form("customer", "X")), false);
        assertEquals(403, refused.status());
        assertTrue(office.orders().isEmpty());
    }

    @Test
    void syncIsntAPage() {
        assertEquals(404, browser.get("/api/sync").status());
    }
}
