package workorders;

import rife.engine.*;
import rife.engine.elements.CsrfProtected;
import rife.template.Template;
import rife.validation.Validated;
import rife.validation.ValidationError;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The office: dispatchers schedule orders and read the reports, and the
 * devices of the technicians sync with it.
 */
public class OfficeSite extends Site {
    private final Store store_;
    private final String syncToken_;
    private final List<String> technicians_;
    private final Clock clock_;
    // the office's changes happen one at a time, see FieldSite
    private final ReentrantLock work_ = new ReentrantLock();

    Route board;
    Route create;
    Route order;
    Route photo;
    Route sync;

    public OfficeSite(Store store, String syncToken, List<String> technicians, Clock clock) {
        store_ = store;
        syncToken_ = syncToken;
        technicians_ = List.copyOf(technicians);
        clock_ = clock;
    }

    public void setup() {
        before(new CsrfProtected() {
            public void process(Context c) {
                // the devices aren't browsers, they authenticate with the token
                if (c.route() != sync) {
                    super.process(c);
                }
            }
        });

        board = get("/", c -> {
            var t = c.template("office/board");
            var days = new LinkedHashMap<LocalDate, List<WorkOrder>>();
            for (var order : store_.orders()) {
                days.computeIfAbsent(order.getScheduledFor(), day -> new ArrayList<>()).add(order);
            }
            if (days.isEmpty()) {
                t.setBlock("days", "no_orders");
            }
            for (var day : days.entrySet()) {
                t.removeValue("day_rows");
                for (var order : day.getValue()) {
                    t.setValue("row_url", c.urlFor(this.order).param("id", order.getId()));
                    t.setValueEncoded("row_number", order.getNumber());
                    t.setValueEncoded("row_arrival", order.getArrival());
                    t.setValueEncoded("row_customer", order.getCustomer());
                    t.setValueEncoded("row_address", order.getAddress());
                    t.setValueEncoded("row_equipment", order.getEquipment());
                    t.setValueEncoded("row_technician", order.getTechnician());
                    t.setValue("row_status", order.getStatus());
                    t.setValue("row_status_label", Views.statusLabel(order.getStatus()));
                    t.appendBlock("day_rows", "row");
                }
                t.setValue("day_label", Views.day(day.getKey()));
                t.appendBlock("days", "day");
            }
            c.print(t);
        });

        create = getPost("/new", c -> {
            var t = c.template("office/create");
            if (c.method() == RequestMethod.POST) {
                var order = new WorkOrder();
                c.parametersBean(order, null, WorkOrderMetaData.DISPATCH);
                var validated = (Validated) order;
                var valid = validated.validateGroup(WorkOrderMetaData.DISPATCH);
                // an order for someone without a device would never be done
                if (order.getTechnician() != null && !technicians_.contains(order.getTechnician())) {
                    validated.addValidationError(new ValidationError.INVALID("technician"));
                    valid = false;
                }
                if (valid) {
                    locked(() -> store_.create(order));
                    c.redirect(c.urlFor(this.order).param("id", order.getId()));
                    return;
                }
                c.generateForm(t, order);
                t.setValueEncoded("scheduled_for", null == order.getScheduledFor() ? "" : order.getScheduledFor());
                technicians(t, order.getTechnician());
            } else {
                c.generateEmptyForm(t, WorkOrder.class);
                t.setValue("scheduled_for", LocalDate.now(clock_));
                technicians(t, null);
            }
            c.print(t);
        });

        order = get("/order", PathInfoHandling.MAP(m -> m.p("id", "\\d+")), c -> {
            var order = store_.order(c.parameterInt("id"));
            if (null == order) {
                c.setStatus(404);
                c.print("There's no such order.");
                return;
            }
            var t = c.template("office/order");
            Views.order(t, order, store_, photo -> c.urlFor(this.photo).param("id", photo.getId()).toString(), clock_);
            if (!order.isDone()) {
                Views.results(t, store_.checklist(order.getId()));
                t.setBlock("planned", "planned_block");
            }
            c.print(t);
        });

        photo = get("/photo", PathInfoHandling.MAP(m -> m.p("id", "\\d+")), c -> FieldSite.servePhoto(c, store_));

        sync = post("/api/sync", c -> {
            if (!authorized(c.header("Authorization"))) {
                c.setStatus(401);
                c.setHeader("WWW-Authenticate", "Bearer");
                return;
            }
            try {
                var body = c.body();
                var answer = locked(() -> Sync.answer(store_, body));
                c.setContentType("application/json");
                c.print(answer);
            } catch (Sync.InvalidRequestException e) {
                c.setStatus(400);
            }
        });
    }

    private <T> T locked(Supplier<T> work) {
        work_.lock();
        try {
            return work.get();
        } finally {
            work_.unlock();
        }
    }

    private void technicians(Template t, String selected) {
        for (var technician : technicians_) {
            t.setValueEncoded("technician_name", technician);
            t.setValue("technician_selected", technician.equals(selected) ? "selected" : "");
            t.appendBlock("technician_options", "technician_option");
        }
    }

    private boolean authorized(String header) {
        if (null == header || !header.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(header.substring(7).getBytes(StandardCharsets.UTF_8), syncToken_.getBytes(StandardCharsets.UTF_8));
    }
}
