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
                let ink = NSColor(srgbRed: 24 / 255, green: 44 / 255, blue: 59 / 255, alpha: 1)
                ink.setFill()
                NSBezierPath(roundedRect: NSRect(x: 6, y: 6, width: 96, height: 96), xRadius: 22, yRadius: 22).fill()
                context.saveGState()
                context.translateBy(x: 46, y: 57); context.rotate(by: -.pi / 15); context.translateBy(x: -46, y: -57)
                NSColor(srgbRed: 242 / 255, green: 244 / 255, blue: 233 / 255, alpha: 1).setFill()
                NSBezierPath(roundedRect: NSRect(x: 27, y: 31, width: 38, height: 52), xRadius: 5, yRadius: 5).fill()
                context.restoreGState()
                context.saveGState()
                context.translateBy(x: 61, y: 52); context.rotate(by: .pi / 18); context.translateBy(x: -61, y: -52)
                context.setShadow(offset: CGSize(width: -2, height: 3), blur: 4, color: NSColor.black.withAlphaComponent(0.2).cgColor)
                NSColor(srgbRed: 223 / 255, green: 242 / 255, blue: 121 / 255, alpha: 1).setFill()
                NSBezierPath(roundedRect: NSRect(x: 42, y: 25, width: 38, height: 52), xRadius: 5, yRadius: 5).fill()
                context.setShadow(offset: .zero, blur: 0, color: nil)
                ink.setFill()
                for (y, width) in [(42, 22), (51, 22), (60, 13)] {
                    NSBezierPath(roundedRect: NSRect(x: 50, y: y, width: width, height: 3), xRadius: 1.5, yRadius: 1.5).fill()
                }
                context.restoreGState()
                NSGraphicsContext.restoreGraphicsState()
                let suffix = scale == 2 ? "@2x" : ""
                try bitmap.representation(using: .png, properties: [:])!.write(to: directory.appendingPathComponent("icon_\(size)x\(size)\(suffix).png"))
            }
        }
    }
}
