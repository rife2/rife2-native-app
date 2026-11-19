package workorders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static workorders.Browser.form;
import static workorders.Fixture.*;

class SignOffTest {
    private static final String SIGNATURE = "data:image/png;base64," + Base64.getEncoder().encodeToString(PIXEL);

    private final Store device = store();
    private final Browser browser = new Browser(new FieldSite(device, "Sam", request -> {
        throw new IllegalStateException("offline");
    }, CLOCK));
    private final WorkOrder order = started();

    @AfterEach
    void close() {
        browser.close();
        Fixture.close(device);
    }

    @Test
    void signingOffInThreeSteps() {
        tickAll();
        var review = browser.get("/sign-off?order=" + order.getId());
        assertEquals(200, review.status());
        assertTrue(review.contains("Step 1 of 3"));
        assertFalse(review.contains("checklist items aren't done"));

        var report = next(review, form("next", "1"));
        assertTrue(report.contains("Step 2 of 3"));
        var signature = next(report, form("notes", "Replaced the electrode.", "minutes", "50", "next", "1"));
        assertTrue(signature.contains("Step 3 of 3"));

        var finished = next(signature, form("signedBy", "Jo Customer", "signature", SIGNATURE, "finish", "1"));
        assertEquals("/order/" + order.getId(), finished.path());
        assertTrue(finished.contains("Finished 17 November at"));
        assertTrue(finished.contains("after 50 minutes, signed by Jo Customer."));
        assertTrue(finished.contains("Replaced the electrode."));

        var done = device.order(order.getId());
        assertEquals(WorkOrder.FINISHED, done.getStatus());
        assertEquals("Replaced the electrode.", done.getNotes());
        assertEquals(50, done.getMinutes());
        assertEquals("Jo Customer", done.getSignedBy());
        assertEquals(CLOCK.instant(), done.getFinishedAt());
        var signatures = device.photos(order.getId(), Photo.SIGNATURE);
        assertEquals(1, signatures.size());
        assertArrayEquals(PIXEL, device.photo(signatures.get(0).getId()).getData());
        assertTrue(finished.contains("/photo/" + signatures.get(0).getId()));
        assertTrue(browser.get("/").contains("<strong>1</strong> finished, waiting to be sent."));
    }

    @Test
    void openChecklistItemsHaveToBeConfirmed() {
        var review = browser.get("/sign-off?order=" + order.getId());
        assertTrue(review.contains(Checklists.forEquipment("Boiler").size() + " checklist items aren't done."));

        var refused = next(review, form("next", "1"));
        assertTrue(refused.contains("Step 1 of 3"));
        assertTrue(refused.contains("Confirm this or go back and finish the checklist."));

        var confirmed = next(refused, form("skip", "true", "next", "1"));
        assertTrue(confirmed.contains("Step 2 of 3"));
    }

    @Test
    void reportIsValidated() {
        tickAll();
        var report = next(browser.get("/sign-off?order=" + order.getId()), form("next", "1"));

        var empty = next(report, form("notes", " ", "minutes", "", "next", "1"));
        assertTrue(empty.contains("Step 2 of 3"));
        assertTrue(empty.contains("Describe what you did."));
        assertTrue(empty.contains("Say how long the work took."));

        var short_work = next(empty, form("notes", "Done", "minutes", "2", "next", "1"));
        assertTrue(short_work.contains("The time is in minutes, between 5 and 720."));
        assertTrue(short_work.contains(">Done</textarea>"), "the notes stay filled in");

        var too_long = next(short_work, form("notes", "x".repeat(4001), "minutes", "30", "next", "1"));
        assertTrue(too_long.contains("The report is too long."));

        assertTrue(next(too_long, form("notes", "Done", "minutes", "30", "next", "1")).contains("Step 3 of 3"));
    }

    @Test
    void goingBackKeepsWhatWasEntered() {
        tickAll();
        var report = next(browser.get("/sign-off?order=" + order.getId()), form("next", "1"));
        var signature = next(report, form("notes", "Bled the radiators.", "minutes", "40", "next", "1"));

        var back = next(signature, form("signedBy", "Jo", "back", "1"));
        assertTrue(back.contains("Step 2 of 3"));
        assertTrue(back.contains(">Bled the radiators.</textarea>"));
        assertTrue(back.contains("value=\"40\""));

        var review = next(back, form("notes", "Bled the radiators.", "minutes", "40", "back", "1"));
        assertTrue(review.contains("Step 1 of 3"));

        var forward = next(next(review, form("next", "1")), form("notes", "Bled the radiators.", "minutes", "40", "next", "1"));
        assertTrue(forward.contains("value=\"Jo\""), "the name stays filled in");
    }

    @Test
    void signatureAndNameAreNeeded() {
        var signature = toSignature();
        for (var invalid : new String[][]{{"", SIGNATURE}, {"Jo", ""}, {"Jo", "data:image/png;base64,***"},
                                          {"Jo", "data:text/html;base64,PGI+"}, {"x".repeat(81), SIGNATURE},
                                          // a space of a keyboard of another script, which the office would refuse
                                          {"\u3000", SIGNATURE}}) {
            signature = next(signature, form("signedBy", invalid[0], "signature", invalid[1], "finish", "1"));
            assertTrue(signature.contains("The customer's name and signature are both needed."), invalid[0] + " " + invalid[1]);
        }
        assertEquals(WorkOrder.STARTED, device.order(order.getId()).getStatus());
    }

    @Test
    void anOrderThatIsntStartedCantBeSignedOff() {
        var scheduled = order(device, TODAY, "10:00 to 12:00", "Boiler", "Sam");
        assertEquals("/order/" + scheduled.getId(), browser.get("/sign-off?order=" + scheduled.getId()).path());
        assertEquals("/", browser.get("/sign-off?order=999").path());
        assertEquals("/", browser.get("/sign-off").path());
    }

    @Test
    void anOrderThatChangedMeanwhileEndsTheSignOff() {
        var signature = toSignature();
        var elsewhere = device.order(order.getId());
        elsewhere.setStatus(WorkOrder.FINISHED);
        device.save(elsewhere);
        var page = next(signature, form("signedBy", "Jo", "signature", SIGNATURE, "finish", "1"));
        assertEquals("/order/" + order.getId(), page.path());
        assertEquals(200, page.status());
        assertFalse(page.contains("null"));
        assertTrue(device.photos(order.getId(), Photo.SIGNATURE).isEmpty());
    }

    @Test
    void finishedSignOffCantBeResumed() {
        var signature = toSignature();
        next(signature, form("signedBy", "Jo", "signature", SIGNATURE, "finish", "1"));
        // the same page submitted again, with the browser's back button
        var again = next(signature, form("signedBy", "Someone else", "signature", SIGNATURE, "finish", "1"));
        assertEquals("/order/" + order.getId(), again.path());
        assertEquals("Jo", device.order(order.getId()).getSignedBy());
        assertEquals(1, device.photos(order.getId(), Photo.SIGNATURE).size());
    }

    @Test
    void aSignatureHasToBeAnImage() {
        var signature = toSignature();
        var empty = next(signature, form("signedBy", "Jo", "signature", "data:image/png;base64,", "finish", "1"));
        assertTrue(empty.contains("The customer's name and signature are both needed."));
        var text = next(empty, form("signedBy", "Jo", "signature", "data:image/png;base64," + Base64.getEncoder().encodeToString("hello".getBytes()), "finish", "1"));
        assertTrue(text.contains("The customer's name and signature are both needed."));
        assertEquals(WorkOrder.STARTED, device.order(order.getId()).getStatus());
    }

    @Test
    void expiredSignOffStartsOverAndSaysSo() {
        browser.get("/");
        var page = browser.post("/sign-off", form("contId", "gone", "order", String.valueOf(order.getId()), "finish", "1"));
        assertTrue(page.contains("Step 1 of 3"));
        assertTrue(page.contains("That sign-off had expired, so it started over."));
    }

    private Browser.Page toSignature() {
        tickAll();
        var report = next(browser.get("/sign-off?order=" + order.getId()), form("next", "1"));
        return next(report, form("notes", "Done", "minutes", "30", "next", "1"));
    }

    // submits a step with the continuation that its page resumes
    private Browser.Page next(Browser.Page page, Map<String, String> fields) {
        var submitted = new LinkedHashMap<>(page.hidden());
        submitted.remove("signature");
        submitted.putAll(fields);
        return browser.post("/sign-off", submitted);
    }

    private void tickAll() {
        for (var item : device.checklist(order.getId())) {
            item.setDone(true);
            device.save(item);
        }
    }

    private WorkOrder started() {
        var order = order(device, TODAY, "08:00 to 10:00", "Boiler", "Sam");
        order.setStatus(WorkOrder.STARTED);
        device.save(order);
        return order;
    }
}
