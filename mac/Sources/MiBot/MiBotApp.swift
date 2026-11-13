import AppKit
import MiBotKit
import SwiftUI

/// Launch options, mostly for tests:
///   --profile NAME   keep this run's sign-in apart (default "default")
///   --hub URL --code CODE   sign in with a pairing code at launch
///   --trace FILE     write what happens to each approval to FILE, one JSON line each
struct Launch {
    var profile = "default"
    var hub: String?
    var code: String?
    var trace: String?

    init(_ args: [String] = CommandLine.arguments) {
        var it = args.dropFirst().makeIterator()
        while let a = it.next() {
            switch a {
            case "--profile": profile = it.next().map { $0.filter { $0.isLetter || $0.isNumber || $0 == "-" } } ?? profile
            case "--hub": hub = it.next()
            case "--code": code = it.next()
            case "--trace": trace = it.next()
            default: break
            }
        }
        if profile.isEmpty { profile = "default" }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let launch = Launch()
    lazy var model = AppModel(store: AccountStore(profile: launch.profile), trace: Trace(file: launch.trace.map { URL(fileURLWithPath: $0) }))
    private var window: NSWindow?

    func applicationWillFinishLaunching(_ notification: Notification) {
        // The notification delegate must be in place before launch ends, or a click that launched the app is lost.
        _ = model
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        showWindow()
        if let hub = launch.hub, let code = launch.code, model.line == .signedOut {
            Task { try? await model.signIn(hubText: hub, code: code) }
        }
    }

    func showWindow() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 900, height: 600),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
            w.title = "MiBot"
            w.isReleasedWhenClosed = false
            w.setFrameAutosaveName("MiBotMain")
            w.contentView = NSHostingView(rootView: RootView().environmentObject(model).frame(minWidth: 720, minHeight: 460))
            if !w.setFrameUsingName("MiBotMain") { w.center() }
            window = w
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    // Closing the window keeps MiBot running in the menu bar, so requests still reach you.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showWindow()
        return true
    }
}

@main
struct MiBotApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        MenuBarExtra {
            MenuBarContent().environmentObject(delegate.model)
        } label: {
            MenuBarLabel().environmentObject(delegate.model)
        }
        Settings { EmptyView() }
    }
}

struct MenuBarLabel: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let n = model.book.pending.filter { model.canAnswer($0) }.count
        if n > 0 { Label("\(n)", systemImage: "exclamationmark.shield.fill").labelStyle(.titleAndIcon) }
        else { Image(systemName: "checkmark.shield") }
    }
}
