import SwiftUI
import JavaScriptCore
import UniformTypeIdentifiers

struct CleanupRegion: Codable, Equatable, Identifiable {
    var id = UUID()
    var x = 0.7, y = 0.05, width = 0.25, height = 0.12
    var valid: Bool { [x, y, width, height].allSatisfy(\.isFinite) && x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.001 && y + height <= 1.001 }
}
struct VideoCleanupSettings: Codable, Equatable {
    var watermark = false
    var subtitles = false
    var watermarkRegions: [CleanupRegion] = []
    var subtitleRegions: [CleanupRegion] = []
    var maskRevision: String?
}
struct WatermarkMask: Codable {
    var rgba: [UInt8]
    var width: Int
    var height: Int
    var origin: [Double]
    var extent: [Double]
    var valid: Bool {
        width > 0 && height > 0 && width <= 4096 && height <= 4096 && width * height <= 700000 && rgba.count == width * height * 4 &&
        origin.count == 2 && extent.count == 2 && (origin + extent).allSatisfy(\.isFinite) &&
        origin.allSatisfy { $0 >= 0 && $0 <= 1 } && extent.allSatisfy { $0 > 0 && $0 <= 1 } &&
        origin[0] + extent[0] <= 1.01 && origin[1] + extent[1] <= 1.01
    }
}

enum VideoCleanup {
    static let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("VideoCleanup", isDirectory: true)
    static let maskFile = root.appendingPathComponent("watermark.json")
    static func store(_ mask: WatermarkMask) throws {
        guard mask.valid else { throw SubtitleFiles.failure("워터마크 마스크 형식이 올바르지 않습니다.") }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try JSONEncoder().encode(mask).write(to: maskFile, options: .atomic)
    }
    static func paths(_ settings: VideoCleanupSettings?) throws -> [String] {
        guard let settings, settings.watermark || settings.subtitles else { return [] }
        var script = "", mask: WatermarkMask?
        if settings.watermark, settings.maskRevision != nil {
            mask = try JSONDecoder().decode(WatermarkMask.self, from: Data(contentsOf: maskFile))
            guard mask?.valid == true else { throw SubtitleFiles.failure("워터마크 마스크 오류") }
        }
        if let mask {
            script += "//!TEXTURE wm\n//!SIZE \(mask.width) \(mask.height)\n//!FORMAT rgba8\n//!FILTER LINEAR\n" + mask.rgba.map { String(format: "%02x", $0) }.joined() + "\n"
        }
        let water = settings.watermark ? Array(settings.watermarkRegions.filter(\.valid).prefix(4)) : []
        let subs = settings.subtitles ? Array(settings.subtitleRegions.filter(\.valid).prefix(4)) : []
        guard mask != nil || !water.isEmpty || !subs.isEmpty else { return [] }
        script += "//!HOOK MAIN\n//!BIND HOOKED\n" + (mask == nil ? "" : "//!BIND wm\n") + "//!DESC Lilac video cleanup\n"
        script += """
        bool inside(vec2 p,vec4 r){return p.x>=r.x&&p.y>=r.y&&p.x<=r.x+r.z&&p.y<=r.y+r.w;}
        float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}
        vec3 fillRegion(vec2 p,vec4 r){
            vec2 px=2.0/HOOKED_size; float l=r.x-px.x,rr=r.x+r.z+px.x,t=r.y-px.y,b=r.y+r.w+px.y;
            vec4 weights=1.0/max(vec4(p.x-l,rr-p.x,p.y-t,b-p.y),vec4(0.001));
            return (HOOKED_tex(vec2(l,p.y)).rgb*weights.x+HOOKED_tex(vec2(rr,p.y)).rgb*weights.y+
                    HOOKED_tex(vec2(p.x,t)).rgb*weights.z+HOOKED_tex(vec2(p.x,b)).rgb*weights.w)/dot(weights,vec4(1.0));
        }
        vec4 hook(){vec2 p=HOOKED_pos;vec4 c=HOOKED_tex(p);
        """
        if let mask {
            script += "vec2 origin=vec2(\(mask.origin[0]),\(mask.origin[1])),extent=vec2(\(mask.extent[0]),\(mask.extent[1]));"
            script += "if(inside(p,vec4(origin,extent))){float a=min(wm_tex((p-origin)/extent).r,0.97);c.rgb=clamp((c.rgb-vec3(a))/(1.0-a),0.0,1.0);}\n"
        } else {
            for r in water { script += "if(inside(p,vec4(\(r.x),\(r.y),\(r.width),\(r.height))))c.rgb=fillRegion(p,vec4(\(r.x),\(r.y),\(r.width),\(r.height)));\n" }
        }
        script += "return c;}\n"
        if !subs.isEmpty {
            guard let url = Bundle.main.url(forResource: "subtitle-cleanup", withExtension: "glsl") else { throw SubtitleFiles.failure("영상 자막 정리 셰이더가 없습니다.") }
            let rects = subs.map { "vec4(\($0.x),\($0.y),\($0.width),\($0.height))" }
            let constants = "const int uRectCount=\(rects.count); const vec4 uRects[4]=vec4[4](" + (rects + Array(repeating: "vec4(0.0)", count: 4 - rects.count)).joined(separator: ",") + ");\n"
            script += try String(contentsOf: url).replacingOccurrences(of: "// REGION_CONSTANTS", with: constants)
        }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let file = root.appendingPathComponent(SubtitleFiles.key(script) + ".glsl")
        if !FileManager.default.fileExists(atPath: file.path) { try Data(script.utf8).write(to: file, options: .atomic) }
        return [file.path]
    }
}

/// The desktop learner is pure JavaScript. Use exactly that scene/black-level estimator, off the UI thread.
actor WatermarkLearning {
    private let context: JSContext
    init() {
        context = JSContext()!
        if let url = Bundle.main.url(forResource: "watermark-learner", withExtension: "js"), let source = try? String(contentsOf: url) { context.evaluateScript(source) }
        context.evaluateScript("var learner = new LilacWatermark.WatermarkLearner();")
    }
    func add(_ file: URL) throws -> Int {
        guard let image = UIImage(contentsOfFile: file.path)?.cgImage else { throw SubtitleFiles.failure("영상 프레임을 읽지 못했습니다.") }
        let width = min(640, image.width), height = max(1, image.height * width / image.width)
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = rgba.withUnsafeMutableBytes { bytes -> Bool in
            guard let canvas = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                         space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            canvas.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height)); return true
        }
        guard drawn else { throw SubtitleFiles.failure("영상 프레임 변환 실패") }
        context.setObject(rgba, forKeyedSubscript: "pixels" as NSString)
        context.evaluateScript("learner.addFrame(new Uint8Array(pixels),\(width),\(height));pixels=null;")
        return Int(context.evaluateScript("learner.acceptedFrames")?.toInt32() ?? 0)
    }
    func finish() throws -> WatermarkMask {
        let json = context.evaluateScript("var result=learner.finish(); result ? JSON.stringify({...result,rgba:Array.from(result.rgba)}) : '';")?.toString() ?? ""
        guard !json.isEmpty else { throw SubtitleFiles.failure(context.evaluateScript("learner.failureReason")?.toString() ?? "충분한 장면을 모으지 못했습니다.") }
        return try JSONDecoder().decode(WatermarkMask.self, from: Data(json.utf8))
    }
    func parse(_ text: String) throws -> WatermarkMask {
        context.exception = nil
        context.setObject(text, forKeyedSubscript: "maskSource" as NSString)
        let json = context.evaluateScript("var parsed=LilacWatermark.parseMaskShader(maskSource);JSON.stringify({...parsed,rgba:Array.from(parsed.rgba)});")?.toString() ?? ""
        guard context.exception == nil, let data = json.data(using: .utf8), let mask = try? JSONDecoder().decode(WatermarkMask.self, from: data), mask.valid else { throw SubtitleFiles.failure("워터마크 마스크 파일 형식이 올바르지 않습니다.") }
        return mask
    }
}

struct VideoCleanupControls: View {
    @Binding var settings: VideoCleanupSettings?
    let engine: MPVEngine
    @State private var editor = false
    @State private var importMask = false
    @State private var learning: Task<Void, Never>?
    @State private var message = ""
    private var value: Binding<VideoCleanupSettings> { Binding(get: { settings ?? VideoCleanupSettings() }, set: { settings = $0 }) }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle("워터마크 지우기", isOn: value.watermark)
            Toggle("영상에 박힌 자막 지우기", isOn: value.subtitles)
            HStack {
                Button("영역 지정") { editor = true }
                Button(learning == nil ? "로고 학습" : "학습 중단") { if let learning { learning.cancel(); self.learning = nil } else { learn() } }
                Button("마스크 가져오기") { importMask = true }
            }
            if value.wrappedValue.maskRevision != nil { Button("학습한 마스크 삭제") { settings?.maskRevision = nil } }
            Text(message.isEmpty ? "선택한 영역만 처리합니다. 로고 학습은 재생 중 여러 장면을 모으며, 고정된 반투명 흰색 로고에 적용됩니다." : message).font(.caption).foregroundStyle(.secondary)
        }
        .sheet(isPresented: $editor) { CleanupRegionEditor(settings: value, engine: engine) }
        .fileImporter(isPresented: $importMask, allowedContentTypes: [.plainText, .data]) { result in
            guard case .success(let url) = result else { return }
            Task {
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                do {
                    guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 6_000_000 else { throw SubtitleFiles.failure("마스크 파일이 너무 큽니다.") }
                    let mask = try await WatermarkLearning().parse(String(contentsOf: url))
                    try VideoCleanup.store(mask); var next = value.wrappedValue; next.maskRevision = UUID().uuidString; next.watermark = true; settings = next
                    message = "워터마크 마스크를 적용했습니다."
                } catch { message = error.localizedDescription }
            }
        }
        .onDisappear { learning?.cancel(); learning = nil }
    }
    private func learn() {
        learning = Task {
            let learner = WatermarkLearning()
            let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
            defer { try? FileManager.default.removeItem(at: file); learning = nil }
            do {
                for _ in 0..<180 {
                    try Task.checkCancellation()
                    if !engine.paused {
                        try await engine.screenshot(to: file)
                        let count = try await learner.add(file); message = "장면 \(count)개 학습 중 · 여러 밝기와 배경이 필요합니다"
                    }
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                }
                let mask = try await learner.finish(); try Task.checkCancellation(); try VideoCleanup.store(mask)
                var next = value.wrappedValue; next.maskRevision = UUID().uuidString; next.watermark = true; settings = next; message = "로고 학습 완료"
            } catch is CancellationError { message = "로고 학습을 중단했습니다." }
            catch { message = error.localizedDescription }
        }
    }
}

private struct CleanupRegionEditor: View {
    @Binding var settings: VideoCleanupSettings
    let engine: MPVEngine
    @Environment(\.dismiss) private var dismiss
    @State private var subtitle = false
    @State private var image: UIImage?
    private var regions: Binding<[CleanupRegion]> { subtitle ? $settings.subtitleRegions : $settings.watermarkRegions }
    var body: some View {
        NavigationStack {
            Form {
                Picker("종류", selection: $subtitle) { Text("워터마크").tag(false); Text("내장 자막").tag(true) }.pickerStyle(.segmented)
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: .fit).overlay {
                        GeometryReader { geometry in
                            ForEach(regions.wrappedValue) { r in
                                Rectangle().stroke(.purple, lineWidth: 2).frame(width: geometry.size.width * r.width, height: geometry.size.height * r.height)
                                    .position(x: geometry.size.width * (r.x + r.width / 2), y: geometry.size.height * (r.y + r.height / 2))
                            }
                        }
                    }
                }
                ForEach(regions) { region in
                    VStack {
                        Text("왼쪽 \(Int(region.wrappedValue.x * 100))% · 위 \(Int(region.wrappedValue.y * 100))%")
                        Slider(value: region.x, in: 0...max(0.001, 1 - region.wrappedValue.width)).accessibilityLabel("영역 왼쪽")
                        Slider(value: region.y, in: 0...max(0.001, 1 - region.wrappedValue.height)).accessibilityLabel("영역 위쪽")
                        Text("너비 \(Int(region.wrappedValue.width * 100))% · 높이 \(Int(region.wrappedValue.height * 100))%")
                        Slider(value: region.width, in: 0.01...max(0.01, 1 - region.wrappedValue.x)).accessibilityLabel("영역 너비")
                        Slider(value: region.height, in: 0.01...max(0.01, 1 - region.wrappedValue.y)).accessibilityLabel("영역 높이")
                        Button("영역 삭제", role: .destructive) { regions.wrappedValue.removeAll { $0.id == region.wrappedValue.id } }
                    }
                }
                Button("영역 추가") { regions.wrappedValue.append(subtitle ? CleanupRegion(x: 0.1, y: 0.75, width: 0.8, height: 0.2) : CleanupRegion()) }.disabled(regions.wrappedValue.count >= 4)
            }
            .navigationTitle("영상 정리 영역").toolbar { Button("완료") { dismiss() } }
            .task {
                let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
                defer { try? FileManager.default.removeItem(at: file) }
                try? await engine.screenshot(to: file); image = UIImage(contentsOfFile: file.path)
            }
        }
    }
}
