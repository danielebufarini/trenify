import XCTest
@preconcurrency import SharedApp

@MainActor
final class NativeMonitoringInteropTests: XCTestCase {
    private func collect<T: AnyObject>(_ states: SkieSwiftStateFlow<T>, matching: @escaping (T) -> Bool, action: () -> Void) async {
        let ready = expectation(description: "typed SKIE projection delivery")
        let observer = Task { @MainActor in
            for await value in states { if matching(value) { ready.fulfill(); return } }
        }
        action(); await fulfillment(of: [ready], timeout: 5)
        observer.cancel(); await observer.value
    }
    func testMonitoringFacadeSectionsCapabilitiesAndActionsThroughSKIE() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: 12, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        fixture.stageEndedMonitor(number: "456", status: .arrived)
        fixture.sharedSelect(area: .monitoring)
        let monitoring = fixture.session.shell.state.value.active.monitoring!
        await collect(monitoring.state, matching: { $0.active.count == 1 && $0.ended.count == 1 }) {}
        let active = monitoring.state.value.active.first!
        let ended = monitoring.state.value.ended.first!
        XCTAssertNotEqual(active.trainRunKey, ended.trainRunKey)
        XCTAssertEqual(active.identity.number, "123")
        XCTAssertEqual(active.status, .running)
        XCTAssertEqual(active.observation.provenance.providerName, "ViaggiaTreno")
        XCTAssertTrue(active.canStop)
        XCTAssertTrue(active.canToggleNotifications)
        XCTAssertFalse(active.canRemove)
        XCTAssertEqual(ended.status, .arrived)
        XCTAssertTrue(ended.canRemove)
        XCTAssertFalse(ended.canStop)
        XCTAssertFalse(ended.canToggleNotifications)
        // Ended stop/toggle are gated: no reactivation through the facade.
        monitoring.stop(trainRunKey: ended.trainRunKey)
        monitoring.setMonitorNotifications(trainRunKey: ended.trainRunKey, enabled: false)
        XCTAssertEqual(monitoring.state.value.ended.count, 1)
        await collect(monitoring.state, matching: { $0.active.first?.notificationsEnabled == false }) {
            monitoring.setMonitorNotifications(trainRunKey: active.trainRunKey, enabled: false)
        }
        await collect(monitoring.state, matching: { $0.active.isEmpty }) {
            monitoring.stop(trainRunKey: active.trainRunKey)
        }
        monitoring.open(trainRunKey: ended.trainRunKey)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainDetail)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .monitoring)
        await collect(monitoring.state, matching: { $0.ended.isEmpty }) {
            monitoring.removeEnded(trainRunKey: ended.trainRunKey)
        }
        XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
    }
    func testMonitoringTerminalTransitionRetainsFinalSnapshotThroughSKIE() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        fixture.sharedSelect(area: .monitoring)
        let monitoring = fixture.session.shell.state.value.active.monitoring!
        await collect(monitoring.state, matching: { $0.active.count == 1 }) {}
        await collect(monitoring.state, matching: { $0.active.isEmpty && $0.ended.count == 1 }) {
            fixture.completeActiveMonitorTerminally(number: "123", status: .arrived)
        }
        let ended = monitoring.state.value.ended.first!
        XCTAssertEqual(ended.status, .arrived)
        XCTAssertTrue(ended.hasSnapshot)
        XCTAssertTrue(ended.canRemove)
        XCTAssertFalse(ended.canStop)
        XCTAssertEqual(fixture.networkRequests, 0)
    }
}
