import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

@MainActor
final class NativeAlertsShellTests: XCTestCase {
    private func delivery(_ model: NativeAlertsModel, matching: @escaping () -> Bool, action: () -> Void = {}) async {
        let ready = expectation(description: "screen-level SKIE acknowledgement")
        ready.assertForOverFulfill = false
        model.onUpdate = { if matching() { ready.fulfill() } }
        action()
        if matching() { ready.fulfill() }
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
    }
    private func routeModel(_ fixture: NativeInteropFixture) -> NativeAlertsModel {
        let model = NativeAlertsModel(session: fixture.session, identity: fixture.session.shell.state.value.active.identity)
        model.start(); model.start()
        return model
    }
    private func alerts(_ fixture: NativeInteropFixture) -> NativeAlertsModel {
        fixture.sharedSelect(area: .alerts)
        return routeModel(fixture)
    }
    private func stop(_ model: NativeAlertsModel) async {
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

    func testOverviewProjectsVisibleStrike() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        // Settle init (observation subscription + refresh) before staging,
        // then prove the state update propagates to the native projection.
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        let state = model.alerts!
        XCTAssertFalse(state.loading)
        let row = state.strikes.first!
        XCTAssertEqual(row.strikeId, "alert-1")
        XCTAssertEqual(row.sector, "Ferroviario")
        XCTAssertEqual(row.status, .scheduled)
        XCTAssertTrue(row.railwayRelevant)
        XCTAssertEqual(row.relevance, .regional)
        XCTAssertEqual(row.regions, ["Piemonte"])
        XCTAssertEqual(row.operators, ["Trenitalia"])
        XCTAssertTrue(row.canOpenReference)
        XCTAssertEqual(state.allStrikes.count, 1)
        XCTAssertFalse(state.visibleEmpty)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .alertsOverview)
        XCTAssertEqual(fixture.networkRequests, 0)
        await stop(model); await zeroCollectors(fixture)
    }

    func testModifiedStatusProjectsExplicitly() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-mod", status: .modified)
        }
        XCTAssertEqual(model.alerts!.strikes.first!.status, .modified)
        await stop(model); await zeroCollectors(fixture)
    }

    func testRevokedLeavesOverviewButResolvesAsDetail() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.allStrikes.count == 1 }) {
            fixture.stageFutureAlert(id: "revoked-1", status: .revoked)
        }
        XCTAssertTrue(model.alerts!.strikes.isEmpty)
        XCTAssertTrue(model.alerts!.visibleEmpty)
        XCTAssertEqual(model.alerts!.allStrikes.first!.status, .revoked)
        await stop(model); await zeroCollectors(fixture)
    }

    func testEmptyOverview() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.visibleEmpty == true }) {
            fixture.stageAlertsEmpty()
        }
        // The staged empty state must actually arrive: visibleEmpty is already
        // true on the pre-staging snapshot (the default staged strike ended),
        // so the delivery above can fulfill before the staged update lands.
        // Waiting for the staged truth explicitly removes the race.
        await delivery(model, matching: { model.alerts?.allStrikes.isEmpty == true })
        XCTAssertTrue(model.alerts!.strikes.isEmpty)
        XCTAssertTrue(model.alerts!.allStrikes.isEmpty)
        await stop(model); await zeroCollectors(fixture)
    }

    func testOpenDetailAndBackPreservesIdentity() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        model.open("alert-1")
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .strikeDetail)
        XCTAssertEqual(fixture.session.shell.state.value.active.alertStrikeId, "alert-1")
        let detailModel = routeModel(fixture)
        await delivery(detailModel, matching: { detailModel.detail != nil })
        XCTAssertEqual(detailModel.strikeId, "alert-1")
        XCTAssertEqual(detailModel.detail?.strikeId, "alert-1")
        XCTAssertEqual(detailModel.detail?.status, .scheduled)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .alertsOverview)
        await stop(model); await stop(detailModel); await zeroCollectors(fixture)
    }

    func testUnknownStrikeOpenIsNoop() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        // Unknown id is a no-op through the shared action: no navigation occurs.
        model.open("no-such-strike")
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .alertsOverview)
        await stop(model); await zeroCollectors(fixture)
    }

    func testNotificationToggleDenialStaysTruthful() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        XCTAssertFalse(model.alerts!.notificationsEnabled)
        // The fixture denies OS permission: the toggle routes to the shared
        // action and the denial renders truthfully (enabled path is proven
        // in shared unit tests with a granting permission).
        await delivery(model, matching: { model.alerts?.notificationPermissionDenied == true }) { model.toggleNotifications() }
        XCTAssertFalse(model.alerts!.notificationsEnabled)
        XCTAssertTrue(model.alerts!.notificationPermissionDenied)
        await stop(model); await zeroCollectors(fixture)
    }

    func testFailureRetainsContent() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        await delivery(model, matching: { model.alerts?.observation.failure != nil }) { fixture.stageAlertsFailure() }
        XCTAssertEqual(model.alerts!.strikes.count, 1)
        XCTAssertTrue(model.alerts!.hasRetainedContent)
        await stop(model); await zeroCollectors(fixture)
    }

    func testDisappearedStrikeShowsNotFound() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        model.open("alert-1")
        let detail = routeModel(fixture)
        await delivery(detail, matching: { detail.detail != nil })
        await delivery(detail, matching: { detail.detail == nil }) { fixture.stageAlertsEmpty() }
        XCTAssertNil(detail.detail)
        await stop(model); await stop(detail); await zeroCollectors(fixture)
    }

    func testTeardownCancelsCollectors() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        await stop(model); await zeroCollectors(fixture)
    }

    func testAlertsStringsAreTranslated() async {
        XCTAssertFalse(alertsString("title").isEmpty)
        XCTAssertFalse(alertsString("statusRevoked").isEmpty)
        XCTAssertFalse(alertsString("referenceUnavailable").isEmpty)
    }

    func testDeterministicNativeReviewCaptures() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = alerts(fixture)
        await delivery(model, matching: { model.alerts != nil })
        await delivery(model, matching: { model.alerts?.strikes.count == 1 }) {
            fixture.stageFutureAlert(id: "alert-1", status: .scheduled)
        }
        capture(NativeAlertsView(model: model), name: "ios-alerts-overview")
        captureDark(NativeAlertsView(model: model), name: "ios-alerts-dark")
        capture(NativeAlertsView(model: model).environment(\.sizeCategory, .accessibilityExtraLarge), name: "ios-alerts-large-text")
        model.open("alert-1")
        let detail = routeModel(fixture)
        await delivery(detail, matching: { detail.detail != nil })
        capture(NativeAlertsView(model: detail), name: "ios-alerts-detail")
        fixture.session.shell.back()
        await delivery(model, matching: { model.alerts?.allStrikes.first?.status == .revoked }) {
            fixture.stageFutureAlert(id: "revoked-1", status: .revoked)
        }
        model.open("revoked-1")
        let revoked = routeModel(fixture)
        await delivery(revoked, matching: { revoked.detail?.status == .revoked })
        capture(NativeAlertsView(model: revoked), name: "ios-alerts-revoked")
        await stop(model); await stop(detail); await stop(revoked); await zeroCollectors(fixture)
    }

    private func capture(_ view: some View, name: String) {
        let controller = UIHostingController(rootView: view)
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.3))
        let renderer = UIGraphicsImageRenderer(bounds: controller.view.bounds)
        let image = renderer.image { _ in controller.view.drawHierarchy(in: controller.view.bounds, afterScreenUpdates: true) }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func captureDark(_ view: some View, name: String) {
        let controller = UIHostingController(rootView: view.preferredColorScheme(.dark))
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        window.overrideUserInterfaceStyle = .dark
        window.makeKeyAndVisible()
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.3))
        let renderer = UIGraphicsImageRenderer(bounds: controller.view.bounds)
        let image = renderer.image { _ in controller.view.drawHierarchy(in: controller.view.bounds, afterScreenUpdates: true) }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
