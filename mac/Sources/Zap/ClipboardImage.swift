import AppKit

/// A snapshot of the pasteboard taken on the main actor. Decoding, PNG encoding, and
/// base64 are expensive for large images, so they run off the main actor from this value.
struct ClipboardContent: Sendable {
    var file: URL?
    var imageData: Data?
    var text: String?
    static func containsPairingCode(_ text: String) -> Bool { text.range(of: "zap://pair#", options: .caseInsensitive) != nil }

    static func read(from board: NSPasteboard) -> ClipboardContent {
        let file = (board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL])?.first
        let types = NSImage.imageTypes.map(NSPasteboard.PasteboardType.init(rawValue:))
        let data = file == nil ? board.availableType(from: types).flatMap({ board.data(forType: $0) }) : nil
        return ClipboardContent(file: file, imageData: data, text: board.string(forType: .string))
    }
    /// A copied file describes itself: Finder also offers its icon, which is never the content.
    var image: NSImage? { file.flatMap(NSImage.init(contentsOf:)) ?? imageData.flatMap(NSImage.init(data:)) }
}
