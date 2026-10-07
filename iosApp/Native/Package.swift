// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "LilacLocalAI",
    platforms: [.iOS(.v16)],
    products: [.library(name: "LilacLocalAI", targets: ["LilacLocalAI"])],
    targets: [
        .binaryTarget(name: "llama", url: "https://github.com/ggml-org/llama.cpp/releases/download/b5046/llama-b5046-xcframework.zip",
                      checksum: "c19be78b5f00d8d29a25da41042cb7afa094cbf6280a225abe614b03b20029ab"),
        .target(name: "LilacLocalAI", dependencies: ["llama"], publicHeadersPath: "include")
    ], cxxLanguageStandard: .cxx17)
