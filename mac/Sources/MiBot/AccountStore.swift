import Foundation
import MiBotKit

/// Where this Mac keeps its sign-in and the approvals it last saw, in
/// ~/Library/Application Support/MiBot/<profile>/, readable by this user only.
/// `--profile NAME` keeps a test run apart from the everyday sign-in.
struct AccountStore {
    struct Account: Codable { var hub: URL; var token: String }
    struct Saved: Codable { var book: ApprovalBook; var lastSync: Int64 }

    let dir: URL

    init(profile: String) {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = support.appendingPathComponent("MiBot", isDirectory: true).appendingPathComponent(profile, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    }

    private var accountFile: URL { dir.appendingPathComponent("account.json") }
    private var savedFile: URL { dir.appendingPathComponent("approvals.json") }

    func account() -> Account? { read(Account.self, accountFile) }
    func saveAccount(_ a: Account?) { write(a, accountFile) }
    func saved() -> Saved? { read(Saved.self, savedFile) }
    func save(_ s: Saved?) { write(s, savedFile) }

    private func read<T: Decodable>(_ type: T.Type, _ url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func write<T: Encodable>(_ value: T?, _ url: URL) {
        guard let value else { try? FileManager.default.removeItem(at: url); return }
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

/// With `--trace FILE`, one JSON line per thing that happened to an approval, so a test can follow the app
/// without reading the screen. Off otherwise.
struct Trace {
    let file: URL?

    func log(_ event: String, _ a: Approval? = nil, _ extra: [String: String] = [:]) {
        guard let file else { return }
        var line: [String: Any] = ["t": Int64(Date().timeIntervalSince1970 * 1000), "event": event]
        if let a { line["id"] = a.id; line["status"] = a.status }
        for (k, v) in extra { line[k] = v }
        guard var data = try? JSONSerialization.data(withJSONObject: line, options: [.sortedKeys]) else { return }
        data.append(0x0A)
        if let h = try? FileHandle(forWritingTo: file) {
            h.seekToEndOfFile(); h.write(data); try? h.close()
        } else {
            try? data.write(to: file)
        }
    }
}
