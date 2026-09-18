import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

@MainActor
final class NativeMonitoringShellTests: XCTestCase {
    private func delivery(_ model: NativeMonitoringModel, matching: @escaping () -> Bool, action: () -> Void = {}) async {
        let ready = expectation(description: "screen-level SKIE acknowledgement")
        ready.assertForOverFulfill = false
        model.onUpdate = { if matching() { ready.fulfill() } }
        action()
        if matching() { ready.fulfill() }
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
    }
    private func routeModel(_ fixture: NativeInteropFixture) -> NativeMonitoringModel {
        let model = NativeMonitoringModel(session: fixture.session, identity: fixture.session.shell.state.value.active.identity)
        model.start(); model.start()
        return model
    }
    private func monitoring(_ fixture: NativeInteropFixture) -> NativeMonitoringModel {
        fixture.sharedSelect(area: .monitoring)
        return routeModel(fixture)
    }
    private func stop(_ model: NativeMonitoringModel) async {
        for task in model.close() { await task.value }
    }
    private func zeroCollectors(_ fixture: NativeInteropFixture) async {
        let ready = expectation(description: "Kotlin finally acknowledges zero collectors")
        let task = Task { @MainActor in
            for await count in fixture.collectorCounts {
                if count.int32Value == 0 { ready.fulfill(); return }
            }
        }
        await fulfillment(of: [ready], timeout: 5)
        task.cancel(); await task.value
    }

    func testEmptyMonitoringProjectsEmptySections() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring != nil })
        XCTAssertEqual(model.monitoring!.active.count, 0)
        XCTAssertEqual(model.monitoring!.ended.count, 0)
        XCTAssertFalse(model.monitoring!.loading)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .monitoring)
        await stop(model); await zeroCollectors(fixture)
    }

    func testActiveAndEndedSectionsCapabilitiesAndStableIdentity() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: 12, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        fixture.stageEndedMonitor(number: "456", status: .arrived)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.count == 1 && model.monitoring?.ended.count == 1 })
        let active = model.monitoring!.active.first!
        let ended = model.monitoring!.ended.first!
        XCTAssertNotEqual(active.trainRunKey, ended.trainRunKey)
        XCTAssertEqual(active.identity.number, "123")
        XCTAssertEqual(active.status, .running)
        XCTAssertEqual(active.delayMinutes?.int32Value, 12)
        XCTAssertFalse(active.ended)
        XCTAssertTrue(active.canStop)
        XCTAssertTrue(active.canToggleNotifications)
        XCTAssertFalse(active.canRemove)
        XCTAssertTrue(active.canOpen)
        XCTAssertEqual(ended.identity.number, "456")
        XCTAssertEqual(ended.status, .arrived)
        XCTAssertTrue(ended.ended)
        XCTAssertNotNil(ended.endedAtEpochSeconds)
        XCTAssertFalse(ended.canStop)
        XCTAssertFalse(ended.canToggleNotifications)
        XCTAssertTrue(ended.canRemove)
        // Genuine provider provenance on the active card; never operator-inferred.
        XCTAssertEqual(active.observation.provenance.providerName, "ViaggiaTreno")
        XCTAssertNotNil(active.observation.provenance.fetchedAtEpochSeconds)
        XCTAssertNotNil(active.observation.provenance.sourceTimestampEpochSeconds)
        XCTAssertEqual(active.observation.freshness, .fresh)
        XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
        await stop(model); await zeroCollectors(fixture)
    }

    func testTerminalTransitionMovesActiveToRecentlyEnded() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.count == 1 })
        fixture.completeActiveMonitorTerminally(number: "123", status: .arrived)
        await delivery(model, matching: { model.monitoring?.active.isEmpty == true && model.monitoring?.ended.count == 1 })
        let ended = model.monitoring!.ended.first!
        XCTAssertEqual(ended.identity.number, "123")
        XCTAssertEqual(ended.status, .arrived)
        XCTAssertTrue(ended.hasSnapshot)
        XCTAssertFalse(ended.canStop)
        XCTAssertTrue(ended.canRemove)
        await stop(model); await zeroCollectors(fixture)
    }

    func testFinalCancelledEndedRetainsCancellation() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageEndedMonitor(number: "123", status: .cancelled)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.ended.count == 1 })
        let ended = model.monitoring!.ended.first!
        XCTAssertEqual(ended.status, .cancelled)
        XCTAssertTrue(ended.ended)
        XCTAssertTrue(ended.canRemove)
        XCTAssertFalse(ended.canStop)
        XCTAssertFalse(ended.canToggleNotifications)
        XCTAssertTrue(model.monitoring!.active.isEmpty)
        await stop(model); await zeroCollectors(fixture)
    }

    func testRefreshFailureRetainsDegradedContent() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageDegradedMonitor(number: "123")
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.first?.observation.provenance.degraded == true })
        let card = model.monitoring!.active.first!
        XCTAssertTrue(card.hasSnapshot)
        XCTAssertEqual(card.refreshFailure, .offline)
        XCTAssertEqual(card.observation.freshness, .stale)
        XCTAssertTrue(card.observation.provenance.degraded)
        await stop(model); await zeroCollectors(fixture)
    }

    func testUnknownProvenanceCarriesNoTimestamps() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: true, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.count == 1 })
        let card = model.monitoring!.active.first!
        XCTAssertEqual(card.observation.freshness, .unknown)
        XCTAssertNil(card.observation.provenance.fetchedAtEpochSeconds)
        XCTAssertNil(card.observation.provenance.sourceTimestampEpochSeconds)
        XCTAssertTrue(card.hasSnapshot)
        await stop(model); await zeroCollectors(fixture)
    }

    func testStopRemoveAndNotificationActionsForward() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        fixture.stageEndedMonitor(number: "456", status: .arrived)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.count == 1 && model.monitoring?.ended.count == 1 })
        let activeKey = model.monitoring!.active.first!.trainRunKey
        let endedKey = model.monitoring!.ended.first!.trainRunKey
        // Ended stop/toggle/remove-wrong-path are gated: no reactivation, no deletion.
        model.stopMonitor(endedKey)
        model.setNotifications(endedKey, enabled: false)
        model.removeEnded(activeKey)
        XCTAssertEqual(model.monitoring!.active.count, 1)
        XCTAssertEqual(model.monitoring!.ended.count, 1)
        await delivery(model, matching: { model.monitoring?.active.first?.notificationsEnabled == false }) {
            model.setNotifications(activeKey, enabled: false)
        }
        XCTAssertEqual(model.monitoring!.active.first!.notificationsEnabled, false)
        await delivery(model, matching: { model.monitoring?.active.isEmpty == true }) { model.stopMonitor(activeKey) }
        await delivery(model, matching: { model.monitoring?.ended.isEmpty == true }) { model.removeEnded(endedKey) }
        await stop(model); await zeroCollectors(fixture)
    }

    func testRetryForwardingWithoutFailureIsHarmless() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.count == 1 })
        model.retryNotifications()
        XCTAssertEqual(model.monitoring!.active.count, 1)
        await stop(model); await zeroCollectors(fixture)
    }

    func testOpenNavigatesToNativeTrainDetailAndBack() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.count == 1 })
        model.openMonitor(model.monitoring!.active.first!.trainRunKey)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainDetail)
        XCTAssertNotNil(fixture.session.shell.state.value.active.trainDetail)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .monitoring)
        await stop(model); await zeroCollectors(fixture)
    }

    func testRestoredMonitoringBindsNativeFacade() async {
        let fixture = NativeInteropFixture(restoredStationTrainDestination: .monitoring)
        defer { fixture.close() }
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .monitoring)
        let model = routeModel(fixture)
        await delivery(model, matching: { model.monitoring != nil })
        await stop(model); await zeroCollectors(fixture)
    }

    func testObserverDeallocationCancelsBothTasksAndNoRowCollectors() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        var model: NativeMonitoringModel? = monitoring(fixture)
        weak var weakModel = model
        let ready = expectation(description: "two screen observers only")
        let counts = Task { @MainActor in
            for await count in fixture.collectorCounts { if count.int32Value == 2 { ready.fulfill(); return } }
        }
        await fulfillment(of: [ready], timeout: 5); counts.cancel(); await counts.value
        model = nil; XCTAssertNil(weakModel)
        await zeroCollectors(fixture)
    }

    func testNoDuplicateSessionRootOrSecondRouter() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        var constructions = 0
        let shell = NativeShellModel { constructions += 1; return fixture.session }
        shell.start(); shell.start()
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring != nil })
        XCTAssertEqual(constructions, 1); XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
        await stop(model); shell.close(); await zeroCollectors(fixture)
    }

    /// Capture harness chrome contract: the root navigation title must stay the
/// production `shell.monitoring` title. "Recently ended" is a section header
/// inside the content, never the whole-screen title.
private let monitoringProductionTitleKey = "shell.monitoring"
private let monitoringProductionTitleTable = "Shell"

/// Production resolves this key from the Shell table in the app bundle; the
/// harness resolves the same key/table from the test bundle (which carries
/// the same Shell strings) so captures show the produced title, never a key.
private func monitoringProductionTitle() -> String {
    NSLocalizedString(monitoringProductionTitleKey, tableName: monitoringProductionTitleTable,
        bundle: Bundle(for: NativeMonitoringModel.self), comment: "")
}

private func show(_ model: NativeMonitoringModel, name: String, dark: Bool = false, large: Bool = false, offset: CGFloat = 0) async {
        let ready = expectation(description: "native rendering layout acknowledgement")
        ready.assertForOverFulfill = false
        let window = UIWindow(windowScene: UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!)
        window.frame = CGRect(x: 0, y: 0, width: large ? 320 : 390, height: 844)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let content = NativeMonitoringView(model: model)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
        window.rootViewController = UIHostingController(rootView: NavigationStack {
            content.navigationTitle(monitoringProductionTitle())
                .navigationBarTitleDisplayMode(.inline)
        }.environment(\.colorScheme, dark ? .dark : .light).preferredColorScheme(dark ? .dark : .light).onAppear { ready.fulfill() })
        window.makeKeyAndVisible(); await fulfillment(of: [ready], timeout: 5); window.layoutIfNeeded()
        let rendered = expectation(description: "native transaction committed")
        CATransaction.begin(); CATransaction.setCompletionBlock { rendered.fulfill() }
        window.layoutIfNeeded(); CATransaction.commit()
        await fulfillment(of: [rendered], timeout: 5)
        if offset > 0, let scroll = scrollView(in: window) {
            scroll.setContentOffset(CGPoint(x: 0, y: offset), animated: false)
            window.layoutIfNeeded()
        }
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        window.isHidden = true; window.rootViewController = nil
    }

    private func scrollView(in view: UIView) -> UIScrollView? {
        if let scroll = view as? UIScrollView { return scroll }
        return view.subviews.lazy.compactMap { self.scrollView(in: $0) }.first
    }

    func testCardSemanticParityActiveAndEnded() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: false, category: .fr, scheduledPlatform: "1", actualPlatform: "2")
        fixture.stageEndedMonitor(number: "456", status: .arrived)
        let model = monitoring(fixture)
        await delivery(model, matching: { model.monitoring?.active.count == 1 && model.monitoring?.ended.count == 1 })
        // The facade carries the full operational snapshot for the rich active
        // card: identity, category, service date, scheduled times, platforms.
        let active = model.monitoring!.active.first!
        XCTAssertEqual(active.identity.number, "123")
        XCTAssertEqual(active.category, .fr)
        XCTAssertEqual("\(active.identity.serviceDate)", "2026-09-14")
        XCTAssertNotNil(active.scheduledDepartureEpochSeconds)
        XCTAssertNotNil(active.scheduledArrivalEpochSeconds)
        XCTAssertEqual(active.scheduledPlatform, "1")
        XCTAssertEqual(active.actualPlatform, "2")
        // The minimal ended snapshot exposes identity and service date only;
        // the view's conditional rows therefore cannot invent times/platforms.
        let ended = model.monitoring!.ended.first!
        XCTAssertTrue(ended.ended)
        XCTAssertEqual(ended.identity.number, "456")
        XCTAssertEqual("\(ended.identity.serviceDate)", "2026-09-14")
        XCTAssertNil(ended.category)
        XCTAssertNil(ended.scheduledDepartureEpochSeconds)
        XCTAssertNil(ended.scheduledArrivalEpochSeconds)
        XCTAssertNil(ended.scheduledPlatform)
        XCTAssertNil(ended.actualPlatform)
        // Pixel proof of both branches is in the refreshed review captures;
        // Android asserts the same conditional rows as rendered nodes.
        await stop(model); await zeroCollectors(fixture)
    }

    func testCaptureHarnessUsesProductionRootTitle() {
        XCTAssertEqual(monitoringProductionTitleKey, "shell.monitoring")
        XCTAssertEqual(monitoringProductionTitleTable, "Shell")
        // The harness renders this exact resolved title; a raw key here means drift.
        XCTAssertEqual(monitoringProductionTitle(), "Monitoring")
    }

    func testDeterministicNativeReviewCaptures() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let empty = monitoring(fixture)
        await delivery(empty, matching: { empty.monitoring != nil })
        await show(empty, name: "ios-monitoring-empty")
        await stop(empty)
        fixture.stageActiveMonitor(number: "123", delayMinutes: nil, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        let active = monitoring(fixture)
        await delivery(active, matching: { active.monitoring?.active.count == 1 })
        await show(active, name: "ios-monitoring-active")
        await delivery(active, matching: { active.monitoring?.active.first?.delayMinutes?.int32Value == 12 }) {
            fixture.stageActiveMonitor(number: "123", delayMinutes: 12, stale: false, unknown: false, category: nil, scheduledPlatform: nil, actualPlatform: nil)
        }
        await show(active, name: "ios-monitoring-delayed")
        await delivery(active, matching: { active.monitoring?.active.first?.observation.provenance.degraded == true }) {
            fixture.stageDegradedMonitor(number: "123")
        }
        await show(active, name: "ios-monitoring-degraded")
        await delivery(active, matching: { active.monitoring?.active.first?.notificationsEnabled == false }) {
            active.setNotifications(active.monitoring!.active.first!.trainRunKey, enabled: false)
        }
        await show(active, name: "ios-monitoring-notifications")
        fixture.stageEndedMonitor(number: "456", status: .arrived)
        await delivery(active, matching: { active.monitoring?.ended.count == 1 })
        await show(active, name: "ios-monitoring-active-ended", offset: 260)
        await show(active, name: "ios-monitoring-arrived", offset: 520)
        await show(active, name: "ios-monitoring-dark", dark: true)
        await show(active, name: "ios-monitoring-large-text", large: true)
        await stop(active)
        let cancelled = monitoring(fixture)
        await delivery(cancelled, matching: { cancelled.monitoring?.ended.contains { $0.status == .cancelled } == true }) {
            fixture.stageEndedMonitor(number: "789", status: .cancelled)
        }
        await show(cancelled, name: "ios-monitoring-cancelled")
        XCTAssertEqual(fixture.networkRequests, 0)
        await stop(cancelled); await zeroCollectors(fixture)
    }
}
