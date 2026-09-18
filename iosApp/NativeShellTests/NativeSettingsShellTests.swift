import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

/// T8.11 native Settings: the Swift model projects the shared Settings
/// component and forwards its actions; the shell routes Settings to the
/// native SwiftUI view. Persistence,
/// validation, permission policy and deletion stay shared.
@MainActor
final class NativeSettingsShellTests: XCTestCase {
    private func delivery(_ model: NativeSettingsModel, matching: @escaping () -> Bool, action: () -> Void = {}) async {
        let ready = expectation(description: "screen-level SKIE acknowledgement")
        ready.assertForOverFulfill = false
        model.onUpdate = { if matching() { ready.fulfill() } }
        action()
        if matching() { ready.fulfill() }
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
    }
    private func routeModel(_ fixture: NativeInteropFixture) -> NativeSettingsModel {
        let model = NativeSettingsModel(session: fixture.session, identity: fixture.session.shell.state.value.active.identity)
        model.start(); model.start()
        return model
    }
    private func settings(_ fixture: NativeInteropFixture) -> NativeSettingsModel {
        fixture.openSettings()
        return routeModel(fixture)
    }
    private func stop(_ model: NativeSettingsModel) async {
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

    func testSettingsProjectsPersistedDefaults() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings != nil })
        await delivery(model, matching: { model.settings?.loading == false })
        let state = model.settings!
        XCTAssertFalse(state.loading)
        XCTAssertTrue(state.notificationsEnabled)
        XCTAssertEqual(state.delayThresholdText, "15")
        XCTAssertEqual(state.delayThresholdValidation, .valid)
        XCTAssertTrue(state.notifyDelay)
        XCTAssertTrue(state.notifyPlatform)
        XCTAssertTrue(state.notifyCancellation)
        XCTAssertTrue(state.notifyDeparture)
        XCTAssertTrue(state.notifyArrival)
        XCTAssertFalse(state.strikeNotificationsEnabled)
        XCTAssertFalse(state.strikePermissionRequestDenied)
        // Effective permission is platform truth, never a second preference:
        // it is observed (nil only before the first platform answer), while
        // the saved preference above stays authoritative for delivery choice.
        XCTAssertNotNil(state.permission)
        XCTAssertFalse(state.saving)
        XCTAssertNil(state.failedSave)
        XCTAssertEqual(state.historyDeletion, .idle)
        XCTAssertEqual(state.favoritesDeletion, .idle)
        XCTAssertEqual(fixture.networkRequests, 0)
        await stop(model); await zeroCollectors(fixture)
    }

    func testDelayedIosPermissionCannotRestoreLoadingAfterPersistedSettingsArrive() async {
        let fixture = NativeInteropFixture(settingsCorrectiveThresholdMinutes: 42, delayedPermission: true)
        defer { fixture.close() }
        let model = settings(fixture)

        await delivery(model, matching: { model.settings?.loading == false })
        XCTAssertFalse(model.settings!.notificationsEnabled)
        XCTAssertEqual(model.settings!.delayThresholdText, "42")
        XCTAssertFalse(model.settings!.notifyDelay)
        XCTAssertTrue(model.settings!.notifyPlatform)
        XCTAssertFalse(model.settings!.notifyCancellation)
        XCTAssertTrue(model.settings!.notifyDeparture)
        XCTAssertFalse(model.settings!.notifyArrival)

        // Production iOS gets this value through an asynchronous
        // UNUserNotificationCenter callback. Completing it after the real
        // repository emission must update only permission, never restore the
        // pre-load Settings snapshot.
        await delivery(model, matching: {
            model.settings?.permission == .denied && model.settings?.loading == false
        }) {
            fixture.releaseSettingsPermission()
        }
        XCTAssertEqual(model.settings?.delayThresholdText, "42")
        XCTAssertFalse(model.settings!.notificationsEnabled)

        // Representative mutation follows the normal component/repository/
        // presentation path and survives destination recreation.
        await delivery(model, matching: { model.settings?.notificationsEnabled == true }) {
            model.setNotifications(true)
        }
        await delivery(model, matching: { model.settings?.notifyDelay == true }) {
            model.setEventFlag(.delay, enabled: true)
        }
        fixture.session.shell.back()
        let reopened = settings(fixture)
        await delivery(reopened, matching: {
            reopened.settings?.loading == false &&
            reopened.settings?.notificationsEnabled == true &&
            reopened.settings?.delayThresholdText == "42" &&
            reopened.settings?.notifyDelay == true &&
            reopened.settings?.notifyCancellation == false
        })

        await stop(model); await stop(reopened); await zeroCollectors(fixture)
    }

    func testNotificationTogglePersistsThroughSharedRepository() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings?.loading == false })
        await delivery(model, matching: { model.settings?.notificationsEnabled == false }) {
            model.setNotifications(false)
        }
        await delivery(model, matching: { model.settings?.notificationsEnabled == true }) {
            model.setNotifications(true)
        }
        await stop(model); await zeroCollectors(fixture)
    }

    func testThresholdValidationAndSaveRoundTrip() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings?.loading == false })
        // Invalid input stays a validation state, never a coerced save.
        await delivery(model, matching: { model.settings?.delayThresholdValidation == .invalid }) {
            model.editThreshold("abc")
        }
        XCTAssertNil(model.settings?.failedSave)
        await delivery(model, matching: {
            model.settings?.delayThresholdText == "30" && model.settings?.delayThresholdValidation == .valid
        }) {
            model.editThreshold("30")
        }
        await delivery(model, matching: { model.settings?.delayThresholdText == "30" && model.settings?.saving == false }) {
            model.saveThreshold()
        }
        // Persistence round-trips through the shared repository: reopening
        // observes the saved installation default.
        fixture.session.shell.back()
        let reopened = settings(fixture)
        await delivery(reopened, matching: { reopened.settings?.delayThresholdText == "30" })
        await stop(model); await stop(reopened); await zeroCollectors(fixture)
    }

    func testEventFlagsForwardToSharedDefaults() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings?.loading == false })
        await delivery(model, matching: { model.settings?.notifyDelay == false }) {
            model.setEventFlag(.delay, enabled: false)
        }
        await delivery(model, matching: { model.settings?.notifyPlatform == false }) {
            model.setEventFlag(.platform, enabled: false)
        }
        await delivery(model, matching: { model.settings?.notifyCancellation == false }) {
            model.setEventFlag(.cancellation, enabled: false)
        }
        await delivery(model, matching: { model.settings?.notifyDeparture == false }) {
            model.setEventFlag(.departure, enabled: false)
        }
        await delivery(model, matching: { model.settings?.notifyArrival == false }) {
            model.setEventFlag(.arrival, enabled: false)
        }
        await stop(model); await zeroCollectors(fixture)
    }

    func testStrikeToggleWithoutPermissionRequestKeepsOptOutDenied() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings?.loading == false })
        // The fixture grants no permission request: enabling stays denied
        // and never flips the shared opt-in behind the user's back.
        await delivery(model, matching: { model.settings?.strikePermissionRequestDenied == true }) {
            model.toggleStrikeNotifications()
        }
        XCTAssertFalse(model.settings!.strikeNotificationsEnabled)
        await stop(model); await zeroCollectors(fixture)
    }

    func testHistoryDeletionLifecycle() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings?.loading == false })
        await delivery(model, matching: { model.settings?.historyDeletion == .confirming }) {
            model.requestHistoryDeletion()
        }
        await delivery(model, matching: { model.settings?.historyDeletion == .idle }) {
            model.cancelHistoryDeletion()
        }
        await delivery(model, matching: { model.settings?.historyDeletion == .confirming }) {
            model.requestHistoryDeletion()
        }
        await delivery(model, matching: { model.settings?.historyDeletion == .success }) {
            model.confirmHistoryDeletion()
        }
        XCTAssertEqual(model.settings?.favoritesDeletion, .idle)
        await stop(model); await zeroCollectors(fixture)
    }

    func testFavoritesDeletionLifecycle() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings?.loading == false })
        await delivery(model, matching: { model.settings?.favoritesDeletion == .confirming }) {
            model.requestFavoritesDeletion()
        }
        await delivery(model, matching: { model.settings?.favoritesDeletion == .idle }) {
            model.cancelFavoritesDeletion()
        }
        await delivery(model, matching: { model.settings?.favoritesDeletion == .confirming }) {
            model.requestFavoritesDeletion()
        }
        await delivery(model, matching: { model.settings?.favoritesDeletion == .success }) {
            model.confirmFavoritesDeletion()
        }
        XCTAssertEqual(model.settings?.historyDeletion, .idle)
        await stop(model); await zeroCollectors(fixture)
    }

    func testShellRoutesToNativeSettingsAndBackReturnsToSource() async {
        let fixture = NativeInteropFixture()
        let model = NativeShellModel { fixture.session }
        defer { model.close() }
        model.start()
        let areas: [NativePrimaryArea] = [.saved, .monitoring, .alerts]
        for area in areas {
            await shellUpdate(model, matching: { $0.primaryArea == area }) { model.select(area) }
            await shellUpdate(model, matching: { $0.active.destination == .settings }) { model.openSettings() }
            // Settings stays secondary: the primary area is unchanged and
            // Settings never becomes a fifth tab destination.
            XCTAssertEqual(model.state.primaryArea, area)
            XCTAssertNotNil(fixture.session.shell.state.value.active.settings)
            // Repeated opens do not create a second navigation model.
            let identity = model.state.active.identity
            model.openSettings()
            XCTAssertEqual(model.state.active.identity, identity)
            model.back()
            XCTAssertEqual(model.state.primaryArea, area)
            XCTAssertNotEqual(model.state.active.destination, .settings)
        }
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    private func shellUpdate(_ model: NativeShellModel, matching predicate: @escaping (NativeShellState) -> Bool, action: () -> Void) async {
        if predicate(model.state) { return }
        let observed = expectation(description: "SKIE shell state acknowledgement")
        observed.assertForOverFulfill = false
        model.onUpdate = { if predicate($0) { observed.fulfill() } }
        action()
        await fulfillment(of: [observed], timeout: 5)
        model.onUpdate = nil
    }

    func testNativeSettingsRenderingAndInvalidThreshold() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = settings(fixture)
        await delivery(model, matching: { model.settings?.loading == false })
        render(NativeSettingsView(model: model), name: "ios-settings")
        await delivery(model, matching: { model.settings?.delayThresholdValidation == .invalid }) {
            model.editThreshold("abc")
        }
        render(NativeSettingsView(model: model), name: "ios-settings-invalid")
        renderDark(NativeSettingsView(model: model), name: "ios-settings-dark")
        renderLarge(NativeSettingsView(model: model), name: "ios-settings-large-text")
        await stop(model); await zeroCollectors(fixture)
    }

    private func render(_ view: some View, name: String) {
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

    private func renderLarge(_ view: some View, name: String) {
        let controller = UIHostingController(rootView: view.environment(\.dynamicTypeSize, .accessibility3))
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

    private func renderDark(_ view: some View, name: String) {
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
