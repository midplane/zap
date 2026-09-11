// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "Zap", platforms: [.macOS(.v14)], products: [.executable(name: "Zap", targets: ["Zap"])], targets: [.executableTarget(name: "Zap", linkerSettings: [.linkedLibrary("sqlite3")])])
