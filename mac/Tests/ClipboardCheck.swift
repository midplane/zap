import AppKit

@main struct ClipboardCheck {
    @MainActor static func main() throws {
        for text in ["zap://pair#secret", "  \nzap://pair#secret", "Invitation: ZAP://PAIR#secret\nKeep private"] {
            precondition(ClipboardContent.containsPairingCode(text), "Pairing code would enter history")
        }
        precondition(!ClipboardContent.containsPairingCode("Ordinary clipboard text"))
        let board = NSPasteboard.withUniqueName()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { board.releaseGlobally(); try? FileManager.default.removeItem(at: directory) }
        let original = bitmap(width: 13, height: 7)
        let png = original.representation(using: .png, properties: [:])!
        let icon = bitmap(width: 2, height: 2).tiffRepresentation!
        let file = directory.appendingPathComponent("image.png")
        try png.write(to: file)

        board.writeObjects([file as NSURL])
        board.setData(icon, forType: .tiff)
        precondition(pixels(NSImage(pasteboard: board)) == NSSize(width: 2, height: 2))
        precondition(pixels(ClipboardContent.read(from: board).image) == NSSize(width: 13, height: 7), "Captured Finder's icon instead of the image")

        board.clearContents(); board.setData(png, forType: .png)
        precondition(pixels(ClipboardContent.read(from: board).image) == NSSize(width: 13, height: 7), "Direct PNG capture failed")
        board.clearContents(); board.setData(original.tiffRepresentation!, forType: .tiff)
        precondition(pixels(ClipboardContent.read(from: board).image) == NSSize(width: 13, height: 7), "Direct TIFF capture failed")

        let text = directory.appendingPathComponent("notes.txt")
        try Data("Plain text".utf8).write(to: text)
        board.clearContents(); board.writeObjects([text as NSURL]); board.setData(icon, forType: .tiff)
        precondition(ClipboardContent.read(from: board).image == nil, "Captured a non-image file's icon")
        print("Finder image, direct PNG/TIFF, and non-image file checks passed")
    }
    static func bitmap(width: Int, height: Int) -> NSBitmapImageRep {
        NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    }
    static func pixels(_ image: NSImage?) -> NSSize? {
        guard let data = image?.tiffRepresentation, let bitmap = NSBitmapImageRep(data: data) else { return nil }
        return NSSize(width: bitmap.pixelsWide, height: bitmap.pixelsHigh)
    }
}
