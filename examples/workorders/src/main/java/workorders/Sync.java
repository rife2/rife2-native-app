package workorders;

import rife.json.Json;
import rife.json.JsonArray;
import rife.json.JsonObject;
import rife.validation.Validated;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
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

    // with a lock of its own, for a caller that has the device's database to itself
    public static Result run(Store store, String technician, Link link) {
        return run(store, technician, link, new ReentrantLock());
    }

    // Runs on the device; each finished order goes in a request of its own,
    // so that one with many photos can't keep the others from being sent.
    // What the device's database has is read and changed under its lock,
    // the office is waited for without it, so that the pages can go on.
    public static Result run(Store store, String technician, Link link, Lock work) {
        var reports = locked(work, () -> {
            var read = new ArrayList<JsonObject>();
            for (var order : store.ordersWithStatus(WorkOrder.FINISHED)) {
                read.add(report(store, order));
            }
            return read;
        });
        var sent = 0;
        var acknowledged = 0;
        JsonObject answer = null;
        for (var i = 0; i == 0 || i < reports.size(); i++) {
            var request = new JsonObject().set("technician", technician).set("reports", new JsonArray());
            if (i < reports.size()) {
                request.getArray("reports").add(reports.get(i));
            }
            var said = Json.parseObject(link.exchange(request.toString()));
            answer = said;
            // of what the office has, a sync at the same time can have marked some already
            if (i < reports.size()) {
                var filed = said.getArray("received");
                for (var j = 0; j < filed.size(); j++) {
                    if (filed.getInt(j) == reports.get(i).getInt("officeId")) {
                        acknowledged++;
                        break;
                    }
                }
            }
            sent += locked(work, () -> {
                var marked = 0;
                var filed = said.getArray("received");
                for (var j = 0; j < filed.size(); j++) {
                    var order = store.orderFromOffice(filed.getInt(j));
                    if (order != null && WorkOrder.FINISHED.equals(order.getStatus())) {
                        order.setStatus(WorkOrder.REPORTED);
                        store.save(order);
                        marked++;
                    }
                }
                return marked;
            });
        }

        var scheduled = answer.getArray("orders");
        var received = locked(work, () -> {
            var added = 0;
            for (var i = 0; i < scheduled.size(); i++) {
                if (receive(store, scheduled.getObject(i))) {
                    added++;
                }
            }
            return added;
        });
        return new Result(sent, reports.size() - acknowledged, received);
    }

    // a whole number of the JSON, not one that a missing or another value would turn into one
    private static Integer whole(Object value) {
        return value instanceof Number number && number.doubleValue() == number.intValue() ? number.intValue() : null;
    }

    private static <T> T locked(Lock lock, Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private static JsonObject report(Store store, WorkOrder order) {
        return new JsonObject()
            .set("officeId", order.getOfficeId())
            // the technician whose order it is, also when the device was given to someone else since
            .set("technician", order.getTechnician())
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
    private static boolean file(Store store, String device, JsonObject report) {
        var technician = report.getString("technician", device);
        var order = store.order(report.getInt("officeId"));
        if (null == order || !order.getTechnician().equals(technician)) {
            return false;
        }
        var sent = report.getArray("photos");
        var used = report.getArray("parts");
        if (null == sent || null == used || null == report.getArray("checklist")) {
            return false;
        }
        var photos = new ArrayList<Photo>();
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
        // what the technician's pages refuse of what's written and used, the office refuses too
        var written = new Report();
        written.setNotes(report.getString("notes", null));
        written.setMinutes(whole(report.get("minutes")));
        var signer = report.getString("signedBy", "").strip();
        if (!written.validate() || signer.isBlank() || signer.length() > WorkOrderMetaData.SIGNER_LENGTH) {
            return false;
        }
        var parts = new ArrayList<PartUsed>();
        for (var i = 0; i < used.size(); i++) {
            var part = new PartUsed();
            part.setOrderId(order.getId());
            part.setPart(used.getObject(i).getString("part", null));
            part.setQuantity(whole(used.getObject(i).get("quantity")));
            if (!((Validated) part).validate()) {
                return false;
            }
            parts.add(part);
        }
        return store.inTransaction(() -> filed(store, technician, order.getId(), report, written, signer, parts, photos));
    }

    private static boolean filed(Store store, String technician, int orderId, JsonObject report, Report written, String signer,
                                 List<PartUsed> parts, List<Photo> photos) {
        // the second of two syncs with the same report finds it filed, an order given to someone else meanwhile isn't
        if (!store.claim(orderId, technician, WorkOrder.SCHEDULED, WorkOrder.FINISHED)) {
            var current = store.order(orderId);
            return current.isDone() && current.getTechnician().equals(technician);
        }
        // as it is now that it's claimed, not as it was read before
        var order = store.order(orderId);
        order.setNotes(written.getNotes());
        order.setMinutes(written.getMinutes());
        order.setSignedBy(signer);
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
        for (var part : parts) {
            store.add(part);
        }
        for (var photo : photos) {
            store.add(photo);
        }
        return true;
    }
}
