// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "LilacLocalAI",
    platforms: [.iOS(.v16)],
    products: [.library(name: "LilacLocalAI", targets: ["LilacLocalAI"])],
    targets: [
        .binaryTarget(name: "llama", path: "Frameworks/llama.xcframework"),
        .target(name: "LilacLocalAI", dependencies: ["llama"], exclude: ["Jinja/LICENSE.txt"], publicHeadersPath: "include")
    ], cxxLanguageStandard: .cxx17)
