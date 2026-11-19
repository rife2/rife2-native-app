package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rife.engine.RequestMethod;
import rife.engine.SiteRequest;
import rife.engine.SiteRunner;
import rife.json.Json;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static workorders.Fixture.*;

class SyncTest {
    private final Store office = store();
    private final Store device = store();
    private final SiteRunner officeRunner = new SiteRunner().start(new OfficeSite(office, TOKEN, TECHNICIANS, CLOCK));
    private final Sync.Link link = Links.runner(officeRunner, TOKEN);

    @AfterEach
    void close() {
        officeRunner.stop();
        Fixture.close(office);
        Fixture.close(device);
    }

    @Test
    void deviceReceivesTheScheduledOrdersOfItsTechnician() {
        var mine = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        order(office, TODAY, "08:00 to 10:00", "Boiler", "Alex");
        // an overdue order is still to be done
        var overdue = order(office, TODAY.minusDays(1), "08:00 to 10:00", "Boiler", "Sam");

        assertEquals(new Sync.Result(0, 0, 2), Sync.run(device, "Sam", link));

        var orders = device.orders();
        assertEquals(2, orders.size());
        assertEquals(overdue.getId(), orders.get(0).getOfficeId());
        var copy = orders.get(1);
        assertEquals(mine.getId(), copy.getOfficeId());
        assertEquals(mine.getNumber(), copy.getNumber());
        assertEquals(mine.getCustomer(), copy.getCustomer());
        assertEquals(mine.getPhone(), copy.getPhone());
        assertEquals(TODAY, copy.getScheduledFor());
        assertEquals(WorkOrder.SCHEDULED, copy.getStatus());
        // the device has its own checklist for the order
        assertEquals(Checklists.forEquipment("Boiler").size(), device.checklist(copy.getId()).size());
    }

    @Test
    void syncingAgainDoesntDuplicate() {
        order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        Sync.run(device, "Sam", link);
        assertEquals(new Sync.Result(0, 0, 0), Sync.run(device, "Sam", link));
        assertEquals(1, device.orders().size());
    }

    @Test
    void changesOfTheOfficeReachOrdersThatWerentStarted() {
        var unstarted = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var started = order(office, TODAY, "10:00 to 12:00", "Boiler", "Sam");
        Sync.run(device, "Sam", link);
        var started_copy = device.orderFromOffice(started.getId());
        started_copy.setStatus(WorkOrder.STARTED);
        device.save(started_copy);

        unstarted.setArrival("13:00 to 15:00");
        office.save(unstarted);
        started.setArrival("15:00 to 17:00");
        office.save(started);
        Sync.run(device, "Sam", link);

        assertEquals("13:00 to 15:00", device.orderFromOffice(unstarted.getId()).getArrival());
        assertEquals("10:00 to 12:00", device.orderFromOffice(started.getId()).getArrival());
    }

    @Test
    void finishedOrderIsReportedWithEverything() {
        var scheduled = order(office, TODAY, "08:00 to 10:00", "Heat pump", "Sam");
        Sync.run(device, "Sam", link);
        var order = device.orderFromOffice(scheduled.getId());
        var items = device.checklist(order.getId());
        items.get(0).setDone(true);
        device.save(items.get(0));
        var part = new PartUsed();
        part.setOrderId(order.getId());
        part.setPart("Filter");
        part.setQuantity(2);
        device.add(part);
        device.add(photo(order.getId(), Photo.PHOTO));
        device.replaceSignature(photo(order.getId(), Photo.SIGNATURE));
        finish(order);

        assertEquals(new Sync.Result(1, 0, 0), Sync.run(device, "Sam", link));

        assertEquals(WorkOrder.REPORTED, device.order(order.getId()).getStatus());
        var filed = office.order(scheduled.getId());
        assertEquals(WorkOrder.FINISHED, filed.getStatus());
        assertEquals("Cleaned the fins.", filed.getNotes());
        assertEquals(45, filed.getMinutes());
        assertEquals("Jo Customer", filed.getSignedBy());
        assertEquals(Instant.parse("2026-11-17T10:15:00Z"), filed.getFinishedAt());
        var checklist = office.checklist(filed.getId());
        assertEquals(Checklists.forEquipment("Heat pump").size(), checklist.size());
        assertTrue(checklist.get(0).isDone());
        assertFalse(checklist.get(1).isDone());
        assertEquals(1, office.parts(filed.getId()).size());
        assertEquals(2, office.parts(filed.getId()).get(0).getQuantity());
        assertEquals(1, office.photos(filed.getId(), Photo.PHOTO).size());
        var signature = office.photos(filed.getId(), Photo.SIGNATURE);
        assertEquals(1, signature.size());
        assertArrayEquals(PIXEL, office.photo(signature.get(0).getId()).getData());
        // the finished order isn't sent back to the device
        assertEquals(new Sync.Result(0, 0, 0), Sync.run(device, "Sam", link));
    }

    @Test
    void reportThatTheOfficeAlreadyHasIsAcknowledgedOnce() {
        var scheduled = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        Sync.run(device, "Sam", link);
        var order = device.orderFromOffice(scheduled.getId());
        device.add(photo(order.getId(), Photo.PHOTO));
        finish(order);

        // the answer of the first sync got lost on the way back
        var lost = new AtomicReference<Boolean>(true);
        Sync.Link flaky = request -> {
            var answer = link.exchange(request);
            if (lost.getAndSet(false)) {
                throw new IllegalStateException("connection reset");
            }
            return answer;
        };
        assertThrows(IllegalStateException.class, () -> Sync.run(device, "Sam", flaky));
        assertEquals(WorkOrder.FINISHED, device.order(order.getId()).getStatus());

        assertEquals(new Sync.Result(1, 0, 0), Sync.run(device, "Sam", flaky));
        assertEquals(WorkOrder.REPORTED, device.order(order.getId()).getStatus());
        assertEquals(1, office.photos(scheduled.getId(), Photo.PHOTO).size());
    }

    @Test
    void reportForAnotherTechniciansOrderIsRefused() {
        var alexs = order(office, TODAY, "08:00 to 10:00", "Boiler", "Alex");
        var request = """
            {"technician": "Sam", "reports": [{"officeId": %d, "notes": "Done", "minutes": 10, "signedBy": "X",
            "finishedAt": "2026-11-17T10:00:00Z", "checklist": [], "parts": [], "photos": []}]}""".formatted(alexs.getId());
        var answer = Json.parseObject(link.exchange(request));
        assertEquals(0, answer.getArray("received").size());
        assertEquals(WorkOrder.SCHEDULED, office.order(alexs.getId()).getStatus());
    }

    @Test
    void reportForAnUnknownOrderIsRefused() {
        var request = """
            {"technician": "Sam", "reports": [{"officeId": 999, "notes": "Done", "minutes": 10, "signedBy": "X",
            "finishedAt": "2026-11-17T10:00:00Z", "checklist": [], "parts": [], "photos": []}]}""";
        assertEquals(0, Json.parseObject(link.exchange(request)).getArray("received").size());
    }

    @Test
    void officeRefusesSyncWithoutTheToken() {
        var request = new SiteRequest(RequestMethod.POST).contentType("application/json")
            .bodyAsBytes("{\"technician\": \"Sam\", \"reports\": []}".getBytes());
        var response = officeRunner.handle("/api/sync", request);
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));

        var wrong = Links.runner(officeRunner, "not-the-token");
        assertThrows(IllegalStateException.class, () -> Sync.run(device, "Sam", wrong));
    }

    @Test
    void failedSyncChangesNothingOnTheDevice() {
        var scheduled = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        Sync.run(device, "Sam", link);
        var order = device.orderFromOffice(scheduled.getId());
        finish(order);
        assertThrows(IllegalStateException.class, () -> Sync.run(device, "Sam", request -> {
            throw new IllegalStateException("offline");
        }));
        assertEquals(WorkOrder.FINISHED, device.order(order.getId()).getStatus());
        assertEquals(WorkOrder.SCHEDULED, office.order(scheduled.getId()).getStatus());
    }

    @Test
    void photoThatIsntAnImageIsRefused() {
        var scheduled = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        Sync.run(device, "Sam", link);
        var order = device.orderFromOffice(scheduled.getId());
        var page = photo(order.getId(), Photo.PHOTO);
        page.setContentType("text/html");
        device.add(page);
        finish(order);

        assertEquals(new Sync.Result(0, 1, 0), Sync.run(device, "Sam", link));
        assertEquals(WorkOrder.FINISHED, device.order(order.getId()).getStatus());
        assertEquals(WorkOrder.SCHEDULED, office.order(scheduled.getId()).getStatus());
        assertTrue(office.photos(scheduled.getId(), Photo.PHOTO).isEmpty());
    }

    @Test
    void reportThatTheOfficeRefusesIsntCountedAsSent() {
        var scheduled = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        Sync.run(device, "Sam", link);
        // the dispatcher gave the job to someone else meanwhile
        scheduled.setTechnician("Alex");
        office.save(scheduled);
        var order = device.orderFromOffice(scheduled.getId());
        finish(order);

        assertEquals(new Sync.Result(0, 1, 0), Sync.run(device, "Sam", link));
        assertEquals(WorkOrder.FINISHED, device.order(order.getId()).getStatus());
    }

    @Test
    void eachReportTravelsInARequestOfItsOwn() {
        var first = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var second = order(office, TODAY, "10:00 to 12:00", "Boiler", "Sam");
        Sync.run(device, "Sam", link);
        finish(device.orderFromOffice(first.getId()));
        finish(device.orderFromOffice(second.getId()));

        var reports = new java.util.ArrayList<Integer>();
        Sync.Link counting = request -> {
            reports.add(Json.parseObject(request).getArray("reports").size());
            return link.exchange(request);
        };
        assertEquals(new Sync.Result(2, 0, 0), Sync.run(device, "Sam", counting));
        assertEquals(java.util.List.of(1, 1), reports);
    }

    @Test
    void sameReportSentConcurrentlyIsFiledOnce()
    throws Exception {
        var scheduled = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var request = """
            {"technician": "Sam", "reports": [{"officeId": %d, "notes": "Done", "minutes": 10, "signedBy": "Jo",
            "finishedAt": "2026-11-17T10:00:00Z", "checklist": [], "parts": [{"part": "Filter", "quantity": 1}],
            "photos": [{"kind": "photo", "contentType": "image/png", "taken": "2026-11-17T09:00:00Z", "data": "%s"}]}]}"""
            .formatted(scheduled.getId(), java.util.Base64.getEncoder().encodeToString(PIXEL));
        var threads = 8;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var answers = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (var i = 0; i < threads; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return link.exchange(request);
                }));
            }
            start.countDown();
            for (var answer : answers) {
                assertEquals(1, Json.parseObject(answer.get()).getArray("received").size());
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(1, office.parts(scheduled.getId()).size());
        assertEquals(1, office.photos(scheduled.getId(), Photo.PHOTO).size());
    }

    @Test
    void reportThatCantBeReadDoesntKeepTheOthersFromBeingFiled() {
        var broken = order(office, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var fine = order(office, TODAY, "10:00 to 12:00", "Boiler", "Sam");
        var request = """
            {"technician": "Sam", "reports": [
            {"officeId": %d, "notes": "Done", "minutes": 10, "signedBy": "Jo", "finishedAt": "2026-11-17T10:00:00Z",
             "checklist": [], "parts": [], "photos": [{"kind": "photo", "contentType": "image/png", "taken": "2026-11-17T09:00:00Z", "data": "***"}]},
            {"officeId": %d, "notes": "Done", "minutes": 10, "signedBy": "Jo", "finishedAt": "2026-11-17T11:00:00Z",
             "checklist": [], "parts": [], "photos": []}]}""".formatted(broken.getId(), fine.getId());
        var received = Json.parseObject(link.exchange(request)).getArray("received");
        assertEquals(1, received.size());
        assertEquals(fine.getId(), received.getInt(0));
        assertEquals(WorkOrder.SCHEDULED, office.order(broken.getId()).getStatus());
    }

    @Test
    void requestThatCantBeReadIsABadRequest() {
        var request = new SiteRequest(RequestMethod.POST).contentType("application/json")
            .header("Authorization", "Bearer " + TOKEN).bodyAsBytes("{not json".getBytes());
        assertEquals(400, officeRunner.handle("/api/sync", request).getStatus());
    }

    private void finish(WorkOrder order) {
        order.setNotes("Cleaned the fins.");
        order.setMinutes(45);
        order.setSignedBy("Jo Customer");
        order.setFinishedAt(Instant.parse("2026-11-17T10:15:00Z"));
        order.setStatus(WorkOrder.FINISHED);
        device.save(order);
    }

    private static Photo photo(int orderId, String kind) {
        var photo = new Photo();
        photo.setOrderId(orderId);
        photo.setKind(kind);
        photo.setContentType("image/png");
        photo.setData(PIXEL);
        photo.setTaken(Instant.parse("2026-11-17T10:00:00Z"));
        return photo;
    }
}
