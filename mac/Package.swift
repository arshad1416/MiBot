// swift-tools-version: 5.10
// The MiBot Mac app. Build the app with scripts/build-app.sh; run the logic tests with `swift test`.
import PackageDescription

let package = Package(
    name: "MiBot",
    platforms: [.macOS(.v14)],
    targets: [
        // Talking to the hub and keeping approvals straight: no AppKit, so it is tested on its own.
        .target(name: "MiBotKit"),
        .executableTarget(name: "MiBot", dependencies: ["MiBotKit"]),
        .testTarget(name: "MiBotKitTests", dependencies: ["MiBotKit"]),
    ]
)
