import Foundation
import MiBotKit
import UserNotifications

/// macOS notifications for requests that need an OK. A notification carries no Approve or Deny button:
/// clicking it opens the request in the app, where the person decides.
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    var onOpen: (@MainActor (String) -> Void)?
    private var expiryTimers: [String: Task<Void, Never>] = [:]

    override init() {
        super.init()
        center.delegate = self
    }

    func askPermission() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private static func key(_ id: String) -> String { "approval.\(id)" }

    func post(_ a: Approval, skewMs: Int64) {
        let content = UNMutableNotificationContent()
        content.title = a.botName.isEmpty ? "A Bot needs your OK" : "\(a.botName) needs your OK"
        content.subtitle = a.title
        let command = a.commandText
        content.body = command.isEmpty ? "Open MiBot to see what it wants to run." : String(command.prefix(240))
        content.sound = .default
        content.threadIdentifier = "approvals"
        content.userInfo = ["approvalId": a.id]
        if #available(macOS 12.0, *) { content.interruptionLevel = .timeSensitive }
        center.add(UNNotificationRequest(identifier: Self.key(a.id), content: content, trigger: nil))
        // macOS has no timeout for a notification: take it down when the request runs out.
        if a.expiresAt > 0 {
            let left = max(0, a.expiresAt - skewMs - nowMs())
            expiryTimers[a.id]?.cancel()
            expiryTimers[a.id] = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(left) * 1_000_000)
                if !Task.isCancelled { self?.remove(a.id) }
            }
        }
    }

    func remove(_ id: String) {
        center.removeDeliveredNotifications(withIdentifiers: [Self.key(id)])
        center.removePendingNotificationRequests(withIdentifiers: [Self.key(id)])
        expiryTimers.removeValue(forKey: id)?.cancel()
    }

    func removeAll() {
        center.removeAllDeliveredNotifications()
        expiryTimers.values.forEach { $0.cancel() }
        expiryTimers = [:]
    }

    // Shown even while MiBot is the app in front.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .list, .sound])
    }

    // Clicking it opens that request. It does not answer it.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let id = response.notification.request.content.userInfo["approvalId"] as? String
        Task { @MainActor in
            if let id { self.onOpen?(id) }
            done()
        }
    }
}
