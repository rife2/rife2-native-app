package workorders;

import java.time.LocalDate;
import java.util.List;

// orders that an empty office starts with, so that there's something to do
public final class Demo {
    public static final String TECHNICIAN = "Sam Rivera";
    public static final List<String> TECHNICIANS = List.of(TECHNICIAN, "Alex Moreau");

    private Demo() {
    }

    // a new day of work as soon as nothing is planned from today on, so that
    // the demo still has work the day after
    public static void seed(Store store, LocalDate today) {
        if (store.hasOrdersFrom(today)) {
            return;
        }
        order(store, today, "08:00 to 10:00", "Bakery Lindqvist", "12 Harbour Street, Lowmoor", "+44 20 7946 0101",
            "Boiler", "The boiler cuts out a few minutes after the ovens start in the morning.", TECHNICIAN);
        order(store, today, "10:00 to 12:00", "Greenfield Primary School", "3 School Lane, Lowmoor", "+44 20 7946 0102",
            "Heat pump", "Yearly service. The caretaker hears a rattle from the outdoor unit.", TECHNICIAN);
        order(store, today, "13:00 to 15:00", "Riverside Apartments", "88 Quay Road, Lowmoor", "+44 20 7946 0103",
            "Elevator", "Inspection due. Residents say the car stops a little below the third floor.", TECHNICIAN);
        order(store, today.plusDays(1), "08:00 to 10:00", "Oakridge Farm", "Oakridge Lane, Upper Lowmoor", "+44 20 7946 0104",
            "Solar inverter", "The inverter shows error 31 since the storm on Saturday.", TECHNICIAN);
        order(store, today, "10:00 to 12:00", "Town Library", "1 Market Square, Lowmoor", "+44 20 7946 0105",
            "Boiler", "No hot water in the staff kitchen.", TECHNICIANS.get(1));
    }

    private static void order(Store store, LocalDate day, String arrival, String customer, String address, String phone,
                              String equipment, String problem, String technician) {
        var order = new WorkOrder();
        order.setScheduledFor(day);
        order.setArrival(arrival);
        order.setCustomer(customer);
        order.setAddress(address);
        order.setPhone(phone);
        order.setEquipment(equipment);
        order.setProblem(problem);
        order.setTechnician(technician);
        store.create(order);
    }
}
