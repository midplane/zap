import AppKit

@main struct GenerateIcon {
    static func main() throws {
        let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for size in [16, 32, 128, 256, 512] {
            for scale in [1, 2] {
                let pixels = size * scale
                let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
                NSGraphicsContext.saveGraphicsState()
                NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
                let context = NSGraphicsContext.current!.cgContext
                context.scaleBy(x: CGFloat(pixels) / 108, y: CGFloat(pixels) / 108)
                context.translateBy(x: 0, y: 108)
                context.scaleBy(x: 1, y: -1)
                NSColor(srgbRed: 36 / 255, green: 90 / 255, blue: 197 / 255, alpha: 1).setFill()
                NSBezierPath(roundedRect: NSRect(x: 6, y: 6, width: 96, height: 96), xRadius: 22, yRadius: 22).fill()
                NSColor.white.setFill()
                NSBezierPath(roundedRect: NSRect(x: 31, y: 28, width: 46, height: 59), xRadius: 7, yRadius: 7).fill()
                NSColor(srgbRed: 36 / 255, green: 90 / 255, blue: 197 / 255, alpha: 1).setFill()
                NSBezierPath(roundedRect: NSRect(x: 41, y: 21, width: 26, height: 17), xRadius: 5, yRadius: 5).fill()
                NSColor.white.setFill()
                NSBezierPath(roundedRect: NSRect(x: 44, y: 23, width: 20, height: 12), xRadius: 3, yRadius: 3).fill()
                NSColor(srgbRed: 36 / 255, green: 90 / 255, blue: 197 / 255, alpha: 1).setFill()
                let bolt = NSBezierPath()
                bolt.move(to: NSPoint(x: 58, y: 42))
                for point in [(42, 62), (52, 62), (49, 76), (67, 53), (56, 53)] { bolt.line(to: NSPoint(x: point.0, y: point.1)) }
                bolt.close(); bolt.fill()
                NSGraphicsContext.restoreGraphicsState()
                let suffix = scale == 2 ? "@2x" : ""
                try bitmap.representation(using: .png, properties: [:])!.write(to: directory.appendingPathComponent("icon_\(size)x\(size)\(suffix).png"))
            }
        }
    }
}
