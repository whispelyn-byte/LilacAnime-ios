import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// Generate opaque iOS assets from the existing desktop brand at build time.
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let source = root.appendingPathComponent("Branding/AppIcon.png")
guard let input = CGImageSourceCreateWithURL(source as CFURL, nil), let image = CGImageSourceCreateImageAtIndex(input, 0, nil) else { fatalError("App icon is missing") }
let directory = root.appendingPathComponent("LilacAnime/Assets.xcassets/AppIcon.appiconset")
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
let specs: [(String, Int, Int)] = [("iphone", 20, 2), ("iphone", 20, 3), ("iphone", 29, 2), ("iphone", 29, 3), ("iphone", 40, 2), ("iphone", 40, 3), ("iphone", 60, 2), ("iphone", 60, 3), ("ipad", 20, 1), ("ipad", 20, 2), ("ipad", 29, 1), ("ipad", 29, 2), ("ipad", 40, 1), ("ipad", 40, 2), ("ipad", 76, 1), ("ipad", 76, 2), ("ipad", 83, 2), ("ios-marketing", 1024, 1)]
var entries: [[String: String]] = []
for (idiom, points, scale) in specs {
    let size = points == 83 ? 167 : points * scale
    let name = "icon-\(size).png"
    let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    let bounds = CGRect(x: 0, y: 0, width: size, height: size)
    context.fill(bounds); context.interpolationQuality = .high; context.draw(image, in: bounds)
    let output = directory.appendingPathComponent(name)
    let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("Could not write \(name)") }
    let dimension = points == 83 ? "83.5" : String(points)
    entries.append(["idiom": idiom, "size": "\(dimension)x\(dimension)", "scale": "\(scale)x", "filename": name])
}
try JSONSerialization.data(withJSONObject: ["images": entries, "info": ["version": 1, "author": "xcode"]], options: [.prettyPrinted, .sortedKeys]).write(to: directory.appendingPathComponent("Contents.json"))
