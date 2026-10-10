import AppKit
import Foundation

// Generate opaque iOS assets from the existing desktop brand at build time.
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let source = root.appendingPathComponent("Branding/AppIcon.png")
guard let image = NSImage(contentsOf: source) else { fatalError("App icon is missing") }
let directory = root.appendingPathComponent("LilacAnime/Assets.xcassets/AppIcon.appiconset")
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
let specs: [(String, Int, Int)] = [("iphone", 20, 2), ("iphone", 20, 3), ("iphone", 29, 2), ("iphone", 29, 3), ("iphone", 40, 2), ("iphone", 40, 3), ("iphone", 60, 2), ("iphone", 60, 3), ("ipad", 20, 1), ("ipad", 20, 2), ("ipad", 29, 1), ("ipad", 29, 2), ("ipad", 40, 1), ("ipad", 40, 2), ("ipad", 76, 1), ("ipad", 76, 2), ("ipad", 83, 2), ("ios-marketing", 1024, 1)]
var entries: [[String: String]] = []
for (idiom, points, scale) in specs {
    let size = points == 83 ? 167 : points * scale
    let name = "icon-\(size).png"
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    let previous = NSGraphicsContext.current
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    NSColor.white.setFill(); NSRect(x: 0, y: 0, width: size, height: size).fill()
    NSGraphicsContext.current?.imageInterpolation = .high
    image.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
    NSGraphicsContext.current = previous
    try bitmap.representation(using: .png, properties: [:])!.write(to: directory.appendingPathComponent(name))
    let dimension = points == 83 ? "83.5" : String(points)
    entries.append(["idiom": idiom, "size": "\(dimension)x\(dimension)", "scale": "\(scale)x", "filename": name])
}
try JSONSerialization.data(withJSONObject: ["images": entries, "info": ["version": 1, "author": "xcode"]], options: [.prettyPrinted, .sortedKeys]).write(to: directory.appendingPathComponent("Contents.json"))
