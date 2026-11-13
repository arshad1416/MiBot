import AppKit
import Foundation
import MiBotKit

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// The app's state: the sign-in, the line to the hub, and every approval, kept in step with the hub's live events.
@MainActor
final class AppModel: ObservableObject {
    enum Line: Equatable { case signedOut, connecting, live, offline(String) }
    enum Page: Hashable { case approvals, hub }

    @Published private(set) var line: Line = .signedOut
    @Published private(set) var book = ApprovalBook()
    @Published var page: Page = .approvals
    /// The request on screen; a notification sets it.
    @Published var focusId: String?
    @Published private(set) var busy: Set<String> = []
    /// A note under a request: why an answer didn't count.
    @Published private(set) var notes: [String: String] = [:]
    @Published private(set) var signOutReason: String?

    private(set) var client: HubClient?
    let store: AccountStore
    let trace: Trace
    let notifier = Notifier()
    private var lastSync: Int64 = 0
    private var runTask: Task<Void, Never>?

    init(store: AccountStore, trace: Trace) {
        self.store = store
        self.trace = trace
        if let saved = store.saved() { book = saved.book; lastSync = saved.lastSync }
        if let a = store.account() { client = HubClient(base: a.hub, token: a.token); start() }
        notifier.onOpen = { [weak self] id in self?.open(id) }
    }

    var pendingCount: Int { book.pending.count }

    // MARK: signing in and out

    func signIn(hubText: String, code: String) async throws {
        let parsed = HubClient.parseLink(hubText)
        guard let hub = parsed?.hub ?? HubClient.normalize(hubText) else { throw HubError(0, "That doesn’t look like the hub’s address.") }
        let c = try await HubClient.pair(hub: hub, code: code.isEmpty ? (parsed?.code ?? "") : code)
        store.saveAccount(.init(hub: c.base, token: c.token))
        // A different hub's approvals have nothing to do with this one.
        book = ApprovalBook(); lastSync = 0; save()
        client = c
        signOutReason = nil
        trace.log("signed-in")
        notifier.askPermission()
        start()
    }

    func signOut() {
        let c = client
        Task { await c?.signOut() }
        forget(reason: nil)
    }

    private func forget(reason: String?) {
        runTask?.cancel(); runTask = nil
        client = nil
        store.saveAccount(nil)
        book = ApprovalBook(); lastSync = 0; save()
        notifier.removeAll()
        updateBadge()
        signOutReason = reason
        line = .signedOut
        trace.log("signed-out")
    }

    // MARK: the line to the hub

    func start() {
        runTask?.cancel()
        line = .connecting
        notifier.askPermission()
        runTask = Task { [weak self] in await self?.run() }
    }

    private func run() async {
        var wait: UInt64 = 1
        while !Task.isCancelled, let client {
            line = .connecting
            do {
                for try await ev in client.events() {
                    switch ev {
                    case .hello:
                        // Opened first, listed second, so nothing that happens in between is missed.
                        try await resync()
                        line = .live
                        wait = 1
                        trace.log("live")
                    case .approval(let a):
                        absorb(book.apply(a))
                    case .other:
                        break
                    }
                }
            } catch let e as HubError where e.signedOut {
                forget(reason: e.message)
                return
            } catch {
                if Task.isCancelled { return }
                line = .offline((error as? HubError)?.message ?? error.localizedDescription)
                trace.log("offline", nil, ["why": error.localizedDescription])
            }
            if Task.isCancelled { return }
            if case .live = line { line = .offline("Lost the hub. Reconnecting…") }
            try? await Task.sleep(nanoseconds: wait * 1_000_000_000)
            wait = min(wait * 2, 30)
        }
    }

    /// Catch up after a (re)connect or a restart: what is waiting, and what was decided while away.
    func resync() async throws {
        guard let client else { return }
        let r = try await client.approvals(since: lastSync > 0 ? lastSync - 60_000 : nil)
        for change in book.sync(r.approvals, serverTime: r.serverTime, localNow: nowMs()) { absorb(change) }
        if r.serverTime > 0 { lastSync = r.serverTime }
        book.prune(nowMs: nowMs())
        save()
        trace.log("synced", nil, ["pending": String(book.pending.count)])
    }

    private func absorb(_ change: ApprovalBook.Change?) {
        guard let change else { save(); return }
        switch change {
        case .needed(let a):
            trace.log("needed", a)
            if book.answerable(a, nowMs: nowMs()) {
                notifier.post(a, skewMs: book.skewMs)
                trace.log("notified", a)
            }
        case .settled(let a):
            trace.log("settled", a, ["by": a.resolvedBy ?? ""])
            notifier.remove(a.id)
        }
        updateBadge()
        save()
    }

    private func save() { store.save(.init(book: book, lastSync: lastSync)) }

    private func updateBadge() {
        let n = book.pending.filter { book.answerable($0, nowMs: nowMs()) }.count
        NSApp?.dockTile.badgeLabel = n > 0 ? String(n) : nil
    }

    // MARK: answering

    func canAnswer(_ a: Approval, at date: Date = Date()) -> Bool {
        line != .signedOut && book.answerable(a, nowMs: Int64(date.timeIntervalSince1970 * 1000))
    }

    /// `once` or `deny`. The hub decides which answer counts when two devices answer together.
    func decide(_ a: Approval, _ decision: String) {
        guard let client, !busy.contains(a.id), canAnswer(a) else { return }
        busy.insert(a.id)
        notes[a.id] = nil
        trace.log("deciding", a, ["decision": decision])
        Task {
            defer { busy.remove(a.id) }
            do {
                let d = try await client.decide(a.id, decision)
                if let now = d.approval { absorb(book.apply(now)) }
                if let err = d.error { notes[a.id] = err }
                trace.log(d.error == nil ? "decided" : "refused", d.approval ?? a, ["decision": decision, "error": d.error ?? ""])
            } catch let e as HubError {
                if e.signedOut { forget(reason: e.message); return }
                notes[a.id] = e.message
                trace.log("decide-failed", a, ["error": e.message])
            } catch {}
        }
    }

    /// Show one request (a notification was clicked). Never answers it.
    func open(_ id: String) {
        page = .approvals
        focusId = id
        trace.log("opened", book.all[id], ["id": id])
        (NSApp.delegate as? AppDelegate)?.showWindow()
    }
}
