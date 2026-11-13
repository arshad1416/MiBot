import Foundation

/// Every approval this Mac knows about, kept the same way as on the phone (android/core HubState.kt):
/// an answer, once known, is never replaced by an older "still waiting" copy, and a request the hub no
/// longer lists after a reconnect has run out.
public struct ApprovalBook: Equatable, Codable, Sendable {
    public private(set) var all: [String: Approval] = [:]
    /// Hub time minus this Mac's time, from the last list the hub sent.
    public var skewMs: Int64 = 0

    public init() {}

    /// What a change means for the person: a new request to tell them about, or one that no longer needs them.
    public enum Change: Equatable, Sendable {
        case needed(Approval)
        case settled(Approval)
    }

    /// One approval as the hub now has it (a live event, or the answer to a decision).
    @discardableResult
    public mutating func apply(_ a: Approval) -> Change? {
        let held = all[a.id]
        if let held, !held.pending, a.pending { return nil }
        all[a.id] = a
        if a.pending { return held == nil ? .needed(a) : nil }
        return held?.pending != false ? .settled(a) : nil
    }

    /// The hub's list after a (re)connect: every approval still waiting and those decided since `since`.
    public mutating func sync(_ fresh: [Approval], serverTime: Int64, localNow: Int64) -> [Change] {
        var changes = fresh.compactMap { apply($0) }
        let listed = Set(fresh.map(\.id))
        for var lost in all.values where lost.pending && !listed.contains(lost.id) {
            lost.status = "expired"
            all[lost.id] = lost
            changes.append(.settled(lost))
        }
        if serverTime > 0 { skewMs = serverTime - localNow }
        return changes
    }

    /// Waiting ones, oldest first.
    public var pending: [Approval] { all.values.filter(\.pending).sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) } }

    /// Waiting ones first, then the most recently decided, up to `limit` in all.
    public func listed(limit: Int = 30) -> [Approval] {
        let done = all.values.filter { !$0.pending }.sorted { ($0.resolvedAt ?? $0.createdAt) > ($1.resolvedAt ?? $1.createdAt) }
        return Array((pending + done).prefix(max(limit, pending.count)))
    }

    /// Drops decided ones older than `keepMs`, so a long-running app does not grow without end.
    public mutating func prune(nowMs: Int64, keepMs: Int64 = 24 * 3600 * 1000) {
        all = all.filter { $0.value.pending || ($0.value.resolvedAt ?? $0.value.createdAt) + keepMs > nowMs + skewMs }
    }

    public func answerable(_ a: Approval, nowMs: Int64) -> Bool { a.answerable(nowMs: nowMs, skewMs: skewMs) }
}
