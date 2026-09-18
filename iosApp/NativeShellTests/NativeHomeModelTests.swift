import XCTest
import SwiftUI
@preconcurrency import SharedApp

/// Actual NativeHomeModel tests (T8.5 corrective): the production model
/// object, not the bare facades. All observation runs on the main actor
/// through the accepted SKIE boundary; one session/root throughout.
@MainActor
class HomeModelTestCase: XCTestCase {
    func startedModel(_ fixture: NativeInteropFixture) async -> NativeHomeModel {
        let model = NativeHomeModel(session: fixture.session)
        let ready = expectation(description: "home model initial delivery")
        ready.assertForOverFulfill = false
        model.onUpdate = {
            if model.homeState != nil && model.journeyInput != nil { ready.fulfill() }
        }
        model.start()
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
        return model
    }

    func awaitCondition(_ description: String, _ predicate: @escaping () -> Bool) async {
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

    func awaitCollectors(_ fixture: NativeInteropFixture, _ count: Int32) async {
        await awaitCondition("collectors reach \(count)") { fixture.collectorCount == count }
    }

    /// Drives suggestion selection through the production model actions and
    /// returns both endpoints resolved.
    @discardableResult
    func selectBothSuggestions(_ model: NativeHomeModel) async -> Bool {
        model.stationText(origin: true, text: "Roma")
        await awaitCondition("origin suggestions") { !(model.journeyInput?.suggestions.isEmpty ?? true) }
        guard let roma = model.journeyInput?.suggestions.first(where: { $0.name == "Roma Termini" }) else { return false }
        model.selectStation(roma)
        await awaitCondition("origin resolved") { model.journeyInput?.origin != nil }
        model.stationText(origin: false, text: "Milano")
        await awaitCondition("destination suggestions") { !(model.journeyInput?.suggestions.isEmpty ?? true) }
        guard let milano = model.journeyInput?.suggestions.first(where: { $0.name == "Milano Centrale" }) else { return false }
        model.selectStation(milano)
        await awaitCondition("destination resolved") { model.journeyInput?.destination != nil }
        return true
    }
}

@MainActor
final class NativeHomeModelLifecycleTests: HomeModelTestCase {
    func testStartCollectsHomeAndJourneyExactlyOnce() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let model = await startedModel(fixture)
        defer { _ = model.close() }
        XCTAssertNotNil(model.homeState)
        XCTAssertNotNil(model.journeyInput)
        // Main + Home + Journey-nav + Journey + route-favorite collectors.
        await awaitCollectors(fixture, 5)
        // A second start is idempotent: no duplicate collectors.
        model.start()
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(fixture.collectorCount, 5)

        // 1. Subsequent Home state changes arrive.
        let homed = expectation(description: "home favorite reaches Swift")
        homed.assertForOverFulfill = false
        model.onUpdate = {
            if model.homeState?.favoriteStations.count == 1 { homed.fulfill() }
        }
        fixture.setFavoriteStation(favorite: true)
        await fulfillment(of: [homed], timeout: 5)
        model.onUpdate = nil
        XCTAssertEqual(model.homeState?.favoriteStations.count, 1)

        // 2. Subsequent Journey input changes arrive.
        let journeyed = expectation(description: "journey text reaches Swift")
        journeyed.assertForOverFulfill = false
        model.onUpdate = {
            if model.journeyInput?.originText == "Roma" { journeyed.fulfill() }
        }
        model.stationText(origin: true, text: "Roma")
        await fulfillment(of: [journeyed], timeout: 5)
        model.onUpdate = nil
        XCTAssertEqual(model.journeyInput?.originText, "Roma")
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testResultsBackReconnectsLiveSearchFacade() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let model = await startedModel(fixture)
        defer { _ = model.close() }

        let selected = await selectBothSuggestions(model)
        XCTAssertTrue(selected)
        XCTAssertEqual(model.journeyInput?.origin?.name, "Roma Termini")
        XCTAssertEqual(model.journeyInput?.destination?.name, "Milano Centrale")

        // 3. Valid Search pushes Results above native Home; the Search facade
        // closes while Results is active.
        model.search()
        await awaitCondition("results above home") {
            fixture.session.shell.state.value.active.destination == .journeyResults
        }
        XCTAssertNil(model.journeyInput)

        // Back reconnects a fresh facade over the retained Search component.
        fixture.session.shell.back()
        await awaitCondition("search reconnected") { model.journeyInput != nil }
        XCTAssertEqual(model.journeyInput?.origin?.name, "Roma Termini")
        XCTAssertEqual(model.journeyInput?.destination?.name, "Milano Centrale")

        // 4. Actions still work after returning to Home.
        model.setMode(.arriveBy)
        await awaitCondition("mode applied") { model.journeyInput?.mode == .arriveBy }
        model.swap()
        await awaitCondition("swap applied") { model.journeyInput?.origin?.name == "Milano Centrale" }
        XCTAssertEqual(model.journeyInput?.destination?.name, "Roma Termini")
        await awaitCollectors(fixture, 5)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testCloseCancelsEveryTaskAndDeallocates() async {
        let fixture = NativeInteropFixture()
        fixture.selectHome()
        var model: NativeHomeModel? = await startedModel(fixture)
        weak var weakModel = model
        await awaitCollectors(fixture, 5)
        // 5. Teardown drains every collector to zero.
        let cancelled = model?.close()
        await cancelled?.value
        await awaitCollectors(fixture, 0)
        XCTAssertNil(model?.close())
        model = nil
        XCTAssertNil(weakModel)
        XCTAssertTrue(fixture.sameRoot)
        fixture.close()
    }
}

@MainActor
final class NativeHomeComposerTests: HomeModelTestCase {
    func testTypingClearsSelectionKeepsSwapAndPrefill() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let model = await startedModel(fixture)
        defer { _ = model.close() }

        // Free typing clears the resolved endpoint as intended.
        model.stationText(origin: true, text: "Roma")
        await awaitCondition("typed") { model.journeyInput?.originText == "Roma" }
        XCTAssertNil(model.journeyInput?.origin)

        // Selecting a suggestion leaves the Station resolved.
        await awaitCondition("suggestions") { !(model.journeyInput?.suggestions.isEmpty ?? true) }
        let roma = model.journeyInput!.suggestions.first(where: { $0.name == "Roma Termini" })!
        model.selectStation(roma)
        await awaitCondition("origin resolved") { model.journeyInput?.origin != nil }
        XCTAssertEqual(model.journeyInput?.originText, "Roma Termini")

        model.stationText(origin: false, text: "Milano")
        await awaitCondition("destination suggestions") { !(model.journeyInput?.suggestions.isEmpty ?? true) }
        let milano = model.journeyInput!.suggestions.first(where: { $0.name == "Milano Centrale" })!
        model.selectStation(milano)
        await awaitCondition("destination resolved") { model.journeyInput?.destination != nil }

        // Swap preserves both resolved endpoint identities.
        model.swap()
        await awaitCondition("swapped") { model.journeyInput?.origin?.name == "Milano Centrale" }
        XCTAssertEqual(model.journeyInput?.destination?.name, "Roma Termini")
        model.swap()
        await awaitCondition("swapped back") { model.journeyInput?.origin?.name == "Roma Termini" }

        // Search after selecting both suggestions is valid: Results appears
        // above native Home through the production search() method.
        model.search()
        await awaitCondition("results above home") {
            fixture.session.shell.state.value.active.destination == .journeyResults
        }
        fixture.session.shell.back()
        await awaitCondition("back home") {
            fixture.session.shell.state.value.active.destination == .home
        }

        // History repeat prefills resolved stations (full recorded request).
        await awaitCondition("history recorded") { !(model.homeState?.recentSearches.isEmpty ?? true) }
        model.openRecent(model.homeState!.recentSearches[0])
        await awaitCondition("prefill resolved") { model.journeyInput?.origin != nil }
        XCTAssertEqual(model.journeyInput?.origin?.name, "Roma Termini")
        XCTAssertEqual(model.journeyInput?.destination?.name, "Milano Centrale")
        XCTAssertEqual(model.journeyInput?.invalid, false)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testInvalidSubmitStaysHomeViaProductionMethod() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let model = await startedModel(fixture)
        defer { _ = model.close() }

        // Empty endpoints are invalid; the production search() must not move
        // shared navigation, must not record history, and must flag invalid.
        model.search()
        await awaitCondition("invalid flagged") { model.journeyInput?.invalid == true }
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .home)
        XCTAssertEqual(fixture.session.state.value.main!.state.value.selectedDestination, .home)
        XCTAssertEqual(fixture.session.state.value.main!.state.value.journey!.state.value.active.destination, .journeySearch)
        XCTAssertTrue(model.homeState?.recentSearches.isEmpty ?? false)
        // A repeat invalid submission still navigates nowhere.
        model.search()
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .home)
        XCTAssertEqual(fixture.session.state.value.main!.state.value.selectedDestination, .home)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }
}

@MainActor
final class NativeHomeFailureTests: HomeModelTestCase {
    func testLookupFailureKeepsEnteredTextAndReportsFailure() async {
        let fixture = NativeInteropFixture(failingJourneyStations: true, failingHomeAggregates: false)
        defer { fixture.close() }
        fixture.selectHome()
        let model = await startedModel(fixture)
        defer { _ = model.close() }

        // The lookup fails, but the failure never hides the entered text:
        // the view keeps rendering the field next to home-search-error.
        model.stationText(origin: true, text: "Roma")
        await awaitCondition("lookup failure published") { model.journeyInput?.failure != nil }
        XCTAssertEqual(model.journeyInput?.originText, "Roma")
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testObservationFailureKeepsCachedRecentVisible() async {
        let fixture = NativeInteropFixture(failingJourneyStations: false, failingHomeAggregates: true)
        defer { fixture.close() }
        fixture.selectHome()
        let model = await startedModel(fixture)
        defer { _ = model.close() }

        // One cached row arrives before the failure; the failure flag must
        // never drop it: the view keeps rendering it next to home-load-error.
        await awaitCondition("observation failed") { model.homeState?.observationFailed == true }
        XCTAssertEqual(model.homeState?.recentSearches.count, 1)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }
}
