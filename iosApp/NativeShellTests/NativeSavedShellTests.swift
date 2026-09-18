import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

@MainActor
final class NativeSavedShellTests: XCTestCase {
    private func delivery(_ model: NativeSavedModel, matching: @escaping () -> Bool, action: () -> Void = {}) async {
        let ready = expectation(description: "screen-level SKIE acknowledgement")
        ready.assertForOverFulfill = false
        model.onUpdate = { if matching() { ready.fulfill() } }
        action()
        if matching() { ready.fulfill() }
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
    }
    private func routeModel(_ fixture: NativeInteropFixture) -> NativeSavedModel {
        let model = NativeSavedModel(session: fixture.session, identity: fixture.session.shell.state.value.active.identity)
        model.start(); model.start()
        return model
    }
    private func saved(_ fixture: NativeInteropFixture) -> NativeSavedModel {
        fixture.sharedSelect(area: .saved)
        return routeModel(fixture)
    }
    private func stop(_ model: NativeSavedModel) async {
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

    func testEmptySavedProjectsEmptySections() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = saved(fixture)
        await delivery(model, matching: { model.saved != nil })
        let state = model.saved!
        XCTAssertFalse(state.loading)
        XCTAssertFalse(state.historyLoading)
        XCTAssertTrue(state.stations.isEmpty)
        XCTAssertTrue(state.routes.isEmpty)
        XCTAssertTrue(state.trains.isEmpty)
        XCTAssertTrue(state.journeyHistory.isEmpty)
        XCTAssertTrue(state.trainHistory.isEmpty)
        XCTAssertTrue(state.savedEmpty)
        XCTAssertFalse(state.observationFailed)
        XCTAssertFalse(state.historyObservationFailed)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .saved)
        await stop(model); await zeroCollectors(fixture)
    }

    func testFavoritesProjectSemanticIdentity() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.setFavoriteStation(favorite: true)
        fixture.stageFavoriteRoute()
        fixture.stageFavoriteTrain(number: "123", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: "Milano Centrale")
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 1 })
        let state = model.saved!
        XCTAssertEqual(state.stations.count, 1)
        XCTAssertEqual(state.stations.first!.stationId, "internal-station")
        XCTAssertEqual(state.routes.count, 1)
        XCTAssertEqual(state.routes.first!.originName, "Roma Termini")
        let train = state.trains.first!
        XCTAssertEqual(train.number, "123")
        XCTAssertEqual(train.originId, "internal-station")
        XCTAssertEqual(train.originName, "Roma Termini")
        XCTAssertEqual(train.operatorName, "Trenitalia")
        XCTAssertEqual(train.destinationName, "Milano Centrale")
        XCTAssertTrue(train.canOpen)
        XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
        await stop(model); await zeroCollectors(fixture)
    }

    func testSameNumberDifferentOriginAndOperatorStayDistinct() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "9410", originId: "roma-termini", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: "Milano Centrale")
        fixture.stageFavoriteTrain(number: "9410", originId: "napoli-centrale", originName: "Napoli Centrale",
            operatorName: "Italo", destinationName: "Milano Centrale")
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 2 })
        let trains = model.saved!.trains
        XCTAssertEqual(Set(trains.map { $0.identity }).count, 2)
        XCTAssertEqual(Set(trains.map { $0.originId ?? "" }), ["roma-termini", "napoli-centrale"])
        XCTAssertEqual(Set(trains.map { $0.operatorName ?? "" }), ["Trenitalia", "Italo"])
        await stop(model); await zeroCollectors(fixture)
    }

    func testUnknownFavoriteTrainOmitsAbsentValues() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "123", originId: nil, originName: nil, operatorName: nil, destinationName: nil)
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 1 })
        let train = model.saved!.trains.first!
        XCTAssertNil(train.originId)
        XCTAssertNil(train.originName)
        XCTAssertNil(train.operatorName)
        XCTAssertNil(train.destinationName)
        await stop(model); await zeroCollectors(fixture)
    }

    func testRemoveFavoriteTrainForwards() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "123", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        fixture.stageFavoriteTrain(number: "456", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 2 })
        let removed = model.saved!.trains.first { $0.number == "123" }!.identity
        await delivery(model, matching: { model.saved?.trains.count == 1 }) { model.removeTrain(removed) }
        XCTAssertEqual(model.saved!.trains.first!.number, "456")
        await stop(model); await zeroCollectors(fixture)
    }

    func testOpenFavoriteEntersSharedTrainSearchAndBack() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "123", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 1 })
        // Ambiguous operator (unknown candidate operator) stays for explicit
        // selection: never a silent mismatching open.
        model.openTrain(model.saved!.trains.first!.identity)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
        XCTAssertNotNil(fixture.session.shell.state.value.active.trainSearch)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .saved)
        await stop(model); await zeroCollectors(fixture)
    }

    func testJourneyHistoryRepeatReachesSharedComposerWithoutAutosubmit() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.recordJourneyHistory()
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.journeyHistory.count == 1 })
        let row = model.saved!.journeyHistory.first!
        XCTAssertEqual(row.originName, "Roma Termini")
        model.repeatJourney(row.entryId)
        XCTAssertEqual(fixture.session.shell.state.value.primaryArea, .search)
        // Repeat populates the composer for explicit execution: the shared
        // tree stays on Main (no pushed search/detail route, no autosubmit).
        XCTAssertEqual(fixture.session.shell.state.value.base.destination, .home)
        await stop(model); await zeroCollectors(fixture)
    }

    func testTrainHistoryRepeatEntersSharedTrainSearch() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.recordTrainHistory(number: "8640", serviceDate: nil, originId: "internal-station",
            originName: "Roma Termini", operatorName: "Trenitalia")
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trainHistory.count == 1 })
        let row = model.saved!.trainHistory.first!
        XCTAssertEqual(row.number, "8640")
        XCTAssertNil(row.serviceDateString)
        XCTAssertEqual(row.originId, "internal-station")
        XCTAssertEqual(row.operatorName, "Trenitalia")
        model.repeatTrain(row.entryId)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
        XCTAssertNotNil(fixture.session.shell.state.value.active.trainSearch)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .saved)
        await stop(model); await zeroCollectors(fixture)
    }

    func testHistoryOrderingRemoveAndClear() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.recordJourneyHistory()
        fixture.recordTrainHistory(number: "55", serviceDate: nil, originId: nil, originName: nil, operatorName: nil)
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.journeyHistory.count == 1 && model.saved?.trainHistory.count == 1 })
        let journeyId = model.saved!.journeyHistory.first!.entryId
        await delivery(model, matching: { model.saved?.journeyHistory.isEmpty == true }) { model.removeHistory(journeyId) }
        XCTAssertEqual(model.saved!.trainHistory.count, 1)
        await delivery(model, matching: { model.saved?.trainHistory.isEmpty == true }) { model.clearHistory() }
        XCTAssertTrue(model.saved!.savedEmpty)
        await stop(model); await zeroCollectors(fixture)
    }

    func testFavoriteFailureKeepsRetainedContent() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "123", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        fixture.stageSavedFavoriteFailure()
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 1 })
        await delivery(model, matching: { model.saved?.favoriteFailed == true }) {
            model.removeTrain(model.saved!.trains.first!.identity)
        }
        // Retained content stays visible while the failure flag shows.
        XCTAssertEqual(model.saved!.trains.count, 1)
        XCTAssertTrue(model.saved!.favoriteFailed)
        await stop(model); await zeroCollectors(fixture)
    }

    func testFavoritesObservationFailureRetainsRowsAndRetryRecovers() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "123", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 1 })
        fixture.stageFavoritesObservationFailure()
        await delivery(model, matching: { model.saved?.observationFailed == true })
        // Retained rows stay projected while the observation failure shows.
        XCTAssertEqual(model.saved!.trains.count, 1)
        XCTAssertTrue(model.saved!.hasRetainedFavorites)
        model.retryFavorites()
        await delivery(model, matching: { model.saved?.observationFailed == false })
        XCTAssertEqual(model.saved!.trains.count, 1)
        await stop(model); await zeroCollectors(fixture)
    }

    func testHistoryObservationFailureRetainsRowsAndRetryRecovers() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.recordJourneyHistory()
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.journeyHistory.count == 1 })
        fixture.stageHistoryObservationFailure()
        await delivery(model, matching: { model.saved?.historyObservationFailed == true })
        XCTAssertEqual(model.saved!.journeyHistory.count, 1)
        XCTAssertTrue(model.saved!.hasRetainedHistory)
        model.retryHistory()
        await delivery(model, matching: { model.saved?.historyObservationFailed == false })
        XCTAssertEqual(model.saved!.journeyHistory.count, 1)
        await stop(model); await zeroCollectors(fixture)
    }

    func testSimultaneousFailuresRetryOnlyTheirOwnScope() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "123", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        fixture.recordJourneyHistory()
        fixture.stageSavedFavoriteFailure()
        fixture.stageHistoryRemoveFailure()
        let model = saved(fixture)
        await delivery(model, matching: { model.saved?.trains.count == 1 && model.saved?.journeyHistory.count == 1 })
        await delivery(model, matching: { model.saved?.favoriteFailed == true }) {
            model.removeTrain(model.saved!.trains.first!.identity)
        }
        await delivery(model, matching: { model.saved?.historyFailed == true }) {
            model.removeHistory(model.saved!.journeyHistory.first!.entryId)
        }
        // History Retry clears HISTORY only; the favorite failure stays.
        fixture.clearHistoryRemoveFailure()
        await delivery(model, matching: { model.saved?.historyFailed == false }) { model.retryHistory() }
        XCTAssertTrue(model.saved!.favoriteFailed)
        // Favorites Retry clears FAVORITES only; history stays cleared.
        fixture.clearSavedFavoriteFailure()
        await delivery(model, matching: { model.saved?.favoriteFailed == false }) { model.retryFavorites() }
        XCTAssertFalse(model.saved!.historyFailed)
        await stop(model); await zeroCollectors(fixture)
    }

    func testRestoredSavedBindsNativeFacade() async {
        let fixture = NativeInteropFixture(restoredStationTrainDestination: .saved)
        defer { fixture.close() }
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .saved)
        let model = routeModel(fixture)
        await delivery(model, matching: { model.saved != nil })
        await stop(model); await zeroCollectors(fixture)
    }

    func testObserverDeallocationCancelsBothTasksAndNoRowCollectors() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        var model: NativeSavedModel? = saved(fixture)
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
        let model = saved(fixture)
        await delivery(model, matching: { model.saved != nil })
        XCTAssertEqual(constructions, 1); XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
        await stop(model); shell.close(); await zeroCollectors(fixture)
    }

    func testSavedStringsHaveNoEmptyValues() {
        for key in ["loading", "favorites", "favoriteStations", "favoriteRoutes", "favoriteTrains",
                    "journeyHistory", "trainHistory", "empty", "favoritesEmpty", "journeyHistoryEmpty",
                    "trainHistoryEmpty", "remove", "repeat", "clearHistory", "loadError", "updateError",
                    "retry", "unknownDate", "departAfter", "arriveBy", "serviceDate", "operator"] {
            XCTAssertFalse(savedString(key).isEmpty, key)
            XCTAssertFalse(savedString(key).hasPrefix("sv."), key)
        }
        XCTAssertTrue(String(format: savedString("trainNumber"), "123").contains("123"))
    }

    /// Capture harness chrome contract: the root navigation title must stay the
    /// production `shell.saved` title. Section headers live inside the content,
    /// never as the whole-screen title.
    func testCaptureHarnessUsesProductionRootTitle() {
        XCTAssertEqual(savedProductionTitleKey, "shell.saved")
        XCTAssertEqual(savedProductionTitleTable, "Shell")
        // The harness renders this exact resolved title; a raw key here means drift.
        XCTAssertEqual(savedProductionTitle(), "Saved")
    }

    func testDeterministicNativeReviewCaptures() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let empty = saved(fixture)
        await delivery(empty, matching: { empty.saved != nil })
        await showSaved(empty, name: "ios-saved-empty")
        await stop(empty)
        fixture.setFavoriteStation(favorite: true)
        fixture.stageFavoriteRoute()
        fixture.stageFavoriteTrain(number: "9410", originId: "roma-termini", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: "Milano Centrale")
        fixture.stageFavoriteTrain(number: "9410", originId: "napoli-centrale", originName: "Napoli Centrale",
            operatorName: "Italo", destinationName: "Milano Centrale")
        // Stage favorites first (history still empty) so the favorites
        // capture differs from the full root by construction.
        let favoritesOnly = saved(fixture)
        await delivery(favoritesOnly, matching: { favoritesOnly.saved?.trains.count == 2 })
        await showSaved(favoritesOnly, name: "ios-saved-favorites")
        await stop(favoritesOnly)
        fixture.recordJourneyHistory()
        fixture.recordTrainHistory(number: "9410",
            serviceDate: Kotlinx_datetimeLocalDate(year: 2026, month: 9, day: 15), originId: "roma-termini",
            originName: "Roma Termini", operatorName: "Trenitalia")
        let full = saved(fixture)
        await delivery(full, matching: { full.saved?.trains.count == 2 && full.saved?.journeyHistory.count == 1 })
        await showSaved(full, name: "ios-saved-root")
        await showSaved(full, name: "ios-saved-same-number", offset: 250)
        await showSaved(full, name: "ios-saved-journey-history", offset: 700)
        await showSaved(full, name: "ios-saved-train-history", offset: 1500)
        // Remove interaction result.
        guard let removed = full.saved?.trains.first(where: { $0.number == "9410" && $0.operatorName == "Italo" })?.identity else {
            XCTFail("capture Saved must project both same-number favorites"); return
        }
        await delivery(full, matching: { full.saved?.trains.count == 1 }) { full.removeTrain(removed) }
        await showSaved(full, name: "ios-saved-remove")
        // Journey repeat/prefill result on the shared Home composer.
        guard let journeyId = full.saved?.journeyHistory.first?.entryId else {
            XCTFail("capture Saved must project journey history"); return
        }
        full.repeatJourney(journeyId)
        let homeModel = NativeHomeModel(session: fixture.session)
        homeModel.start()
        await showContent(NativeHomeView(model: homeModel), name: "ios-saved-journey-repeat", title: "Cerca")
        if let homeTask = homeModel.close() { await homeTask.value }
        // Train repeat/prefill result on the shared Train Search surface.
        fixture.sharedSelect(area: .saved)
        let rebound = routeModel(fixture)
        await delivery(rebound, matching: { rebound.saved?.trainHistory.count == 1 })
        guard let trainRepeatId = rebound.saved?.trainHistory.first?.entryId else {
            XCTFail("capture Saved must project train history"); return
        }
        rebound.repeatTrain(trainRepeatId)
        let trainIdentity = fixture.session.shell.state.value.active.identity
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
        let trainHost = NativeStationTrainHost(session: fixture.session, identity: trainIdentity)
        await showContent(trainHost, name: "ios-saved-train-repeat", title: "Find a train")
        await stop(full); await stop(rebound)
        await zeroCollectors(fixture)
        // Only one graph attaches at a time: close the main fixture before
        // the isolated degraded leg. Degraded retained-content state: a
        // failing remove keeps the retained favorite visible with the
        // failure flagged.
        fixture.close()
        let degradedFixture = NativeInteropFixture()
        degradedFixture.stageFavoriteTrain(number: "777", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        degradedFixture.stageSavedFavoriteFailure()
        degradedFixture.sharedSelect(area: .saved)
        let degraded = NativeSavedModel(session: degradedFixture.session,
            identity: degradedFixture.session.shell.state.value.active.identity)
        degraded.start()
        await delivery(degraded, matching: { degraded.saved?.trains.count == 1 })
        guard let degradedIdentity = degraded.saved?.trains.first?.identity else {
            XCTFail("degraded Saved must project the staged favorite"); return
        }
        await delivery(degraded, matching: { degraded.saved?.favoriteFailed == true }) {
            degraded.removeTrain(degradedIdentity)
        }
        XCTAssertEqual(degraded.saved?.trains.count, 1)
        await showSaved(degraded, name: "ios-saved-degraded")
        await showSaved(degraded, name: "ios-saved-dark", dark: true)
        await showSaved(degraded, name: "ios-saved-dynamic-type", large: true)
        await stop(degraded)
        await zeroCollectors(degradedFixture)
        degradedFixture.close()
    }

    private let savedProductionTitleKey = "shell.saved"
    private let savedProductionTitleTable = "Shell"

    private func savedProductionTitle() -> String {
        NSLocalizedString(savedProductionTitleKey, tableName: savedProductionTitleTable,
            bundle: Bundle(for: NativeSavedModel.self), comment: "")
    }

    private func showSaved(_ model: NativeSavedModel, name: String, dark: Bool = false, large: Bool = false, offset: CGFloat = 0) async {
        await showContent(NativeSavedView(model: model), name: name, dark: dark, large: large,
            offset: offset, title: savedProductionTitle())
    }

    private func showContent<Content: View>(_ content: Content, name: String, dark: Bool = false, large: Bool = false, offset: CGFloat = 0, title: String) async {
        let ready = expectation(description: "native rendering layout acknowledgement")
        ready.assertForOverFulfill = false
        let window = UIWindow(windowScene: UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!)
        window.frame = CGRect(x: 0, y: 0, width: large ? 320 : 390, height: 844)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let styled = AnyView(content)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
        window.rootViewController = UIHostingController(rootView: NavigationStack {
            styled.navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
        }.environment(\.colorScheme, dark ? .dark : .light).preferredColorScheme(dark ? .dark : .light).onAppear { ready.fulfill() })
        window.makeKeyAndVisible(); await fulfillment(of: [ready], timeout: 5); window.layoutIfNeeded()
        let rendered = expectation(description: "native transaction committed")
        CATransaction.begin(); CATransaction.setCompletionBlock { rendered.fulfill() }
        window.layoutIfNeeded(); CATransaction.commit()
        await fulfillment(of: [rendered], timeout: 5)
        if offset > 0, let scroll = savedScrollView(in: window) {
            scroll.setContentOffset(CGPoint(x: 0, y: offset), animated: false)
            window.layoutIfNeeded()
        }
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        window.isHidden = true; window.rootViewController = nil
    }

    private func savedScrollView(in view: UIView) -> UIScrollView? {
        if let scroll = view as? UIScrollView { return scroll }
        return view.subviews.lazy.compactMap { self.savedScrollView(in: $0) }.first
    }

}
