package workorders;

import rife.engine.Context;
import rife.engine.Element;
import rife.engine.SpecialParameters;
import rife.template.Template;

import java.time.Instant;
import java.util.Base64;

/**
 * Signs off an order in three steps, written as one method that pauses for
 * each page: what was entered earlier is simply a local variable, and going
 * back is a loop that continues.
 */
public class SignOff implements Element {
    private static final String PNG = "data:image/png;base64,";
    private static final int SIGNATURE_LIMIT = 128 * 1024;

    private final FieldSite site_;

    public SignOff(FieldSite site) {
        site_ = site;
    }

    public void process(Context c) {
        var store = site_.store();
        var id = c.parameterInt("order", -1);
        // a continuation that's gone, after a restart of the app for instance
        var expired = c.parameter(SpecialParameters.CONT_ID) != null;
        var order = started(c, id);
        var report = new Report();
        var skip = false;
        var signed_by = "";

        steps:
        while (true) {
            // the checklist, the parts and the photos, with what isn't done confirmed
            var unconfirmed = false;
            while (true) {
                var t = page(c, order);
                if (expired) {
                    t.setBlock("notice", "expired");
                    expired = false;
                }
                var items = store.checklist(id);
                Views.results(t, items);
                Views.usedParts(t, store.parts(id));
                t.setValue("photo_count", Views.count(store.photos(id, Photo.PHOTO).size(), "photo", "photos"));
                var open = (int) items.stream().filter(item -> !item.isDone()).count();
                if (open > 0) {
                    t.setValue("open_items", open == 1 ? "1 checklist item isn't done." : open + " checklist items aren't done.");
                    t.setValue("skip_checked", skip ? "checked" : "");
                    if (unconfirmed) {
                        t.setBlock("skip_error", "skip_error_block");
                    }
                    t.setBlock("skipped", "skipped_block");
                }
                t.setBlock("step", "review");
                c.print(t);
                c.pause();

                order = started(c, id);
                skip = c.parameterBoolean("skip");
                if (skip || store.checklist(id).stream().allMatch(ChecklistItem::isDone)) {
                    break;
                }
                unconfirmed = true;
            }

            report:
            while (true) {
                while (true) {
                    var t = page(c, order);
                    c.generateForm(t, report);
                    t.setBlock("step", "report");
                    c.print(t);
                    c.pause();

                    order = started(c, id);
                    report.resetValidation();
                    c.parametersBean(report);
                    if (c.parameter("back") != null) {
                        continue steps;
                    }
                    if (report.validate()) {
                        break;
                    }
                }

                var refused = false;
                while (true) {
                    var t = page(c, order);
                    t.setValueEncoded("signed_by", signed_by);
                    if (refused) {
                        t.setBlock("signature_error", "signature_error_block");
                    }
                    t.setBlock("step", "signature");
                    c.print(t);
                    c.pause();

                    order = started(c, id);
                    signed_by = c.parameter("signedBy", "").trim();
                    if (c.parameter("back") != null) {
                        continue report;
                    }
                    var signature = png(c.parameter("signature", ""));
                    if (signature != null && !signed_by.isEmpty() && signed_by.length() <= 80) {
                        finish(c, id, report, signed_by, signature);
                        return;
                    }
                    refused = true;
                }
            }
        }
    }

    // the order, as long as it's still being worked on; otherwise its page
    private WorkOrder started(Context c, int id) {
        var order = site_.store().order(id);
        if (null == order) {
            c.redirect(site_.today);
        }
        if (!WorkOrder.STARTED.equals(order.getStatus())) {
            c.redirect(site_.orderUrl(c, order));
        }
        return order;
    }

    private Template page(Context c, WorkOrder order) {
        var t = c.template("field/sign_off");
        t.setValueEncoded("number", order.getNumber());
        t.setValue("order_url", site_.orderUrl(c, order));
        // a page of a sign-off that ended still leads to its order
        t.setValue("order_id", order.getId());
        return t;
    }

    private void finish(Context c, int id, Report report, String signedBy, byte[] signature) {
        var store = site_.store();
        site_.locked(() -> store.inTransaction(() -> {
            // the order can have been finished in another page meanwhile
            if (!store.claim(id, WorkOrder.STARTED, WorkOrder.FINISHED)) {
                return false;
            }
            var order = store.order(id);
            order.setNotes(report.getNotes().trim());
            order.setMinutes(report.getMinutes());
            order.setSignedBy(signedBy);
            order.setFinishedAt(Instant.now(site_.clock()));
            store.save(order);
            var photo = new Photo();
            photo.setOrderId(id);
            photo.setKind(Photo.SIGNATURE);
            photo.setContentType("image/png");
            photo.setData(signature);
            photo.setTaken(order.getFinishedAt());
            store.replaceSignature(photo);
            return true;
        }));
        c.redirect(site_.orderUrl(c, store.order(id)));
    }

    private static byte[] png(String dataUrl) {
        if (!dataUrl.startsWith(PNG) || dataUrl.length() > PNG.length() + SIGNATURE_LIMIT * 4 / 3) {
            return null;
        }
        try {
            var data = Base64.getDecoder().decode(dataUrl.substring(PNG.length()));
            return Photo.isPng(data) ? data : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
