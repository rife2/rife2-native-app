package workorders;

import rife.template.Template;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

// what the office and the technician's pages show of an order in the same way
final class Views {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("d MMMM 'at' HH:mm", Locale.ENGLISH);

    private Views() {
    }

    static String day(LocalDate date) {
        return DAY.format(date);
    }

    static String moment(Instant instant, ZoneId zone) {
        return MOMENT.format(instant.atZone(zone));
    }

    static String count(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    static String statusLabel(String status) {
        return switch (status) {
            case WorkOrder.STARTED -> "In progress";
            case WorkOrder.FINISHED -> "Finished";
            case WorkOrder.REPORTED -> "Sent";
            default -> "Scheduled";
        };
    }

    static void order(Template t, WorkOrder order, Store store, Function<Photo, String> photoUrl, Clock clock) {
        t.setValueEncoded("number", order.getNumber());
        t.setValue("status", order.getStatus());
        t.setValue("status_label", statusLabel(order.getStatus()));
        t.setValueEncoded("customer", order.getCustomer());
        t.setValueEncoded("address", order.getAddress());
        if (order.getPhone() != null && !order.getPhone().isBlank()) {
            t.setValueEncoded("phone", order.getPhone());
            t.setValueEncoded("phone_dial", order.getPhone().replaceAll("[^+0-9]", ""));
            t.setBlock("phone_line", "phone_block");
        }
        t.setValue("scheduled", day(order.getScheduledFor()));
        t.setValueEncoded("arrival", order.getArrival());
        t.setValueEncoded("equipment", order.getEquipment());
        t.setValueEncoded("technician", order.getTechnician());
        t.setValueEncoded("problem", order.getProblem());
        if (order.isDone()) {
            report(t, order, store, photoUrl, clock);
        }
    }

    private static void report(Template t, WorkOrder order, Store store, Function<Photo, String> photoUrl, Clock clock) {
        t.setValue("finished_at", moment(order.getFinishedAt(), clock.getZone()));
        t.setValue("minutes", order.getMinutes());
        t.setValueEncoded("signed_by", order.getSignedBy());
        t.setValueEncoded("notes", order.getNotes());
        results(t, store.checklist(order.getId()));
        usedParts(t, store.parts(order.getId()));
        for (var signature : store.photos(order.getId(), Photo.SIGNATURE)) {
            t.setValue("signature_url", photoUrl.apply(signature));
            t.setBlock("signature_line", "signature_block");
        }
        var photos = store.photos(order.getId(), Photo.PHOTO);
        if (!photos.isEmpty()) {
            var number = 0;
            for (var photo : photos) {
                t.setValue("photo_url", photoUrl.apply(photo));
                t.setValue("photo_number", ++number);
                t.appendBlock("report_thumbs", "thumb");
            }
            t.setBlock("report_photos", "report_photos_block");
        }
        t.setBlock("report", "report_block");
    }

    static void results(Template t, List<ChecklistItem> items) {
        for (var item : items) {
            t.setValueEncoded("result_label", item.getLabel());
            t.appendBlock("results", item.isDone() ? "result_done" : "result_open");
        }
    }

    static void usedParts(Template t, List<PartUsed> parts) {
        if (parts.isEmpty()) {
            t.setBlock("used_parts", "no_used_parts");
        }
        for (var part : parts) {
            t.setValue("used_quantity", part.getQuantity());
            t.setValueEncoded("used_part_name", part.getPart());
            t.appendBlock("used_parts", "used_part");
        }
    }
}
