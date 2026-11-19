package workorders;

import rife.json.Json;
import rife.json.JsonArray;
import rife.json.JsonObject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a device and the office tell each other: the device sends the orders
 * that were finished on it, and gets back the orders that are scheduled for
 * its technician.
 */
public final class Sync {
    private Sync() {
    }

    /**
     * Carries the JSON of a sync to the office and returns its answer.
     */
    public interface Link {
        String exchange(String request);
    }

    public record Result(int sent, int refused, int received) {
    }

    /**
     * A request that the office can't read.
     */
    public static class InvalidRequestException extends RuntimeException {
        InvalidRequestException(Throwable cause) {
            super(cause);
        }
    }

    // runs on the device; each finished order goes in a request of its own,
    // so that one with many photos can't keep the others from being sent
    public static Result run(Store store, String technician, Link link) {
        var reports = new ArrayList<JsonObject>();
        for (var order : store.ordersWithStatus(WorkOrder.FINISHED)) {
            reports.add(report(store, order));
        }
        var sent = 0;
        JsonObject answer = null;
        for (var i = 0; i == 0 || i < reports.size(); i++) {
            var request = new JsonObject().set("technician", technician).set("reports", new JsonArray());
            if (i < reports.size()) {
                request.getArray("reports").add(reports.get(i));
            }
            answer = Json.parseObject(link.exchange(request.toString()));
            var acknowledged = answer.getArray("received");
            for (var j = 0; j < acknowledged.size(); j++) {
                var order = store.orderFromOffice(acknowledged.getInt(j));
                if (order != null && WorkOrder.FINISHED.equals(order.getStatus())) {
                    order.setStatus(WorkOrder.REPORTED);
                    store.save(order);
                    sent++;
                }
            }
        }

        var received = 0;
        var scheduled = answer.getArray("orders");
        for (var i = 0; i < scheduled.size(); i++) {
            if (receive(store, scheduled.getObject(i))) {
                received++;
            }
        }
        return new Result(sent, reports.size() - sent, received);
    }

    private static JsonObject report(Store store, WorkOrder order) {
        return new JsonObject()
            .set("officeId", order.getOfficeId())
            .set("notes", order.getNotes())
            .set("minutes", order.getMinutes())
            .set("signedBy", order.getSignedBy())
            .set("finishedAt", order.getFinishedAt().toString())
            .array("checklist", items -> {
                for (var item : store.checklist(order.getId())) {
                    items.add(new JsonObject().set("label", item.getLabel()).set("done", item.isDone()));
                }
            })
            .array("parts", parts -> {
                for (var part : store.parts(order.getId())) {
                    parts.add(new JsonObject().set("part", part.getPart()).set("quantity", part.getQuantity()));
                }
            })
            .array("photos", photos -> {
                for (var photo : store.photosWithData(order.getId())) {
                    photos.add(new JsonObject()
                        .set("kind", photo.getKind())
                        .set("contentType", photo.getContentType())
                        .set("taken", photo.getTaken().toString())
                        .set("data", Base64.getEncoder().encodeToString(photo.getData())));
                }
            });
    }

    // a new order is added, one that wasn't started yet is updated, and
    // one that the technician is working on is left alone
    private static boolean receive(Store store, JsonObject scheduled) {
        var office_id = scheduled.getInt("officeId");
        var order = store.orderFromOffice(office_id);
        var is_new = null == order;
        if (is_new) {
            order = new WorkOrder();
            order.setOfficeId(office_id);
        } else if (!WorkOrder.SCHEDULED.equals(order.getStatus())) {
            return false;
        }
        order.setNumber(scheduled.getString("number"));
        order.setCustomer(scheduled.getString("customer"));
        order.setAddress(scheduled.getString("address"));
        order.setPhone(scheduled.getString("phone", null));
        order.setEquipment(scheduled.getString("equipment"));
        order.setProblem(scheduled.getString("problem"));
        order.setTechnician(scheduled.getString("technician"));
        order.setScheduledFor(LocalDate.parse(scheduled.getString("scheduledFor")));
        order.setArrival(scheduled.getString("arrival"));
        if (is_new) {
            store.create(order);
        } else {
            store.save(order);
        }
        return is_new;
    }

    // runs at the office
    public static String answer(Store store, String request) {
        JsonObject json;
        String technician;
        JsonArray reports;
        try {
            json = Json.parseObject(request);
            technician = json.getString("technician");
            reports = json.getArray("reports");
        } catch (RuntimeException e) {
            throw new InvalidRequestException(e);
        }
        if (null == technician || null == reports) {
            throw new InvalidRequestException(null);
        }
        var received = new JsonArray();
        for (var i = 0; i < reports.size(); i++) {
            try {
                var report = reports.getObject(i);
                if (file(store, technician, report)) {
                    received.add(report.getInt("officeId"));
                }
            } catch (RuntimeException e) {
                // the other reports can still be filed, this one stays on the device
                Logger.getLogger("workorders").log(Level.WARNING, "A report of " + technician + " couldn't be filed", e);
            }
        }
        var orders = new JsonArray();
        for (var order : store.scheduledFor(technician)) {
            orders.add(new JsonObject()
                .set("officeId", order.getId())
                .set("number", order.getNumber())
                .set("customer", order.getCustomer())
                .set("address", order.getAddress())
                .set("phone", order.getPhone())
                .set("equipment", order.getEquipment())
                .set("problem", order.getProblem())
                .set("technician", order.getTechnician())
                .set("scheduledFor", order.getScheduledFor().toString())
                .set("arrival", order.getArrival()));
        }
        return new JsonObject().set("received", received).set("orders", orders).toString();
    }

    // a report that the office already has is acknowledged again, since the
    // device may not have heard the first answer
    private static boolean file(Store store, String technician, JsonObject report) {
        var order = store.order(report.getInt("officeId"));
        if (null == order || !order.getTechnician().equals(technician)) {
            return false;
        }
        var photos = new ArrayList<Photo>();
        var sent = report.getArray("photos");
        for (var i = 0; i < sent.size(); i++) {
            var json = sent.getObject(i);
            var photo = new Photo();
            photo.setOrderId(order.getId());
            photo.setKind(json.getString("kind"));
            photo.setContentType(json.getString("contentType"));
            photo.setTaken(Instant.parse(json.getString("taken")));
            photo.setData(Base64.getDecoder().decode(json.getString("data")));
            if (!Photo.isAccepted(photo.getKind(), photo.getContentType())) {
                return false;
            }
            photos.add(photo);
        }
        return store.inTransaction(() -> {
            // the second of two syncs with the same report finds it filed
            if (!store.claim(order.getId(), WorkOrder.SCHEDULED, WorkOrder.FINISHED)) {
                return store.order(order.getId()).isDone();
            }
            order.setNotes(report.getString("notes"));
            order.setMinutes(report.getInt("minutes"));
            order.setSignedBy(report.getString("signedBy"));
            order.setFinishedAt(Instant.parse(report.getString("finishedAt")));
            order.setStatus(WorkOrder.FINISHED);
            store.save(order);

            store.clearChecklist(order.getId());
            store.clearParts(order.getId());
            store.clearPhotos(order.getId());
            var items = report.getArray("checklist");
            for (var i = 0; i < items.size(); i++) {
                var item = items.getObject(i);
                store.addChecklistItem(order.getId(), i, item.getString("label"), item.getBoolean("done"));
            }
            var parts = report.getArray("parts");
            for (var i = 0; i < parts.size(); i++) {
                var part = new PartUsed();
                part.setOrderId(order.getId());
                part.setPart(parts.getObject(i).getString("part"));
                part.setQuantity(parts.getObject(i).getInt("quantity"));
                store.add(part);
            }
            for (var photo : photos) {
                store.add(photo);
            }
            return true;
        });
    }
}
