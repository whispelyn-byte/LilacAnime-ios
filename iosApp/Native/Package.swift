// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "LilacLocalAI",
    platforms: [.iOS(.v16)],
    products: [.library(name: "LilacLocalAI", targets: ["LilacLocalAI"])],
    targets: [
        .binaryTarget(name: "llama", url: "https://github.com/ggml-org/llama.cpp/releases/download/b11490/llama-b11490-xcframework.zip",
                      checksum: "bc19f561ae2504cb2b3e7b189f44c8a81f8aa0fd84f70b9e81c2ff92a98a086c"),
        .target(name: "LilacLocalAI", dependencies: ["llama"], exclude: ["Jinja/LICENSE.txt"], publicHeadersPath: "include")
    ], cxxLanguageStandard: .cxx17)
