import XCTest
@testable import LilacAnime

final class StaggeredResolverTests: XCTestCase {
    func testSlowPreferredServerDoesNotHoldTheNextOneUntilTimeout() async throws {
        let result = try await StaggeredResolver.first([1, 2], delay: 20_000_000) { value in
            if value == 1 { try await Task.sleep(nanoseconds: 5_000_000_000) }
            return value
        }
        XCTAssertEqual(result, 2)
    }
    func testImmediateFailureTriesNextWithoutWaitingForStagger() async throws {
        let started = Date()
        let result = try await StaggeredResolver.first([1, 2], delay: 5_000_000_000) { value in
            if value == 1 { throw SubtitleFiles.failure("failed") }
            return value
        }
        XCTAssertEqual(result, 2)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2)
    }
    func testFastPreferredServerWinsAndAllFailuresAreReported() async throws {
        let result = try await StaggeredResolver.first([1, 2]) { $0 }
        XCTAssertEqual(result, 1)
        do { let _: Int = try await StaggeredResolver.first([1, 2], delay: 10_000_000) { _ in throw SubtitleFiles.failure("all failed") }; XCTFail("Expected failure") }
        catch { XCTAssertEqual(error.localizedDescription, "all failed") }
    }
}
