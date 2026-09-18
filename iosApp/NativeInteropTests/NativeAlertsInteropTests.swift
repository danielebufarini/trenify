import XCTest
@preconcurrency import SharedApp

@MainActor
final class NativeAlertsInteropTests: XCTestCase {
    private func collect<T: AnyObject>(_ states: SkieSwiftStateFlow<T>, matching: @escaping (T) -> Bool, action: () -> Void) async {
        let ready = expectation(description: "typed SKIE projection delivery")
        let observer = Task { @MainActor in
            for await value in states { if matching(value) { ready.fulfill(); return } }
        }
        action(); await fulfillment(of: [ready], timeout: 5)
        observer.cancel(); await observer.value
    }

    func testAlertsFacadeIdentityAndActionsThroughSKIE() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.sharedSelect(area: .alerts)
        let alerts = fixture.session.shell.state.value.active.alerts!
        // Settle init before staging so the update propagates (never dropped).
        await collect(alerts.state, matching: { _ in true }) {}
        await collect(alerts.state, matching: { $0.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        let state = alerts.state.value
        let row = state.strikes.first!
        XCTAssertEqual(row.strikeId, "alert-1")
        XCTAssertEqual(row.status, .scheduled)
        XCTAssertTrue(row.railwayRelevant)
        XCTAssertEqual(row.relevance, .regional)
        XCTAssertEqual(row.operators, ["Trenitalia"])
        XCTAssertTrue(row.canOpenReference)
        XCTAssertNotNil(alerts.detail(strikeId: "alert-1"))
        XCTAssertNil(alerts.detail(strikeId: "no-such-strike"))
        // Overview -> detail uses the shared component action.
        alerts.open(strikeId: "alert-1")
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .strikeDetail)
        XCTAssertEqual(fixture.session.shell.state.value.active.alertStrikeId, "alert-1")
        // Unknown open is a no-op: no duplicate route.
        let size = fixture.session.shell.state.value.path.count
        alerts.open(strikeId: "no-such-strike")
        XCTAssertEqual(fixture.session.shell.state.value.path.count, size)
        // Back uses shared Decompose navigation.
        alerts.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .alertsOverview)
        // Notification toggle delegates to the shared action. The fixture
        // denies OS permission, so the denial stays truthful (the enabled
        // path is proven in shared unit tests with a granting permission).
        XCTAssertFalse(alerts.state.value.notificationsEnabled)
        alerts.toggleNotifications()
        await collect(alerts.state, matching: { $0.notificationPermissionDenied }) {}
        XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testAlertsReferenceContractThroughSKIE() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.sharedSelect(area: .alerts)
        let alerts = fixture.session.shell.state.value.active.alerts!
        await collect(alerts.state, matching: { _ in true }) {}
        await collect(alerts.state, matching: { $0.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        // Dedicated policy: official MIT reference is launchable, booking hosts are not.
        XCTAssertTrue(alerts.canOpenReference(url: "https://scioperi.mit.gov.it/alert-1"))
        XCTAssertFalse(alerts.canOpenReference(url: "https://www.trenitalia.com"))
        XCTAssertFalse(alerts.canOpenReference(url: nil))
        XCTAssertEqual(fixture.networkRequests, 0)
    }
}
