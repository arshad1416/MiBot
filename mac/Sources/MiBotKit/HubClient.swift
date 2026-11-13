import Foundation

/// Something the hub said no to, or could not be reached for.
public struct HubError: Error, LocalizedError, Equatable, Sendable {
    /// The HTTP status, or 0 when the hub could not be reached.
    public let status: Int
    public let message: String
    public init(_ status: Int, _ message: String) { self.status = status; self.message = message }
    public var errorDescription: String? { message }
    public var signedOut: Bool { status == 401 }
}

public struct ApprovalsResponse: Decodable, Sendable {
    public var approvals: [Approval] = []
    public var serverTime: Int64 = 0
    private enum K: String, CodingKey { case approvals, serverTime }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        // A row this app cannot read is skipped rather than losing the whole list.
        approvals = ((try? c.decode([Lenient<Approval>].self, forKey: .approvals)) ?? []).compactMap(\.value)
        serverTime = (try? c.decode(Int64.self, forKey: .serverTime)) ?? 0
    }
}

struct Lenient<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

/// What answering led to: the approval as it now stands, and `error` when another answer, or the clock, got there first.
public struct Decision: Sendable {
    public let approval: Approval?
    public let error: String?
}

/// What the hub pushes on GET /api/events that this app uses.
public enum ServerEvent: Sendable, Equatable {
    case hello(serverTime: Int64)
    case approval(Approval)
    case other

    public static func parse(_ data: String) -> ServerEvent? {
        guard let obj = try? JSONSerialization.jsonObject(with: Data(data.utf8)) as? [String: Any], let type = obj["type"] as? String else { return nil }
        switch type {
        case "hello": return .hello(serverTime: (obj["serverTime"] as? NSNumber)?.int64Value ?? 0)
        case "approval":
            guard let raw = obj["approval"], let json = try? JSONSerialization.data(withJSONObject: raw),
                  let a = try? JSONDecoder().decode(Approval.self, from: json) else { return nil }
            return .approval(a)
        default: return .other
        }
    }
}

/// The hub, as this Mac is signed in to it.
public final class HubClient: @unchecked Sendable {
    public static let cookieName = "grokbot_session"
    public let base: URL
    public let token: String
    private let http: URLSession
    private let stream: URLSession

    public init(base: URL, token: String) {
        self.base = base
        self.token = token
        http = URLSession(configuration: Self.config(timeout: 30))
        stream = URLSession(configuration: Self.config(timeout: 75)) // the hub pings every 20 s
    }

    static func config(timeout: TimeInterval) -> URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        // The session cookie is sent by hand, so no other cookie store is involved.
        c.httpShouldSetCookies = false
        c.httpCookieAcceptPolicy = .never
        c.timeoutIntervalForRequest = timeout
        c.waitsForConnectivity = false
        return c
    }

    /// Names this app to the hub, so Settings › Phones and browsers and "approved on …" say "MiBot app on Mac".
    public static var userAgent: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        let app = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
        return "MiBot-Mac/\(app) (Macintosh; macOS \(v.majorVersion).\(v.minorVersion))"
    }

    /// The value of the Cookie header that signs a request in, for the web view too.
    public var cookie: String { "\(Self.cookieName)=\(token)" }

    func request(_ method: String, _ path: String, body: [String: Any]? = nil) -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent("api").appendingPathComponent(path))
        r.httpMethod = method
        r.setValue(cookie, forHTTPHeaderField: "Cookie")
        r.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        if let body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    static func fetch(_ session: URLSession, _ r: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, res) = try await session.data(for: r)
            return (data, (res as? HTTPURLResponse)?.statusCode ?? 0)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw HubError(0, "Couldn’t reach the hub at \(r.url?.host ?? "?"): \(error.localizedDescription)")
        }
    }

    static func failure(_ status: Int, _ data: Data) -> HubError {
        let said = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
        if status == 401 { return HubError(401, "This Mac was signed out of the hub.") }
        return HubError(status, said ?? "The hub answered \(status).")
    }

    public func signedIn() async throws -> Bool {
        let (data, status) = try await Self.fetch(http, request("GET", "auth/me"))
        guard status == 200 else { throw Self.failure(status, data) }
        return ((try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["user"] as? [String: Any]) != nil
    }

    public func approvals(since: Int64? = nil) async throws -> ApprovalsResponse {
        var r = request("GET", "approvals")
        if let since, var parts = URLComponents(url: r.url!, resolvingAgainstBaseURL: false) {
            parts.queryItems = [URLQueryItem(name: "since", value: String(since))]
            r.url = parts.url
        }
        let (data, status) = try await Self.fetch(http, r)
        guard status == 200 else { throw Self.failure(status, data) }
        do { return try JSONDecoder().decode(ApprovalsResponse.self, from: data) } catch { throw HubError(status, "The hub sent a list this app can’t read.") }
    }

    /// Answer an approval: `once` or `deny` (this app never offers "always").
    public func decide(_ id: String, _ decision: String) async throws -> Decision {
        let (data, status) = try await Self.fetch(http, request("POST", "approvals/\(id)", body: ["decision": decision]))
        guard status == 200 || status == 409 else { throw Self.failure(status, data) }
        let obj = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let approval = (obj["approval"]).flatMap { try? JSONSerialization.data(withJSONObject: $0) }.flatMap { try? JSONDecoder().decode(Approval.self, from: $0) }
        return Decision(approval: approval, error: status == 409 ? (obj["error"] as? String ?? "Already answered.") : nil)
    }

    public func signOut() async { _ = try? await Self.fetch(http, request("POST", "auth/logout")) }

    /// The live event stream. Ends (with an error) when the line drops; the caller reconnects.
    public func events() -> AsyncThrowingStream<ServerEvent, Error> {
        var r = request("GET", "events")
        r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        r.timeoutInterval = 75
        let session = stream
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, res) = try await session.bytes(for: r)
                    let status = (res as? HTTPURLResponse)?.statusCode ?? 0
                    guard status == 200 else { throw Self.failure(status, Data()) }
                    // The hub writes each event as one `data:` line, so each is read on its own (`lines` skips the blank separators).
                    for try await line in bytes.lines {
                        guard line.hasPrefix("data:") else { continue }
                        if let ev = ServerEvent.parse(String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)) { continuation.yield(ev) }
                    }
                    continuation.finish(throwing: HubError(0, "The hub closed the connection."))
                } catch let e as HubError {
                    continuation.finish(throwing: e)
                } catch is CancellationError {
                    continuation.finish()
                } catch let e as URLError where e.code == .cancelled {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: HubError(0, "Lost the hub: \(error.localizedDescription)"))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: signing in with a code

    /// What a typed address stands for: `mac-mini:8787` is `http://mac-mini:8787`. Nil when it can't be a hub's address.
    public static func normalize(_ text: String) -> URL? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var scheme = "http", rest = t
        if let r = t.range(of: "^(https?)://", options: [.regularExpression, .caseInsensitive]) {
            scheme = t[r].lowercased().replacingOccurrences(of: "://", with: "")
            rest = String(t[r.upperBound...])
        }
        while rest.hasSuffix("/") { rest.removeLast() }
        // A slip in the scheme ("http:/mini", "http//mini") would otherwise make "http" the host.
        if rest.isEmpty || rest.range(of: "^[^/]*//", options: .regularExpression) != nil { return nil }
        let hostPort = String(rest.prefix { $0 != "/" && $0 != "?" && $0 != "#" })
        if hostPort.isEmpty || hostPort.contains(where: { $0.isWhitespace || $0 == "\\" || $0 == "@" }) { return nil }
        if let colon = hostPort.lastIndex(of: ":"), !hostPort.hasPrefix("[") {
            let port = hostPort[hostPort.index(after: colon)...]
            guard let n = Int(port), (1...65535).contains(n) else { return nil }
        }
        guard let url = URL(string: "\(scheme)://\(hostPort)"), url.host != nil else { return nil }
        return url
    }

    /// A pairing link (`http://mini:8787/pair#ABCD2345`) split into the hub and the code.
    public static func parseLink(_ text: String) -> (hub: URL, code: String)? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let hash = t.firstIndex(of: "#") else { return nil }
        let code = String(t[t.index(after: hash)...])
        var hub = String(t[..<hash])
        if hub.hasSuffix("/pair") { hub.removeLast(5) }
        guard let url = normalize(hub), !code.isEmpty else { return nil }
        return (url, code)
    }

    /// Codes are shown as `ABCD-2345`; spaces, dashes and case don't matter.
    public static func cleanCode(_ code: String) -> String {
        code.uppercased().filter { $0.isLetter || $0.isNumber }
    }

    /// Hand the hub a one-time code from Settings › Machines › Add a device. Returns the sign-in for this Mac.
    public static func pair(hub: URL, code: String) async throws -> HubClient {
        var r = URLRequest(url: hub.appendingPathComponent("api/pair/sign-in"))
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["code": cleanCode(code)])
        let session = URLSession(configuration: config(timeout: 20))
        let (data, res) = try await { () async throws -> (Data, URLResponse) in
            do { return try await session.data(for: r) } catch { throw HubError(0, "Couldn’t reach the hub at \(hub.host ?? "?"): \(error.localizedDescription)") }
        }()
        let http = res as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        guard status == 200 else {
            let said = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw HubError(status, said ?? "The hub answered \(status).")
        }
        guard let token = sessionToken(fromSetCookie: http?.value(forHTTPHeaderField: "Set-Cookie") ?? "") else {
            throw HubError(status, "The hub accepted the code but sent no sign-in.")
        }
        return HubClient(base: hub, token: token)
    }

    /// The session token in a Set-Cookie header (several cookies may be folded into one line).
    public static func sessionToken(fromSetCookie header: String) -> String? {
        guard let r = header.range(of: "\(cookieName)=") else { return nil }
        let value = header[r.upperBound...].prefix { $0 != ";" && $0 != "," }
        return value.isEmpty ? nil : String(value)
    }
}
