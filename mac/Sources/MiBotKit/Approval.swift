import Foundation

/// Any JSON value, for a tool's arguments.
public enum JSONValue: Codable, Equatable, Sendable {
    case string(String), number(Double), bool(Bool), null
    case array([JSONValue]), object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .null: try c.encodeNil()
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    /// How it reads on one line: strings bare, everything else as JSON.
    public var text: String {
        if case .string(let s) = self { return s }
        guard let data = try? JSONEncoder.sorted.encode(self) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }
}

extension JSONEncoder {
    static var sorted: JSONEncoder { let e = JSONEncoder(); e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return e }
}

/// A Bot asking for an OK before it runs something, as the hub keeps it (GET /api/approvals).
public struct Approval: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var botId: String = ""
    public var botName: String = ""
    public var title: String = ""
    /// What it will do, a line per argument.
    public var detail: String = ""
    public var tool: String = ""
    public var toolkitName: String?
    public var args: JSONValue?
    public var rowSeq: Int = 0
    /// pending | allowed | always | denied | expired
    public var status: String = "pending"
    /// Times are milliseconds since 1970 by the hub's clock.
    public var createdAt: Int64 = 0
    public var expiresAt: Int64 = 0
    public var resolvedAt: Int64?
    /// The device that answered it.
    public var resolvedBy: String?

    public init(id: String, botName: String = "", title: String = "", detail: String = "", tool: String = "", args: JSONValue? = nil,
                status: String = "pending", createdAt: Int64 = 0, expiresAt: Int64 = 0, resolvedBy: String? = nil) {
        self.id = id; self.botName = botName; self.title = title; self.detail = detail; self.tool = tool; self.args = args
        self.status = status; self.createdAt = createdAt; self.expiresAt = expiresAt; self.resolvedBy = resolvedBy
    }

    private enum K: String, CodingKey { case id, botId, botName, title, detail, tool, toolkitName, args, rowSeq, status, createdAt, expiresAt, resolvedAt, resolvedBy }

    // Fields the hub leaves out take their defaults, so an older or newer hub still reads.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        id = try c.decode(String.self, forKey: .id)
        botId = try c.decodeIfPresent(String.self, forKey: .botId) ?? ""
        botName = try c.decodeIfPresent(String.self, forKey: .botName) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        detail = try c.decodeIfPresent(String.self, forKey: .detail) ?? ""
        tool = try c.decodeIfPresent(String.self, forKey: .tool) ?? ""
        toolkitName = try? c.decodeIfPresent(String.self, forKey: .toolkitName)
        args = try? c.decodeIfPresent(JSONValue.self, forKey: .args)
        rowSeq = (try? c.decodeIfPresent(Int.self, forKey: .rowSeq)) ?? 0
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "pending"
        createdAt = (try? c.decodeIfPresent(Int64.self, forKey: .createdAt)) ?? 0
        expiresAt = (try? c.decodeIfPresent(Int64.self, forKey: .expiresAt)) ?? 0
        resolvedAt = try? c.decodeIfPresent(Int64.self, forKey: .resolvedAt)
        resolvedBy = try? c.decodeIfPresent(String.self, forKey: .resolvedBy)
    }

    public var pending: Bool { status == "pending" }

    /// Whether it can still be answered, by the hub's clock (`skewMs` = hub time minus this Mac's time).
    public func answerable(nowMs: Int64, skewMs: Int64 = 0) -> Bool {
        pending && (expiresAt == 0 || nowMs + skewMs < expiresAt)
    }

    /// What became of it, for a request that can no longer be answered.
    public var outcome: String {
        let by = resolvedBy.map { " on \($0)" } ?? ""
        switch status {
        case "allowed", "always": return "Approved" + by
        case "denied": return "Denied" + by
        case "expired": return "Expired before anyone answered"
        default: return "Waiting for you"
        }
    }

    /// The command itself when there is one, else what the hub says it will do, else the arguments.
    public var commandText: String {
        if case .object(let o)? = args, case .string(let command)? = o["command"] { return command }
        if !detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return detail }
        if case .object(let o)? = args, !o.isEmpty {
            return o.keys.sorted().map { k in String("\(k): \(o[k]!.text)".prefix(300)) }.joined(separator: "\n")
        }
        return ""
    }
}

/// "Expires in 4 min", by the hub's clock.
public func expiresIn(_ ms: Int64) -> String {
    let s = max(0, ms / 1000)
    if s < 60 { return "Expires in \(s)s" }
    if s < 3600 { return "Expires in \(s / 60) min" }
    return "Expires in \(s / 3600) h"
}
