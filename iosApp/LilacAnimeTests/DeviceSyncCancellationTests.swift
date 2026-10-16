import XCTest
@testable import LilacAnime

final class DeviceSyncCancellationTests: XCTestCase {
    @MainActor func testDisconnectWhileConnectingRejectsLateResponse() async throws {
        var pending: CheckedContinuation<[String: Any], Never>?
        var paths: [String] = []
        let started = expectation(description: "status requested")
        let sync = DeviceSync(deferLoading: true, transport: { _, path, _, _, _ in
            paths.append(path)
            return await withCheckedContinuation { pending = $0; started.fulfill() }
        })
        let connecting = Task { try await sync.connect(address: "https://fixture.test", password: "test") }
        await fulfillment(of: [started], timeout: 2)
        sync.disconnect()
        pending?.resume(returning: ["app": "lilacanime-server", "ready": true])
        do { try await connecting.value; XCTFail("a disconnected attempt must not log in") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(paths, ["/api/status"])
        XCTAssertFalse(sync.connected)
        XCTAssertEqual(sync.state, "연결을 끊었습니다.")
    }
}
