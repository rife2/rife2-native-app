import XCTest

/// Goes through a day of work with a finger, in the app that ios/build.sh installed: the pages,
/// the forms, htmx and the sign-off all come from the native library inside it.
final class Workday: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.uwyn.rife2.workorders")
    // what's in the pages, rather than in the keyboard, which has a Next button of its own
    private var page: XCUIElement {
        app.webViews.firstMatch
    }

    override func setUp() {
        continueAfterFailure = false
    }

    func testWorkday() {
        app.launch()

        tap(page.buttons["Sync with the office"])
        see("received")
        shot("today")

        tap(element(page.links, containing: "Bakery Lindqvist"))
        see("The boiler cuts out")
        tap(page.buttons["Start the work"])
        see("checklist")
        let boxes = page.switches
        XCTAssertTrue(boxes.firstMatch.waitForExistence(timeout: 20), "the checklist has boxes")
        for index in 0..<boxes.count {
            boxes.element(boundBy: index).tap()
            Thread.sleep(forTimeInterval: 0.3)
        }
        tap(element(page.textFields, containing: "Part"))
        app.typeText("Ignition electrode")
        tap(page.buttons["Add"])
        see("Ignition electrode")
        shot("order")

        tap(page.links["Sign off"])
        see("Step 1 of 3")
        tap(page.buttons["Next"])
        see("Step 2 of 3")
        tap(page.textViews.firstMatch)
        app.typeText("Replaced the ignition electrode, the burner lights every time now.")
        tap(element(page.textFields, containing: "minutes"))
        app.typeText("55")
        tap(page.buttons["Next"])
        see("Step 3 of 3")
        tap(element(page.textFields, containing: "Name"))
        app.typeText("Anna Lindqvist")
        let pad = element(page.otherElements, containing: "Signature pad")
        XCTAssertTrue(pad.waitForExistence(timeout: 20), "the signature pad is there")
        let start = pad.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.6))
        start.press(forDuration: 0.1, thenDragTo: pad.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.3)))
        pad.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.3)).press(forDuration: 0.1, thenDragTo: pad.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.65)))
        shot("signature")
        tap(page.buttons["Finish"])
        see("signed by Anna Lindqvist")
        shot("report")

        tap(element(page.links, containing: "Today"))
        see("1 finished, waiting to be sent.")
        tap(page.buttons["Sync with the office"])
        see("Sent 1 finished, received 0 new.")
    }

    // the photo that ios/check-app.sh added to the library of the simulator, the size of one of a
    // phone's camera, goes through the file input of the page to the site
    func testPhoto() {
        app.launch()
        tap(page.buttons["Sync with the office"])
        see("received")
        tap(element(page.links, containing: "Greenfield Primary School"))
        tap(page.buttons["Start the work"])
        see("No photos yet.")
        tap(element(page.descendants(matching: .any), containing: "Take a photo"))
        tap(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] 'Photo Library'")).firstMatch)
        // the picker runs apart from the app, the newest photo is its first one
        Thread.sleep(forTimeInterval: 3)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.16, dy: 0.42)).tap()
        // and confirmed with the check mark at its top
        Thread.sleep(forTimeInterval: 2)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.905, dy: 0.125)).tap()
        let photo = page.images.matching(NSPredicate(format: "label == 'Photo 1'")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 30), "the photo isn't on the page")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'too large'")).firstMatch.exists)
        shot("photo")
    }

    private func element(_ query: XCUIElementQuery, containing text: String) -> XCUIElement {
        query.matching(NSPredicate(format: "label CONTAINS %@ OR placeholderValue CONTAINS %@", text, text)).firstMatch
    }

    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: 20), "not there: \(element)", file: file, line: line)
        element.tap()
    }

    private func see(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        let found = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
        XCTAssertTrue(found.waitForExistence(timeout: 20), "the page doesn't say: \(text)", file: file, line: line)
    }

    // the screens for the readme, when the script asks for them
    private func shot(_ name: String) {
        guard let directory = ProcessInfo.processInfo.environment["SHOTS"] else {
            return
        }
        Thread.sleep(forTimeInterval: 0.5)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: directory).appendingPathComponent(name + ".png"))
    }
}
