package workorders;

import rife.database.DbQueryManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

final class Fixture {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC);
    static final String TOKEN = "test-token";
    static final List<String> TECHNICIANS = List.of("Sam", "Alex");
    // the smallest PNG, a single pixel
    static final byte[] PIXEL = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private Fixture() {
    }

    static Store store() {
        var store = Store.h2("mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store.migrate();
        return store;
    }

    static void close(Store store) {
        new DbQueryManager(store.datasource()).executeUpdate("SHUTDOWN");
        store.close();
    }

    static WorkOrder order(Store store, LocalDate day, String arrival, String equipment, String technician) {
        var order = new WorkOrder();
        order.setScheduledFor(day);
        order.setArrival(arrival);
        order.setCustomer("Customer of " + equipment);
        order.setAddress("1 Test Street");
        order.setPhone("+44 20 7946 0000");
        order.setEquipment(equipment);
        order.setProblem("It doesn't work.");
        order.setTechnician(technician);
        return store.create(order);
    }
}
