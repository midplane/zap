import AppKit

enum ClipboardImage {
    static func read(from board: NSPasteboard) -> NSImage? {
        if let urls = board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], let url = urls.first {
            return NSImage(contentsOf: url)
        }
        return NSImage(pasteboard: board)
    }
}
