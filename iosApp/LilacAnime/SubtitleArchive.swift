import Foundation
import Darwin
import libarchive
import LilacLocalAI

enum SubtitleArchive {
    static func extract(_ file: URL, into folder: URL) throws -> [URL] {
        // libarchive converts 7z UTF-16 and legacy ZIP names through the calling thread's locale.
        // Keep this synchronous scope local to this thread; never mutate the process-wide locale.
        guard let locale = lilac_utf8_locale_begin() else { throw SubtitleFiles.failure("압축 파일의 UTF-8 문자 환경을 준비하지 못했습니다.") }
        defer { lilac_utf8_locale_end(locale) }
        guard let reader = archive_read_new() else { throw SubtitleFiles.failure("압축 자막을 열 수 없습니다.") }
        defer { archive_read_free(reader) }
        archive_read_support_filter_all(reader)
        archive_read_support_format_all(reader)
        // Older Korean ZIP archives often omit the UTF-8 filename flag.
        archive_read_set_options(reader, "zip:hdrcharset=CP949")
        guard archive_read_open_filename(reader, file.path, 65536) == ARCHIVE_OK else { throw failure(reader) }
        var entry: OpaquePointer?
        var files: [URL] = []
        var total = 0
        var count = 0
        while true {
            let result = archive_read_next_header(reader, &entry)
            if result == ARCHIVE_EOF { break }
            guard result >= ARCHIVE_WARN, let entry else { throw failure(reader) }
            guard archive_entry_filetype(entry) == UInt32(S_IFREG) else { archive_read_data_skip(reader); continue }
            // iOS can retain the C locale; requesting locale bytes loses Unicode 7z/RAR names.
            guard let path = archive_entry_pathname_utf8(entry) ?? archive_entry_pathname(entry) else { throw SubtitleFiles.failure("압축 파일 이름을 읽지 못했습니다.") }
            let name = String(cString: path)
            let ext = URL(fileURLWithPath: name).pathExtension.lowercased()
            guard ["ass","ssa","srt","vtt","smi","sbv","sub","mpl2","ttml","xml","ttf","otf","ttc"].contains(ext) else { archive_read_data_skip(reader); continue }
            count += 1
            guard count <= 250, archive_entry_size(entry) <= 50_000_000 else { throw SubtitleFiles.failure("압축된 자막 파일이 너무 큽니다.") }
            var bytes = Data()
            var buffer = [UInt8](repeating: 0, count: 65536)
            while true {
                let amount = archive_read_data(reader, &buffer, buffer.count)
                if amount == 0 { break }
                guard amount > 0 else { throw failure(reader) }
                total += amount
                guard bytes.count + amount <= 50_000_000, total <= 100_000_000 else { throw SubtitleFiles.failure("압축된 자막 파일이 너무 큽니다.") }
                bytes.append(contentsOf: buffer.prefix(amount))
            }
            let target = folder.appendingPathComponent(String(SubtitleFiles.key(name).prefix(12)) + "_" + URL(fileURLWithPath: name).lastPathComponent)
            try bytes.write(to: target, options: .atomic)
            files.append(target)
        }
        guard !files.isEmpty else { throw SubtitleFiles.failure("압축 파일에 지원하는 자막이 없습니다.") }
        return files
    }
    private static func failure(_ archive: OpaquePointer) -> NSError {
        let detail = archive_error_string(archive).map { String(cString: $0) } ?? "압축 형식을 읽지 못했습니다."
        return SubtitleFiles.failure(detail)
    }
}
