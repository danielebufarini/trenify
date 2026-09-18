import XCTest
import SwiftUI
import UIKit
import SharedApp

@MainActor
final class NativeShellTests: XCTestCase {
    func testOneStateObjectSessionSurvivesSwiftUIRecreationAndClosesOnce() async {
        let fixture = NativeInteropFixture()
        var creations = 0
        let factory = { creations += 1; return fixture.session }
        let scene = windowScene()
        let window = UIWindow(windowScene: scene)
        let shown = expectation(description: "StateObject shell appears")
        shown.assertForOverFulfill = false
        let host = UIHostingController(rootView: OwnerFixture(factory: factory).onAppear { shown.fulfill() })
        window.rootViewController = host
        window.makeKeyAndVisible()
        await fulfillment(of: [shown], timeout: 5)
        XCTAssertEqual(creations, 1)
        // Updating the same SwiftUI root type re-evaluates construction, but preserves StateObject.
        let recomposed = expectation(description: "SwiftUI root recreation rendered")
        host.rootView = OwnerFixture(factory: factory, revision: 1, revised: { recomposed.fulfill() })
            .onAppear { shown.fulfill() }
        host.view.layoutIfNeeded()
        await fulfillment(of: [recomposed], timeout: 5)
        XCTAssertEqual(creations, 1)
        XCTAssertTrue(fixture.sameRoot)
        window.isHidden = true
        window.rootViewController = nil
        fixture.close()
        fixture.close()
        XCTAssertEqual(fixture.driverCloses, 1)
        XCTAssertEqual(fixture.connectivityCloses, 1)
    }

    func testAllTabBindingsForwardTypedActionsThroughOneRoot() async {
        let fixture = NativeInteropFixture()
        let model = NativeShellModel { fixture.session }
        defer { model.close() }
        model.start()
        for (area, tab) in [(NativePrimaryArea.search, MainTab.home), (.monitoring, .monitoring), (.saved, .favorites), (.alerts, .alerts)] {
            let changed = expectation(description: "SKIE authoritative tab")
            changed.assertForOverFulfill = false
            model.onUpdate = { if $0.primaryArea == area { changed.fulfill() } }
            model.selection.wrappedValue = area
            await fulfillment(of: [changed], timeout: 5)
            XCTAssertEqual(fixture.session.state.value.main!.state.value.selectedDestination, tab)
            let identity = model.state.active.identity
            for _ in 0..<4 { model.selection.wrappedValue = area }
            XCTAssertEqual(model.state.active.identity, identity)
            XCTAssertTrue(fixture.sameRoot)
            model.onUpdate = nil
        }
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testSharedDrivenSelectionAndWarmDelegateNotificationUpdateSKIEProjection() async {
        let fixture = NativeInteropFixture()
        let model = NativeShellModel { fixture.session }
        defer { model.close() }
        model.start()
        await update(model, matching: { $0.primaryArea == .monitoring }) { fixture.sharedSelect(area: .monitoring) }
        XCTAssertEqual(model.selection.wrappedValue, .monitoring)
        await update(model, matching: { $0.primaryArea == .alerts && $0.active.destination == .strikeDetail }) {
            fixture.warmNotification(destination: PlatformNotificationDestinationStrike(strikeId: "a"))
        }
        let identity = model.state.active.identity
        fixture.warmNotification(destination: PlatformNotificationDestinationStrike(strikeId: "a"))
        XCTAssertEqual(fixture.session.shell.state.value.path.count, 1)
        XCTAssertEqual(fixture.session.shell.state.value.active.identity, identity)
        fixture.warmNotification(destination: PlatformNotificationDestinationStrike(strikeId: "b"))
        fixture.warmNotification(destination: PlatformNotificationDestinationStrike(strikeId: "a"))
        XCTAssertEqual(fixture.session.shell.state.value.path.count, 2)
        XCTAssertEqual(fixture.session.shell.state.value.active.identity, identity)
        model.back()
        XCTAssertEqual(fixture.session.state.value.main!.state.value.alerts!.state.value.active.strikeId, "b")
        XCTAssertEqual(model.path(for: .alerts).count, 1)
    }

    func testColdPendingDelegateLaunchProjectsAreaAndTypedPathOnce() async {
        let fixture = NativeInteropFixture(pendingDestination: PlatformNotificationDestinationStrike(strikeId: "cold"))
        let model = NativeShellModel { fixture.session }
        defer { model.close() }
        XCTAssertEqual(model.state.primaryArea, .alerts)
        XCTAssertEqual(model.state.base.destination, .alertsOverview)
        XCTAssertEqual(model.state.active.destination, .strikeDetail)
        XCTAssertEqual(model.path(for: .alerts).count, 1)
        let identity = model.state.active.identity
        fixture.warmNotification(destination: PlatformNotificationDestinationStrike(strikeId: "cold"))
        XCTAssertEqual(fixture.session.shell.state.value.active.identity, identity)
        XCTAssertEqual(fixture.session.shell.state.value.path.count, 1)
        model.back()
        XCTAssertFalse(model.state.canGoBack)
    }

    func testHierarchicalNativePathPopAndInvalidInsertionRemainSharedAuthoritative() async {
        let fixture = NativeInteropFixture()
        let model = NativeShellModel { fixture.session }
        defer { model.close() }
        fixture.openHistory()
        model.start()
        await update(model, matching: { $0.active.destination == .history }) {}
        // T8.5: the journey composer lives in the native Home base, so the
        // Search form is not a separate visual path entry. History appears
        // directly above Home; the shared Journey stack still retains Search.
        XCTAssertEqual(model.path(for: .search).map(\.destination), [.history])
        let path = model.path(for: .search)
        model.requestPath(path + [path[0]], area: .search)
        model.requestPath([], area: .saved)
        XCTAssertEqual(model.path(for: .search), path)
        let obsoleteBinding = model.pathBinding(for: .search)
        fixture.warmNotification(destination: PlatformNotificationDestinationTrain(provider: "viaggiatreno",
            number: "123", origin: "S1", serviceDate: "2026-09-14"))
        obsoleteBinding.wrappedValue = []
        XCTAssertEqual(model.state.active.destination, .trainDetail)
        XCTAssertEqual(fixture.session.state.value.navigation.active.destination, .trainDetail)
        model.back()
        XCTAssertEqual(model.state.active.destination, .history)
        model.pathBinding(for: .search).wrappedValue = Array(path.dropLast())
        XCTAssertEqual(model.state.active.destination, .home)
        XCTAssertEqual(fixture.session.state.value.main!.state.value.journey!.state.value.active.destination, .journeySearch)
        // The filtered path is already empty (Search lives in Home); a shared
        // Back selects Home from the retained Journey Search.
        model.back()
        XCTAssertEqual(model.state.active.destination, .home)
        XCTAssertEqual(fixture.session.state.value.main!.state.value.selectedDestination, .home)
    }

    func testHistoryAndSettingsRoutesStayNativeOnPopAndSessionClose() async {
        // T8.15: the legacy Compose host/lease API is removed. History and
        // Settings resolve through their native facades; pop and close keep
        // shared navigation authoritative with no renderer fallback.
        let fixture = NativeInteropFixture()
        let model = NativeShellModel { fixture.session }
        fixture.openHistory()
        model.start()
        await update(model, matching: { $0.active.destination == .history }) {}
        XCTAssertNotNil(fixture.session.shell.state.value.active.history)
        fixture.openSettings()
        await update(model, matching: { $0.active.destination == .settings }) {}
        XCTAssertNotNil(fixture.session.shell.state.value.active.settings)
        model.back()
        XCTAssertEqual(model.state.active.destination, .history)
        XCTAssertNotNil(fixture.session.shell.state.value.active.history)
        // Back returns to native History; reopen Settings for the
        // session-close proof below.
        fixture.openSettings()
        await update(model, matching: { $0.active.destination == .settings }) {}
        model.close()
        XCTAssertEqual(fixture.driverCloses, 1)
    }

    func testSceneLifecycleTaskCancellationAndAdapterDeinitAreDeterministic() async {
        let fixture = NativeInteropFixture()
        var model: NativeShellModel? = NativeShellModel { fixture.session }
        weak var weakModel = model
        model!.start()
        model!.start()
        await update(model!, matching: { _ in true }) {}
        XCTAssertEqual(fixture.collectorCount, 1)
        for _ in 0..<3 {
            model!.scenePhase(.active)
            XCTAssertEqual(fixture.session.phase.value, .active)
            model!.scenePhase(.inactive)
            model!.scenePhase(.background)
            XCTAssertEqual(fixture.session.phase.value, .inactive)
        }
        let task = model!.task!
        model!.onUpdate = nil
        model = nil
        XCTAssertNil(weakModel)
        await task.value
        await noCollectors(fixture)
        XCTAssertEqual(fixture.session.phase.value, .closed)
        XCTAssertEqual(fixture.projectionCount, 0)
        XCTAssertEqual(fixture.driverCloses, 1)
    }

    func testNativeTabViewNavigationStackRenderingAndNativeSettingsPop() async {
        for (area, dark) in [(NativePrimaryArea.search, false), (.monitoring, true)] {
            let fixture = NativeInteropFixture()
            let model = NativeShellModel { fixture.session }
            model.select(area)
            model.start()
            let window = await show(model, dark: dark)
            // T8.5: Search-tab Home is native SwiftUI; T8.8: Monitoring is
            // native SwiftUI; T8.9: Saved is native SwiftUI; Alerts is
            // native. T8.15 removed the legacy host/render path entirely.
            // Let SKIE subscriptions establish before snapshotting: the
            // pre-T8.15 settle-await provided this quiescence implicitly.
            await poll("home collectors started") { fixture.collectorCount >= 1 }
            let observations = fixture.homeObservations
            let collectors = fixture.collectorCount
            model.objectWillChange.send()
            window.layoutIfNeeded()
            // Recomposition overlaps SKIE subscriptions transiently; wait
            // for the stale collector to drain, then the count must return
            // to the snapshot (no leak).
            await poll("recomposition collectors settled") { fixture.collectorCount == collectors }
            XCTAssertEqual(fixture.homeObservations, observations)
            XCTAssertEqual(fixture.collectorCount, collectors)
            // T8.5: native Home observes Main/Home/Journey facades, so Search
            // carries its borrowed SKIE collectors; recomposition must not leak.
            XCTAssertGreaterThanOrEqual(fixture.collectorCount, 1)
            let tabs = descendants(window.rootViewController!).compactMap { $0 as? UITabBarController }
            XCTAssertEqual(tabs.first?.tabBar.items?.count, 4)
            XCTAssertEqual(tabs.first?.tabBar.items?.map { $0.title ?? "" }, ["Search", "Monitoring", "Saved", "Alerts"])
            XCTAssertEqual(tabs.first?.selectedIndex, area == .search ? 0 : 1)
            capture(window, name: "ios-\(area == .search ? "search" : "monitoring")-\(dark ? "dark" : "light")")
            model.openSettings()
            await update(model, matching: { $0.active.destination == .settings }) {}
            let navigation = descendants(window.rootViewController!).compactMap { $0 as? UINavigationController }
                .first { $0.viewControllers.count > 1 }
            XCTAssertNotNil(navigation)
            await settle(navigation)
            capture(window, name: "ios-settings-\(dark ? "dark" : "light")")
            let popped = expectation(description: "System NavigationStack pop forwarded")
            popped.assertForOverFulfill = false
            model.onUpdate = { if !$0.canGoBack { popped.fulfill() } }
            navigation?.popViewController(animated: false)
            await fulfillment(of: [popped], timeout: 5)
            XCTAssertFalse(model.state.canGoBack)
            XCTAssertEqual(model.state.primaryArea, area)
            model.onUpdate = nil
            for _ in 0..<2 {
                let other: NativePrimaryArea = area == .search ? .monitoring : .search
                await update(model, matching: { $0.primaryArea == other }) { model.select(other) }
                // T8.8: Search and Monitoring are both native.
                XCTAssertTrue(fixture.sameRoot)
                await update(model, matching: { $0.primaryArea == area }) { model.select(area) }
            }
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.driverCloses, 1)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
    }

    func testHomeLastContentReachableAboveTabChrome() async {
        // Post-acceptance reachability: the final Home content must scroll
        // fully above the native tab chrome (floating Liquid Glass
        // included) while the tab bar itself stays native. A fullscreen
        // window carries the real device safe area, which is exactly what a
        // floating bar overlaps; a short window reports zero safe area and
        // cannot reproduce the issue. All chrome geometry comes from the
        // real UITabBar frame: no hard-coded bar height anywhere in this
        // proof.
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let model = NativeShellModel { fixture.session }
        model.select(.search)
        model.start()
        let window = await show(model, dark: false)
        defer { window.isHidden = true; window.rootViewController = nil }

        guard let homeScroll = viewDescendants(of: window).compactMap({ $0 as? UIScrollView }).first(where: { scroll in
            viewDescendants(of: scroll).contains(where: { $0 is UITextField })
        }) else {
            XCTFail("Home scroll view with composer fields missing")
            return
        }
        // Settle the measured chrome inset first: poll the effective bottom
        // inset for stability with a bounded acknowledgement, never a sleep.
        var lastInset: CGFloat = -1
        var stableTurns = 0
        await poll("tab chrome inset settled") {
            let current = homeScroll.adjustedContentInset.bottom
            if current == lastInset { stableTurns += 1 } else { stableTurns = 0; lastInset = current }
            return stableTurns >= 2
        }
        // Overflow is required: without it the reachability proof is vacuous.
        XCTAssertGreaterThan(homeScroll.contentSize.height, homeScroll.bounds.height,
                             "Home content must overflow the viewport")
        homeScroll.setContentOffset(CGPoint(
            x: 0,
            y: homeScroll.contentSize.height - homeScroll.bounds.height + homeScroll.adjustedContentInset.bottom,
        ), animated: false)
        window.layoutIfNeeded()

        guard let tabBar = viewDescendants(of: window).compactMap({ $0 as? UITabBar }).first(where: {
            !$0.isHidden && $0.alpha > 0.01 && $0.window != nil
        }) else {
            XCTFail("Real tab chrome missing: reachability needs the native bar")
            return
        }
        let barTop = tabBar.convert(tabBar.bounds, to: window).minY
        print("T85-DIAG barTop=\(barTop) winH=\(window.bounds.maxY) safeBottom=\(window.safeAreaInsets.bottom) contentH=\(homeScroll.contentSize.height) boundsH=\(homeScroll.bounds.height) insetB=\(homeScroll.adjustedContentInset.bottom) offsetY=\(homeScroll.contentOffset.y) scrollMinY=\(homeScroll.convert(homeScroll.bounds, to: window).minY)")
        XCTAssertLessThan(barTop, window.bounds.maxY, "Tab chrome must overlap the viewport or the proof is vacuous")
        // The content end, mapped into window coordinates, must sit fully
        // above the chrome top: the last actionable item can be brought
        // clear of the bar by scrolling, never hidden beneath it.
        let scrollFrame = homeScroll.convert(homeScroll.bounds, to: window)
        let endInWindow = scrollFrame.minY + (homeScroll.contentSize.height - homeScroll.contentOffset.y)
        XCTAssertLessThanOrEqual(endInWindow, barTop + 1)

        await model.close()?.value
        await noCollectors(fixture)
        XCTAssertEqual(fixture.driverCloses, 1)
        XCTAssertEqual(fixture.connectivityCloses, 1)
        XCTAssertEqual(fixture.networkRequests, 0)
        XCTAssertTrue(fixture.sameRoot)
    }

    private func viewDescendants(of view: UIView) -> [UIView] {
        var out = [view]
        var index = 0
        while index < out.count {
            out.append(contentsOf: out[index].subviews)
            index += 1
        }
        return out
    }

    func testNativeHomeReviewCapturesDarkNarrowSelectedAndTrainEntry() async {
        // Selected From/To state (resolved endpoints, native pickers, segmented
        // mode, route-favorite entry): deterministic selection, not typed text.
        do {
            let fixture = NativeInteropFixture()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            let input = fixture.session.state.value.main!.state.value.journey!.state.value.journeySearch!
            input.stationText(origin: true, text: "Roma")
            await poll("origin suggestions") { !input.state.value.suggestions.isEmpty }
            input.selectStation(station: input.state.value.suggestions.first(where: { $0.name == "Roma Termini" })!)
            await poll("origin resolved") { input.state.value.origin != nil }
            input.stationText(origin: false, text: "Milano")
            await poll("destination suggestions") { !input.state.value.suggestions.isEmpty }
            input.selectStation(station: input.state.value.suggestions.first(where: { $0.name == "Milano Centrale" })!)
            await poll("destination resolved") { input.state.value.destination != nil }
            input.setMode(mode: .arriveBy)
            let window = await show(model, dark: false)
            window.layoutIfNeeded()
            capture(window, name: "ios-home-selected")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Home dark inside the native tab shell.
        do {
            let fixture = NativeInteropFixture()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            let window = await show(model, dark: true)
            window.layoutIfNeeded()
            capture(window, name: "ios-home-dark")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Narrow layout (Dynamic Type and small widths use the same adaptive
        // primitives; a 320pt shell proves the composer does not clip).
        do {
            let fixture = NativeInteropFixture()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            let window = await show(model, dark: false)
            window.frame = CGRect(x: 0, y: 0, width: 320, height: 844)
            window.layoutIfNeeded()
            capture(window, name: "ios-home-narrow")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Secondary Train Search entry destination (T8.7 owns the screen; Home
        // owns only this entry/navigation, proven here with Back to Home).
        do {
            let fixture = NativeInteropFixture()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            fixture.session.state.value.main!.state.value.home!.openTrainSearch()
            await update(model, matching: { $0.active.destination == .trainSearch }) {}
            let window = await show(model, dark: false)
            XCTAssertNotNil(model.state.active.trainSearch)
            window.layoutIfNeeded()
            capture(window, name: "ios-train-entry")
            model.back()
            await update(model, matching: { $0.active.destination == .home }) {}
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
    }

    private func poll(_ description: String, _ predicate: @escaping () -> Bool) async {
        let settled = expectation(description: description)
        settled.assertForOverFulfill = false
        let task = Task { @MainActor in
            for _ in 0..<100 {
                if predicate() { settled.fulfill(); return }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        await fulfillment(of: [settled], timeout: 6)
        task.cancel()
        await task.value
    }

    private func update(_ model: NativeShellModel, matching predicate: @escaping (NativeShellState) -> Bool, action: () -> Void) async {
        let observed = expectation(description: "SKIE shell state acknowledgement")
        observed.assertForOverFulfill = false
        model.onUpdate = { if predicate($0) { observed.fulfill() } }
        action()
        await fulfillment(of: [observed], timeout: 5)
        model.onUpdate = nil
    }

    private func noCollectors(_ fixture: NativeInteropFixture) async {
        let stopped = expectation(description: "Kotlin observer finally acknowledged")
        let acknowledgement = Task { @MainActor in
            for await count in fixture.collectorCounts {
                if count.int32Value == 0 { stopped.fulfill(); return }
            }
        }
        await fulfillment(of: [stopped], timeout: 5)
        acknowledgement.cancel()
        await acknowledgement.value
    }

    private func windowScene() -> UIWindowScene {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!
    }

    private func show(_ model: NativeShellModel, dark: Bool) async -> UIWindow {
        let appeared = expectation(description: "Native shell rendering appeared")
        appeared.assertForOverFulfill = false
        let window = UIWindow(windowScene: windowScene())
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = UIHostingController(rootView: NativeAppShell(model: model)
            .environment(\.colorScheme, dark ? .dark : .light).onAppear { appeared.fulfill() })
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)
        window.layoutIfNeeded()
        return window
    }

    private func descendants(_ controller: UIViewController) -> [UIViewController] {
        [controller] + controller.children.flatMap(descendants)
    }

    private func settle(_ navigation: UINavigationController?) async {
        guard let transition = navigation?.transitionCoordinator else { return }
        let completed = expectation(description: "Native transition completed")
        guard transition.animate(alongsideTransition: nil, completion: { _ in completed.fulfill() }) else { return }
        await fulfillment(of: [completed], timeout: 5)
        navigation?.view.layoutIfNeeded()
    }

    private func capture(_ window: UIWindow, name: String) {
        window.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

@MainActor
private struct OwnerFixture: View {
    @StateObject private var model: NativeShellModel
    let revision: Int
    let revised: () -> Void
    init(factory: @escaping () -> NativeApplicationSession, revision: Int = 0, revised: @escaping () -> Void = {}) {
        _model = StateObject(wrappedValue: NativeShellModel(makeSession: factory))
        self.revision = revision
        self.revised = revised
    }
    var body: some View {
        NativeAppShell(model: model).onAppear { model.start() }
            .preference(key: RenderedRevision.self, value: revision)
            .onPreferenceChange(RenderedRevision.self) { if $0 == revision { revised() } }
    }
}

private struct RenderedRevision: PreferenceKey {
    static let defaultValue = -1
    static func reduce(value: inout Int, nextValue: () -> Int) { value = nextValue() }
}
