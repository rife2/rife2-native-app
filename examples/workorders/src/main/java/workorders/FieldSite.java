package workorders;

import rife.config.RifeConfig;
import rife.engine.*;
import rife.engine.elements.CsrfProtected;
import rife.template.Template;
import rife.tools.FileUtils;
import rife.validation.Validated;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The pages of a technician, which work on the orders of the device's own
 * database and only need the office to sync.
 */
public class FieldSite extends Site {
    static final int PHOTO_LIMIT = 15 * 1024 * 1024;

    private final Store store_;
    private final String technician_;
    private final Sync.Link office_;
    private final Clock clock_;
    private final Runnable stopped_;
    // a web view sends several requests at once, so what checks the state of
    // an order and then changes it happens one request at a time, the sync's included
    private final ReentrantLock work_ = new ReentrantLock();

    Route today;
    Route order;
    Route start;
    Route check;
    Route addPart;
    Route removePart;
    Route addPhoto;
    Photos photos;
    Route signOff;
    Route sync;

    public FieldSite(Store store, String technician, Sync.Link office, Clock clock) {
        this(store, technician, office, clock, () -> {});
    }

    /**
     * @param stopped what's stopped with the site, like the demo office that it syncs with
     */
    public FieldSite(Store store, String technician, Sync.Link office, Clock clock, Runnable stopped) {
        store_ = store;
        technician_ = technician;
        office_ = office;
        clock_ = clock;
        stopped_ = stopped;
    }

    public void setup() {
        // the photos of a phone's camera are larger than the default limit
        RifeConfig.engine().setFileUploadSizeLimit(PHOTO_LIMIT);
        // a sign-off can wait for the customer, so it lasts a working day
        RifeConfig.engine().setContinuationDuration(TimeUnit.HOURS.toMillis(10));

        before(new CsrfProtected());

        today = get("/", this::today);
        order = get("/order", PathInfoHandling.MAP(m -> m.p("id", "\\d+")), this::order);
        start = post("/order", PathInfoHandling.MAP(m -> m.p("id", "\\d+").s().t("start")), c -> {
            var started = locked(() -> {
                var order = order(c, WorkOrder.SCHEDULED);
                if (order != null) {
                    order.setStatus(WorkOrder.STARTED);
                    store_.save(order);
                }
                return order;
            });
            if (started != null) {
                c.redirect(orderUrl(c, started));
            }
        });
        check = patch("/order", PathInfoHandling.MAP(m -> m.p("id", "\\d+").s().t("check").s().p("item", "\\d+")), c -> {
            var t = c.template("field/work");
            var checked = locked(() -> {
                var order = order(c, WorkOrder.STARTED);
                if (null == order) {
                    return false;
                }
                var item = store_.checklistItem(order.getId(), c.parameterInt("item"));
                if (null == item) {
                    c.setStatus(404);
                    return false;
                }
                item.setDone(c.parameterBoolean("done"));
                store_.save(item);
                checklistItem(c, t, order, item);
                return true;
            });
            if (checked) {
                c.printBlock(t, "check_item");
            }
        });
        addPart = post("/order", PathInfoHandling.MAP(m -> m.p("id", "\\d+").s().t("parts")), c -> {
            var t = c.template("field/work");
            var changed = locked(() -> {
                var order = order(c, WorkOrder.STARTED);
                if (null == order) {
                    return false;
                }
                // what the technician can fill in, the order is the one of the address
                var part = c.parametersBean(PartUsed.class);
                part.setOrderId(order.getId());
                part.setPart(null == part.getPart() ? null : part.getPart().trim());
                if (((Validated) part).validate()) {
                    store_.add(part);
                } else {
                    t.setBlock("part_error", "part_error_block");
                }
                parts(c, t, order);
                return true;
            });
            if (changed) {
                c.printBlock(t, "parts_block");
            }
        });
        removePart = delete("/order", PathInfoHandling.MAP(m -> m.p("id", "\\d+").s().t("parts").s().p("part", "\\d+")), c -> {
            var t = c.template("field/work");
            var changed = locked(() -> {
                var order = order(c, WorkOrder.STARTED);
                if (null == order) {
                    return false;
                }
                store_.deletePart(order.getId(), c.parameterInt("part"));
                parts(c, t, order);
                return true;
            });
            if (changed) {
                c.printBlock(t, "parts_block");
            }
        });
        addPhoto = post("/order", PathInfoHandling.MAP(m -> m.p("id", "\\d+").s().t("photos")), c -> {
            var t = c.template("field/work");
            var file = c.file("photo");
            try {
                byte[] data = null;
                if (file != null && !file.wasSizeExceeded() && file.getFile() != null &&
                    Photo.isAccepted(Photo.PHOTO, file.getContentType()) && file.getFile().length() <= PHOTO_LIMIT) {
                    data = FileUtils.readBytes(file.getFile());
                }
                var photo_data = data;
                var changed = locked(() -> {
                    var order = order(c, WorkOrder.STARTED);
                    if (null == order) {
                        return false;
                    }
                    if (null == photo_data) {
                        t.setBlock("photo_error", "photo_error_block");
                    } else {
                        var photo = new Photo();
                        photo.setOrderId(order.getId());
                        photo.setContentType(file.getContentType());
                        photo.setData(photo_data);
                        photo.setTaken(Instant.now(clock_));
                        store_.add(photo);
                    }
                    photoItems(c, t, order);
                    return true;
                });
                if (changed) {
                    c.printBlock(t, "photos_block");
                }
            } finally {
                // the temporary file of an upload stays until the process ends otherwise
                if (file != null) {
                    file.close();
                }
            }
        });
        photos = group(new Photos(store_));
        signOff = getPost("/sign-off", () -> new SignOff(this));
        sync = post("/sync", c -> {
            try {
                var result = Sync.run(store_, technician_, office_, work_);
                c.redirect(c.urlFor(today)
                    .param("sent", result.sent())
                    .param("refused", result.refused())
                    .param("received", result.received()));
            } catch (Links.WrongAddressException e) {
                c.redirect(c.urlFor(today).param("sync", "address"));
            } catch (RuntimeException e) {
                Logger.getLogger("workorders").log(Level.WARNING, "The sync failed", e);
                c.redirect(c.urlFor(today).param("sync", "failed"));
            }
        });
    }

    // the site's database goes with it
    @Override
    public void destroy() {
        try {
            store_.close();
        } finally {
            stopped_.run();
        }
    }

    Store store() {
        return store_;
    }

    Clock clock() {
        return clock_;
    }

    <T> T locked(Supplier<T> work) {
        work_.lock();
        try {
            return work.get();
        } finally {
            work_.unlock();
        }
    }

    private void today(Context c) {
        var t = c.template("field/today");
        t.setValueEncoded("technician", technician_);
        var day = LocalDate.now(clock_);
        t.setValue("today", Views.day(day));
        if ("failed".equals(c.parameter("sync"))) {
            t.setBlock("notice", "sync_failed");
        } else if ("address".equals(c.parameter("sync"))) {
            t.setBlock("notice", "sync_address");
        } else if (c.parameter("sent") != null) {
            t.setValue("sent", c.parameterInt("sent", 0));
            t.setValue("received", c.parameterInt("received", 0));
            var refused = c.parameterInt("refused", 0);
            if (refused > 0) {
                t.setValue("refused", refused);
                t.setBlock("refused_line", "refused_block");
            }
            t.setBlock("notice", "synced");
        }
        var waiting = store_.ordersWithStatus(WorkOrder.FINISHED).size();
        if (waiting > 0) {
            t.setValue("waiting_count", waiting);
            t.setBlock("waiting", "waiting_some");
        } else {
            t.setBlock("waiting", "waiting_none");
        }
        var orders = store_.orders();
        for (var order : orders) {
            var scheduled = order.getScheduledFor();
            var later = scheduled.isAfter(day);
            t.setBean(order, "job_");
            t.setValue("job_url", orderUrl(c, order));
            // an overdue job is still today's work, with the day it was planned for
            t.setValueEncoded("job_when", (scheduled.equals(day) ? "" : Views.day(scheduled) + ", ") + order.getArrival());
            t.setValue("job_status_label", Views.statusLabel(order.getStatus()));
            t.appendBlock(order.isDone() ? "done_jobs" : later ? "later_jobs" : "today_jobs", "job");
        }
        if (orders.isEmpty()) {
            t.setBlock("lists", "no_orders");
        } else {
            for (var list : new String[]{"today_jobs", "later_jobs", "done_jobs"}) {
                if (!t.isValueSet(list)) {
                    t.setBlock(list, "no_jobs");
                }
            }
            t.setBlock("lists", "lists_block");
        }
        c.print(t);
    }

    private void order(Context c) {
        var order = store_.order(c.parameterInt("id"));
        if (null == order) {
            c.setStatus(404);
            c.print("This order isn't on this device.");
            return;
        }
        var t = c.template("field/order");
        Views.order(t, order, store_, photo -> photos.url(c, photo), clock_);
        if (WorkOrder.SCHEDULED.equals(order.getStatus())) {
            t.setValue("start_url", c.urlFor(start).param("id", order.getId()));
            t.setBlock("actions", "start_block");
        } else if (WorkOrder.STARTED.equals(order.getStatus())) {
            t.setValue("sign_off_url", c.urlFor(signOff).param("order", order.getId()));
            t.setBlock("actions", "sign_off_block");
            for (var item : store_.checklist(order.getId())) {
                checklistItem(c, t, order, item);
                t.appendBlock("check_items", "check_item");
            }
            t.setBlock("checklist", "checklist_block");
            parts(c, t, order);
            t.setBlock("parts", "parts_block");
            photoItems(c, t, order);
            t.setBlock("photos", "photos_block");
            t.setBlock("work", "work_block");
        }
        c.print(t);
    }

    // the order of the request, when it's in the state that the request needs
    private WorkOrder order(Context c, String status) {
        var order = store_.order(c.parameterInt("id"));
        if (null == order) {
            c.setStatus(404);
            c.print("This order isn't on this device.");
            return null;
        }
        if (!status.equals(order.getStatus())) {
            c.setStatus(409);
            c.print("This order is " + Views.statusLabel(order.getStatus()).toLowerCase() + ".");
            return null;
        }
        return order;
    }

    String orderUrl(Context c, WorkOrder order) {
        return c.urlFor(this.order).param("id", order.getId()).toString();
    }

    private void checklistItem(Context c, Template t, WorkOrder order, ChecklistItem item) {
        t.setValue("item_id", item.getId());
        t.setValue("item_url", c.urlFor(check).param("id", order.getId()).param("item", item.getId()));
        t.setValue("item_checked", item.isDone() ? "checked" : "");
        t.setValueEncoded("item_label", item.getLabel());
    }

    private void parts(Context c, Template t, WorkOrder order) {
        t.setValue("parts_url", c.urlFor(addPart).param("id", order.getId()));
        var parts = store_.parts(order.getId());
        if (parts.isEmpty()) {
            t.setBlock("part_items", "no_parts");
        }
        for (var part : parts) {
            t.setValue("part_quantity", part.getQuantity());
            t.setValueEncoded("part_name", part.getPart());
            t.setValue("part_url", c.urlFor(removePart).param("id", order.getId()).param("part", part.getId()));
            t.appendBlock("part_items", "part_item");
        }
    }

    private void photoItems(Context c, Template t, WorkOrder order) {
        t.setValue("photos_url", c.urlFor(addPhoto).param("id", order.getId()));
        var taken = store_.photos(order.getId(), Photo.PHOTO);
        if (taken.isEmpty()) {
            t.setBlock("photo_items", "no_photos");
        }
        var number = 0;
        for (var photo : taken) {
            t.setValue("photo_url", photos.url(c, photo));
            t.setValue("photo_number", ++number);
            t.appendBlock("photo_items", "photo_item");
        }
    }
}
