package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static workorders.Fixture.*;

class StoreTest {
    private final Store store = store();

    @AfterEach
    void close() {
        Fixture.close(store);
    }

    @Test
    void migratingTwiceKeepsTheOrders() {
        order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        store.migrate();
        assertEquals(1, store.orders().size());
    }

    @Test
    void createdOrderGetsNumberAndChecklistOfItsEquipment() {
        var order = order(store, TODAY, "08:00 to 10:00", "Elevator", "Sam");
        assertEquals("WO-" + (1000 + order.getId()), store.order(order.getId()).getNumber());
        assertEquals(WorkOrder.SCHEDULED, store.order(order.getId()).getStatus());
        var labels = store.checklist(order.getId()).stream().map(ChecklistItem::getLabel).toList();
        assertEquals(Checklists.forEquipment("Elevator"), labels);
        assertTrue(store.checklist(order.getId()).stream().noneMatch(ChecklistItem::isDone));
    }

    @Test
    void ordersAreSortedByDayThenArrival() {
        var later = order(store, TODAY.plusDays(1), "08:00 to 10:00", "Boiler", "Sam");
        var afternoon = order(store, TODAY, "13:00 to 15:00", "Boiler", "Sam");
        var morning = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        assertEquals(List.of(morning.getId(), afternoon.getId(), later.getId()),
            store.orders().stream().map(WorkOrder::getId).toList());
    }

    @Test
    void scheduledForHasTheTechniciansOrdersStillToDoIncludingOverdueOnes() {
        var past = order(store, TODAY.minusDays(1), "08:00 to 10:00", "Boiler", "Sam");
        var today = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var tomorrow = order(store, TODAY.plusDays(1), "08:00 to 10:00", "Boiler", "Sam");
        order(store, TODAY, "08:00 to 10:00", "Boiler", "Alex");
        var finished = order(store, TODAY, "10:00 to 12:00", "Boiler", "Sam");
        finished.setStatus(WorkOrder.FINISHED);
        store.save(finished);

        var ids = store.scheduledFor("Sam").stream().map(WorkOrder::getId).toList();
        assertEquals(List.of(past.getId(), today.getId(), tomorrow.getId()), ids);
    }

    @Test
    void orderFromOfficeFindsTheLocalCopy() {
        var order = new WorkOrder();
        order.setOfficeId(42);
        order.setNumber("WO-1042");
        order.setScheduledFor(TODAY);
        order.setArrival("08:00 to 10:00");
        order.setCustomer("Customer");
        order.setAddress("Address");
        order.setEquipment("Boiler");
        order.setProblem("Problem");
        order.setTechnician("Sam");
        store.create(order);
        assertEquals("WO-1042", store.orderFromOffice(42).getNumber());
        assertNull(store.orderFromOffice(43));
    }

    @Test
    void deviceHasEachOrderOfTheOfficeOnce() {
        var first = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        first.setOfficeId(7);
        store.save(first);
        var second = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        second.setOfficeId(7);
        assertThrows(RuntimeException.class, () -> store.save(second));
    }

    @Test
    void claimOnlyChangesAnOrderInTheExpectedStatus() {
        var order = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        assertFalse(store.claim(order.getId(), WorkOrder.STARTED, WorkOrder.FINISHED));
        assertTrue(store.claim(order.getId(), WorkOrder.SCHEDULED, WorkOrder.FINISHED));
        assertFalse(store.claim(order.getId(), WorkOrder.SCHEDULED, WorkOrder.FINISHED));
        assertEquals(WorkOrder.FINISHED, store.order(order.getId()).getStatus());
    }

    @Test
    void claimOfATechnicianOnlyChangesTheirOrder() {
        var order = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        assertFalse(store.claim(order.getId(), "Alex", WorkOrder.SCHEDULED, WorkOrder.FINISHED));
        assertEquals(WorkOrder.SCHEDULED, store.order(order.getId()).getStatus());
        assertTrue(store.claim(order.getId(), "Sam", WorkOrder.SCHEDULED, WorkOrder.FINISHED));
    }

    @Test
    void checklistItemOnlyBelongsToItsOrder() {
        var one = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var other = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var item = store.checklist(one.getId()).get(0);
        assertNotNull(store.checklistItem(one.getId(), item.getId()));
        assertNull(store.checklistItem(other.getId(), item.getId()));
    }

    @Test
    void partOfAnotherOrderIsntDeleted() {
        var one = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var other = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var part = new PartUsed();
        part.setOrderId(one.getId());
        part.setPart("Gasket set");
        part.setQuantity(1);
        store.add(part);
        assertFalse(store.deletePart(other.getId(), part.getId()));
        assertTrue(store.deletePart(one.getId(), part.getId()));
        assertTrue(store.parts(one.getId()).isEmpty());
    }

    @Test
    void photoListsLeaveTheDataOut() {
        var order = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        var photo = photo(order.getId(), Photo.PHOTO);
        store.add(photo);
        var listed = store.photos(order.getId(), Photo.PHOTO);
        assertEquals(1, listed.size());
        assertNull(listed.get(0).getData());
        assertTrue(store.photos(order.getId(), Photo.SIGNATURE).isEmpty());
        assertArrayEquals(PIXEL, store.photo(photo.getId()).getData());
        assertArrayEquals(PIXEL, store.photosWithData(order.getId()).get(0).getData());
    }

    @Test
    void signatureIsReplaced() {
        var order = order(store, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        store.replaceSignature(photo(order.getId(), Photo.SIGNATURE));
        var second = photo(order.getId(), Photo.SIGNATURE);
        store.replaceSignature(second);
        var signatures = store.photos(order.getId(), Photo.SIGNATURE);
        assertEquals(1, signatures.size());
        assertEquals(second.getId(), signatures.get(0).getId());
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
