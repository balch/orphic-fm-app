import XCTest
@testable import DjWidgetExtension

/// Decodes the same checked-in fixture the Kotlin `DjWidgetGoldenFixtureTest`
/// verifies against `DjWidgetWire.encode`. If a field is renamed or retyped
/// on either side, one of these two tests goes red instead of the widget
/// silently going blank on a user's phone.
final class DjWidgetWireDecodeTest: XCTestCase {
    func testDecodesGoldenFixture() throws {
        let url = Bundle(for: type(of: self))
            .url(forResource: "snapshot-golden", withExtension: "json")
        let data = try Data(contentsOf: XCTUnwrap(url))
        let wire = try JSONDecoder().decode(DjWidgetWirePayload.self, from: data)

        XCTAssertEqual(wire.currentVibe, "Rust Belt")
        XCTAssertEqual(wire.albumTitle, "Zero to One")
        XCTAssertTrue(wire.isPlaying)
        XCTAssertTrue(wire.timerRunning)
        XCTAssertEqual(wire.timerRemainingSeconds, 1421)
        XCTAssertEqual(wire.timerStatus, "RUNNING")
        XCTAssertEqual(wire.artworkFile, "artwork-rust-belt.png")
        XCTAssertEqual(wire.writtenAtEpochMs, 1_700_000_000_000)
    }
}
