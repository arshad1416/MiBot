import XCTest
@testable import MiBotKit

final class ApprovalBookTests: XCTestCase {
    func a(_ id: String, _ status: String = "pending", by: String? = nil, created: Int64 = 1, expires: Int64 = 0) -> Approval {
        Approval(id: id, botName: "Bot", title: "Send an email", status: status, createdAt: created, expiresAt: expires, resolvedBy: by)
    }

    func testANewRequestIsNeededOnceAndItsAnswerSettlesIt() {
        var book = ApprovalBook()
        XCTAssertEqual(book.apply(a("1")), .needed(a("1")))
        XCTAssertNil(book.apply(a("1")), "the same request again is not news")
        XCTAssertEqual(book.apply(a("1", "allowed", by: "phone")), .settled(a("1", "allowed", by: "phone")))
        XCTAssertNil(book.apply(a("1", "allowed", by: "phone")))
        XCTAssertTrue(book.pending.isEmpty)
    }

    func testAnAnswerIsNeverUndoneByAnOlderWaitingCopy() {
        var book = ApprovalBook()
        book.apply(a("1", "denied"))
        XCTAssertNil(book.apply(a("1")))
        XCTAssertEqual(book.all["1"]?.status, "denied")
    }

    func testAfterAReconnectARequestTheHubNoLongerListsHasRunOut() {
        var book = ApprovalBook()
        book.apply(a("gone")); book.apply(a("kept"))
        let changes = book.sync([a("kept"), a("new", created: 5)], serverTime: 10_000, localNow: 9_000)
        XCTAssertEqual(book.all["gone"]?.status, "expired")
        XCTAssertEqual(book.skewMs, 1_000)
        XCTAssertTrue(changes.contains(.needed(a("new", created: 5))))
        XCTAssertTrue(changes.contains { if case .settled(let x) = $0 { return x.id == "gone" } else { return false } })
        XCTAssertEqual(book.pending.map(\.id), ["kept", "new"])
    }

    func testDecidedWhileAwayIsPickedUpNotExpired() {
        var book = ApprovalBook()
        book.apply(a("1"))
        _ = book.sync([a("1", "allowed", by: "MiBot app on Android")], serverTime: 1, localNow: 1)
        XCTAssertEqual(book.all["1"]?.outcome, "Approved on MiBot app on Android")
    }

    func testExpiryUsesTheHubsClock() {
        var book = ApprovalBook()
        let x = a("1", expires: 10_000)
        book.apply(x)
        book.skewMs = 2_000 // the hub is 2 s ahead
        XCTAssertTrue(book.answerable(x, nowMs: 7_999))
        XCTAssertFalse(book.answerable(x, nowMs: 8_000))
        XCTAssertFalse(book.answerable(a("2", "allowed"), nowMs: 0), "a decided request has no buttons")
    }

    func testListingPutsWaitingFirstAndPrunesOldAnswers() {
        var book = ApprovalBook()
        var old = a("old", "denied"); old.resolvedAt = 0
        book.apply(old); book.apply(a("w", created: 3))
        XCTAssertEqual(book.listed().map(\.id), ["w", "old"])
        book.prune(nowMs: 25 * 3600 * 1000)
        XCTAssertEqual(Array(book.all.keys), ["w"])
    }

    func testTheBookSurvivesARestart() throws {
        var book = ApprovalBook()
        book.apply(Approval(id: "1", title: "Run", args: .object(["command": .string("rm -rf build")])))
        let back = try JSONDecoder().decode(ApprovalBook.self, from: JSONEncoder().encode(book))
        XCTAssertEqual(back, book)
    }
}

final class HubClientTests: XCTestCase {
    func testAddresses() {
        XCTAssertEqual(HubClient.normalize("mac-mini:8787")?.absoluteString, "http://mac-mini:8787")
        XCTAssertEqual(HubClient.normalize(" HTTPS://hub.example.ts.net/ ")?.absoluteString, "https://hub.example.ts.net")
        XCTAssertNil(HubClient.normalize("http//mini"))
        XCTAssertNil(HubClient.normalize("http:/mini"))
        XCTAssertNil(HubClient.normalize("user:pw@mini"))
        XCTAssertNil(HubClient.normalize("mini:99999"))
        XCTAssertNil(HubClient.normalize(""))
    }

    func testPairingLinks() {
        let p = HubClient.parseLink("http://mac-mini:8787/pair#ABCD2345")
        XCTAssertEqual(p?.hub.absoluteString, "http://mac-mini:8787")
        XCTAssertEqual(p?.code, "ABCD2345")
        XCTAssertNil(HubClient.parseLink("mac-mini:8787"))
        XCTAssertEqual(HubClient.cleanCode("abcd-2345 "), "ABCD2345")
    }

    func testSessionCookie() {
        XCTAssertEqual(HubClient.sessionToken(fromSetCookie: "grokbot_session=abc123; Path=/; HttpOnly"), "abc123")
        XCTAssertEqual(HubClient.sessionToken(fromSetCookie: "other=1; Path=/, grokbot_session=xyz; Path=/"), "xyz")
        XCTAssertNil(HubClient.sessionToken(fromSetCookie: "other=1"))
    }

    func testEvents() {
        XCTAssertEqual(ServerEvent.parse(#"{"type":"hello","serverTime":42}"#), .hello(serverTime: 42))
        guard case .approval(let a)? = ServerEvent.parse(#"{"type":"approval","approval":{"id":"apr_1","status":"denied","resolvedBy":"MiBot app on Mac","extra":true}}"#) else { return XCTFail() }
        XCTAssertEqual(a.outcome, "Denied on MiBot app on Mac")
        XCTAssertEqual(ServerEvent.parse(#"{"type":"ping"}"#), .other)
        XCTAssertNil(ServerEvent.parse("not json"))
    }

    func testCommandText() {
        XCTAssertEqual(Approval(id: "1", detail: "to: a@b", args: .object(["command": .string("ls -la")])).commandText, "ls -la")
        XCTAssertEqual(Approval(id: "1", detail: "to: a@b").commandText, "to: a@b")
        XCTAssertEqual(Approval(id: "1", args: .object(["n": .number(2), "to": .string("x")])).commandText, "n: 2\nto: x")
        XCTAssertEqual(expiresIn(59_000), "Expires in 59s")
        XCTAssertEqual(expiresIn(300_000), "Expires in 5 min")
    }
}
