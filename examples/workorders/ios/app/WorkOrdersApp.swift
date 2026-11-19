import RIFE2NativeApp
import SwiftUI

extension UIColor {
    // the color of the bar at the top of the pages, which goes on behind the status bar
    static let bar = UIColor(red: 31 / 255, green: 42 / 255, blue: 68 / 255, alpha: 1)
}

@main
struct WorkOrdersApp: App {
    @StateObject private var pages: NativePages
    #if DEBUG
    private static let debug = true
    #else
    private static let debug = false
    #endif

    init() {
        // the address of the office and who the technician is come from the
        // settings of the app, the demo office inside it is used without them
        for name in ["workorders.office", "workorders.technician", "workorders.syncToken"] {
            if let value = UserDefaults.standard.string(forKey: name), !value.isEmpty {
                NativeSite.properties[name] = value
            }
        }
        _pages = StateObject(wrappedValue: NativePages(background: .bar, inspectable: Self.debug))
    }

    var body: some Scene {
        WindowGroup {
            NativePagesView(pages: pages)
                .ignoresSafeArea(edges: .bottom)
                .background(Color(uiColor: .bar).ignoresSafeArea())
        }
    }
}
