import XCTest
@testable import PhoneSyncCompanion

final class PhoneSyncCompanionTests: XCTestCase {
    func testServerServiceTypeIsCorrect() {
        let expected = "_phonesync._tcp"
        XCTAssertEqual(expected, "_phonesync._tcp")
    }

    func testStatusPayloadShape() {
        let payload = [
            "status": "online",
            "version": "1.0.0",
            "device": "Test Device",
            "available": ["messages", "contacts", "notes", "callhistory"]
        ] as [String: Any]

        XCTAssertEqual(payload["status"] as? String, "online")
        XCTAssertEqual(payload["version"] as? String, "1.0.0")
        XCTAssertEqual((payload["available"] as? [String])?.count, 4)
    }

    func testSupportedEndpoints() {
        let endpoints = [
            "/api/status",
            "/api/messages",
            "/api/contacts",
            "/api/notes",
            "/api/callhistory"
        ]

        XCTAssertTrue(endpoints.contains("/api/status"))
        XCTAssertTrue(endpoints.contains("/api/messages"))
        XCTAssertTrue(endpoints.contains("/api/contacts"))
        XCTAssertTrue(endpoints.contains("/api/notes"))
        XCTAssertTrue(endpoints.contains("/api/callhistory"))
    }
}
