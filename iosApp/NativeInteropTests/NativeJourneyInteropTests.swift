import XCTest
@preconcurrency import SharedApp

/// T8.6 Journey Results/Detail SKIE verification through the accepted
/// facades and shell entries. No Combine, no model wrapper, no repository
/// construction in Swift. One session/root throughout.
@MainActor
final class NativeJourneyInteropTests: XCTestCase {
    private func journeyInput(_ fixture: NativeInteropFixture) -> NativeJourneySearchPresentation {
        fixture.selectHome()
        return fixture.session.state.value.main!.state.value.journey!.state.value.journeySearch!
    }

    /// Drives a valid search through the production facade actions and
    /// selects the Journey branch, like NativeHomeModel.search() does.
    @discardableResult
    private func searchToResults(_ fixture: NativeInteropFixture) async -> Bool {
        let input = journeyInput(fixture)
        input.stationText(origin: true, text: "Roma")
        try? await Task.sleep(nanoseconds: 500_000_000)
        guard let roma = input.state.value.suggestions.first(where: { $0.name == "Roma Termini" }) else { return false }
        input.selectStation(station: roma)
        input.stationText(origin: false, text: "Milano")
        try? await Task.sleep(nanoseconds: 500_000_000)
        guard let milano = input.state.value.suggestions.first(where: { $0.name == "Milano Centrale" }) else { return false }
        input.selectStation(station: milano)
        input.search()
        if input.state.value.invalid { return false }
        fixture.session.state.value.main!.state.value.home!.openJourneySearch()
        for _ in 0..<100 {
            if fixture.session.shell.state.value.path.contains(where: { $0.destination == .journeyResults }) { return true }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return false
    }

    func testInitialResultsStateThroughSkie() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let searched = await searchToResults(fixture)
        XCTAssertTrue(searched)
        let entry = fixture.session.shell.state.value.path.first(where: { $0.destination == .journeyResults })!
        XCTAssertTrue(entry.available)
        let results = entry.journeyResults!
        XCTAssertNil(entry.journeyDetail)
        let state = results.state.value
        XCTAssertEqual(state.originName, "Roma Termini")
        XCTAssertEqual(state.destinationName, "Milano Centrale")
        XCTAssertFalse(state.loading)
        XCTAssertNil(state.failure)
        XCTAssertTrue(state.hasContent)
        XCTAssertFalse(state.empty)
        XCTAssertEqual(state.sort, .departure)
        XCTAssertEqual(state.journeys.count, 1)
        let card = state.journeys.first!
        XCTAssertEqual(card.index, 0)
        XCTAssertEqual(card.changes, 0)
        XCTAssertEqual(card.trainIdentities, ["Frecciarossa 123"])
        XCTAssertEqual(card.operatorNames, ["Trenitalia"])
        XCTAssertEqual(card.legs.count, 1)
        XCTAssertEqual(card.legs.first!.trainIdentity, "Frecciarossa 123")
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testResultsStateUpdatesThroughSkieSequence() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let searched = await searchToResults(fixture)
        XCTAssertTrue(searched)
        let results = fixture.session.shell.state.value.path.first(where: { $0.destination == .journeyResults })!.journeyResults!
        let states: SkieSwiftStateFlow<NativeJourneyResultsState> = results.state
        let updated = expectation(description: "sort reaches Swift")
        updated.assertForOverFulfill = false
        let collection = Task { @MainActor in
            for await next in states {
                if next.sort == .duration { updated.fulfill(); return }
            }
        }
        results.setSort(sort: .duration)
        await fulfillment(of: [updated], timeout: 5)
        collection.cancel()
        await collection.value
        XCTAssertEqual(results.state.value.sort, .duration)
        XCTAssertEqual(results.state.value.journeys.count, 1)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testResultsDetailBackThroughSharedStack() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let searched = await searchToResults(fixture)
        XCTAssertTrue(searched)
        let shell = fixture.session.shell
        let resultsEntry = shell.state.value.path.first(where: { $0.destination == .journeyResults })!
        let resultsIdentity = resultsEntry.identity
        resultsEntry.journeyResults!.selectJourney(index: 0)
        for _ in 0..<100 {
            if shell.state.value.path.contains(where: { $0.destination == .journeyDetail }) { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        let detailEntry = shell.state.value.path.first(where: { $0.destination == .journeyDetail })!
        XCTAssertNil(detailEntry.journeyResults)
        let detail = detailEntry.journeyDetail!
        XCTAssertFalse(detail.state.value.resolving)
        XCTAssertFalse(detail.state.value.notFound)
        XCTAssertEqual(detail.state.value.originName, "Roma Termini")
        XCTAssertEqual(detail.state.value.legs.count, 1)
        XCTAssertEqual(detail.state.value.legs.first!.trainIdentity, "Frecciarossa 123")
        // Booking stays the secondary shared-policy action; the operator
        // name comes from the same policy the component uses for availability.
        XCTAssertEqual(detail.state.value.bookingAvailable, detail.state.value.bookingOperatorName != nil)
        // Back returns to the same Results entry; Back again to native Home.
        shell.back()
        for _ in 0..<100 {
            if shell.state.value.active.destination == .journeyResults { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(shell.state.value.active.destination, .journeyResults)
        XCTAssertEqual(shell.state.value.active.identity, resultsIdentity)
        XCTAssertEqual(shell.state.value.active.journeyResults?.state.value.journeys.count, 1)
        shell.back()
        for _ in 0..<100 {
            if shell.state.value.active.destination == .home { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(shell.state.value.active.destination, .home)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testTrainActionRoutesToNativeTrainDetail() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        // Stage the correlated run before opening the detail, like production
        // observes it: operator-carrying summary plus exact scheduled stops.
        fixture.stageJourneyCorrelatedRun(status: .running, delayMinutes: nil)
        let searched = await searchToResults(fixture)
        XCTAssertTrue(searched)
        let shell = fixture.session.shell
        shell.state.value.path.first(where: { $0.destination == .journeyResults })!.journeyResults!.selectJourney(index: 0)
        for _ in 0..<100 {
            if shell.state.value.path.contains(where: { $0.destination == .journeyDetail }) { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        let detail = shell.state.value.path.first(where: { $0.destination == .journeyDetail })!.journeyDetail!
        // The correlated fixture run resolves the train action (T8.7 owns Train Detail).
        for _ in 0..<100 {
            if detail.state.value.legs.first?.hasTrainAction == true { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(detail.state.value.legs.first?.hasTrainAction, true)
        detail.openTrain(legIndex: 0)
        for _ in 0..<100 {
            if fixture.session.state.value.navigation.active.destination == .trainDetail { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(fixture.session.state.value.navigation.active.destination, .trainDetail)
        // Back from the train route returns to the native Detail.
        fixture.session.back()
        for _ in 0..<100 {
            if shell.state.value.active.destination == .journeyDetail { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(shell.state.value.active.destination, .journeyDetail)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testBookingActionForwardsThroughSharedPolicy() async {
        // Production booking wiring (real OpenBookingLink policy, test URL
        // opener): the approved Trenitalia target resolves to Opened.
        let fixture = NativeInteropFixture(bookingHandoffEnabled: true)
        defer { fixture.close() }
        let searched = await searchToResults(fixture)
        XCTAssertTrue(searched)
        let shell = fixture.session.shell
        shell.state.value.path.first(where: { $0.destination == .journeyResults })!.journeyResults!.selectJourney(index: 0)
        for _ in 0..<100 {
            if shell.state.value.path.contains(where: { $0.destination == .journeyDetail }) { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        let detail = shell.state.value.path.first(where: { $0.destination == .journeyDetail })!.journeyDetail!
        XCTAssertTrue(detail.state.value.bookingAvailable)
        XCTAssertEqual(detail.state.value.bookingOperatorName, "Trenitalia")
        // The test URL opener accepts the approved target: the shared handoff
        // settles to Opened without hanging, and the UI stays actionable.
        detail.buy()
        for _ in 0..<100 {
            if !detail.state.value.bookingInProgress { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertFalse(detail.state.value.bookingInProgress)
        XCTAssertFalse(detail.state.value.bookingFailed)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testJourneyObserverCancellationAndDeallocation() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let searched = await searchToResults(fixture)
        XCTAssertTrue(searched)
        let results = fixture.session.shell.state.value.path.first(where: { $0.destination == .journeyResults })!.journeyResults!
        let states: SkieSwiftStateFlow<NativeJourneyResultsState> = results.state
        let initial = expectation(description: "results SKIE collecting")
        var delivered = false
        let collection = Task { @MainActor in
            for await _ in states {
                if !delivered { delivered = true; initial.fulfill() }
            }
        }
        await fulfillment(of: [initial], timeout: 5)
        collection.cancel()
        await collection.value
        XCTAssertTrue(collection.isCancelled)
        let collectors = expectation(description: "Kotlin teardown acknowledged")
        let acknowledgement = Task { @MainActor in
            for await count in fixture.collectorCounts {
                if count.int32Value == 0 { collectors.fulfill(); return }
            }
        }
        // Leaving the flow back to Home drops every journey facade collector.
        fixture.session.shell.back()
        await fulfillment(of: [collectors], timeout: 5)
        acknowledgement.cancel()
        await acknowledgement.value
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }
}
