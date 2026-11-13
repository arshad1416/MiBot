import MiBotKit
import SwiftUI
import WebKit

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        if model.line == .signedOut { PairView() } else { MainView() }
    }
}

// MARK: signing in with a code

struct PairView: View {
    @EnvironmentObject var model: AppModel
    @State private var hub = ""
    @State private var code = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Sign in to your hub").font(.title2.bold())
            Text("On your hub, open Settings › Machines › Add a device. Type the hub’s address and the code it shows, or paste the whole link.")
                .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if let why = model.signOutReason { Text(why).foregroundStyle(.orange) }
            TextField("Hub address or link, like mac-mini:8787", text: $hub).textFieldStyle(.roundedBorder)
            TextField("Code, like ABCD-2345", text: $code).textFieldStyle(.roundedBorder).font(.system(.body, design: .monospaced))
            if let error { Text(error).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
            HStack {
                Spacer()
                if working { ProgressView().controlSize(.small) }
                Button("Sign in") { go() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(working || hub.trimmingCharacters(in: .whitespaces).isEmpty || (code.isEmpty && HubClient.parseLink(hub) == nil))
            }
        }
        .padding(28)
        .frame(width: 440)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func go() {
        working = true; error = nil
        Task {
            do { try await model.signIn(hubText: hub, code: code) } catch { self.error = error.localizedDescription }
            working = false
        }
    }
}

// MARK: the main window

struct MainView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        NavigationSplitView {
            List(selection: Binding(get: { model.page }, set: { if let p = $0 { model.page = p } })) {
                Label("Approvals", systemImage: "checkmark.shield").badge(model.pendingCount).tag(AppModel.Page.approvals)
                Label("Hub", systemImage: "globe").tag(AppModel.Page.hub)
            }
            .safeAreaInset(edge: .bottom) { LineStatus().padding(10) }
            .navigationSplitViewColumnWidth(min: 170, ideal: 190)
        } detail: {
            switch model.page {
            case .approvals: ApprovalsPage()
            case .hub:
                if let c = model.client { HubWebView(client: c) } else { EmptyView() }
            }
        }
    }
}

struct LineStatus: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Circle().fill(color).frame(width: 8, height: 8)
                Text(text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            if let host = model.client?.base.host { Text(host).font(.caption2).foregroundStyle(.tertiary) }
            Button("Sign out") { model.signOut() }.buttonStyle(.link).font(.caption)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var text: String {
        switch model.line {
        case .live: return "Connected"
        case .connecting: return "Connecting…"
        case .offline(let why): return why
        case .signedOut: return "Signed out"
        }
    }

    private var color: Color {
        switch model.line {
        case .live: return .green
        case .connecting: return .yellow
        default: return .red
        }
    }
}

// MARK: approvals

struct ApprovalsPage: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let items = model.book.listed()
        if items.isEmpty {
            ContentUnavailableView("Nothing waiting", systemImage: "checkmark.shield",
                                   description: Text("When a Bot asks to run something that needs your OK, it shows up here and you get a notification."))
        } else {
            HSplitView {
                List(items, selection: $model.focusId) { a in ApprovalRow(a: a).tag(a.id) }
                    .frame(minWidth: 240, idealWidth: 280, maxWidth: 360)
                Group {
                    if let a = shown(items) { ApprovalDetail(a: a).id(a.id) } else { Text("Pick a request").foregroundStyle(.secondary) }
                }
                .frame(minWidth: 380, maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    /// The one asked for, else the oldest still waiting.
    private func shown(_ items: [Approval]) -> Approval? {
        if let id = model.focusId, let a = model.book.all[id] { return a }
        return model.book.pending.first ?? items.first
    }
}

struct ApprovalRow: View {
    @EnvironmentObject var model: AppModel
    let a: Approval

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let open = model.canAnswer(a, at: ctx.date)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Image(systemName: open ? "exclamationmark.shield.fill" : icon).foregroundStyle(open ? .orange : .secondary)
                    Text(a.botName.isEmpty ? "Bot" : a.botName).font(.headline)
                }
                Text(a.title).font(.callout).lineLimit(2)
                Text(open ? "Waiting for you" : (a.pending ? "Expired before anyone answered" : a.outcome)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.vertical, 3)
        }
    }

    private var icon: String {
        switch a.status {
        case "allowed", "always": return "checkmark.circle"
        case "denied": return "xmark.circle"
        default: return "clock"
        }
    }
}

struct ApprovalDetail: View {
    @EnvironmentObject var model: AppModel
    let a: Approval

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(a.botName.isEmpty ? "A Bot" : a.botName).font(.subheadline).foregroundStyle(.secondary)
                    Text(a.title.isEmpty ? "Wants to run something" : a.title).font(.title2.bold()).fixedSize(horizontal: false, vertical: true)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Command").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Text(a.commandText.isEmpty ? "(no details)" : a.commandText)
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color(nsColor: .textBackgroundColor)))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.25)))
                    if !a.tool.isEmpty {
                        Text("Tool: \(a.tool)" + (a.toolkitName.map { " · \($0)" } ?? "")).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                }
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    if model.canAnswer(a, at: ctx.date) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(expiresIn(a.expiresAt - model.book.skewMs - Int64(ctx.date.timeIntervalSince1970 * 1000)))
                                .font(.caption).foregroundStyle(.secondary)
                            HStack(spacing: 10) {
                                // Neither answer is the default button: Return must not approve anything.
                                Button("Approve once") { model.decide(a, "once") }.buttonStyle(.borderedProminent)
                                Button("Deny", role: .destructive) { model.decide(a, "deny") }.buttonStyle(.bordered)
                                if model.busy.contains(a.id) { ProgressView().controlSize(.small) }
                            }
                            .disabled(model.busy.contains(a.id))
                        }
                    } else {
                        Label(a.pending ? "Expired before anyone answered" : a.outcome, systemImage: a.status == "denied" ? "xmark.circle" : a.pending || a.status == "expired" ? "clock" : "checkmark.circle")
                            .foregroundStyle(.secondary)
                    }
                }
                if let note = model.notes[a.id] { Text(note).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true) }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: the hub's own pages

/// The hub's web app, signed in with this Mac's session, for everything that isn't an approval.
struct HubWebView: NSViewRepresentable {
    let client: HubClient

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        view.customUserAgent = HubClient.userAgent
        let props: [HTTPCookiePropertyKey: Any] = [.name: HubClient.cookieName, .value: client.token, .domain: client.base.host ?? "", .path: "/"]
        if let cookie = HTTPCookie(properties: props) {
            config.websiteDataStore.httpCookieStore.setCookie(cookie) { view.load(URLRequest(url: client.base)) }
        } else {
            view.load(URLRequest(url: client.base))
        }
        return view
    }

    func updateNSView(_ view: WKWebView, context: Context) {}
}

// MARK: the menu bar

struct MenuBarContent: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let waiting = model.book.pending.filter { model.canAnswer($0) }
        if waiting.isEmpty { Text("Nothing waiting for your OK") }
        ForEach(waiting) { a in
            Button("\(a.botName.isEmpty ? "Bot" : a.botName): \(a.title)") { model.open(a.id) }
        }
        Divider()
        Button("Open MiBot") { (NSApp.delegate as? AppDelegate)?.showWindow() }
        Button("Quit MiBot") { NSApp.terminate(nil) }.keyboardShortcut("q")
    }
}
