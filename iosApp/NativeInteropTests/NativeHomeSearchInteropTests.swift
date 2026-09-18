import XCTest
@preconcurrency import SharedApp

/// T8.5 Home/Search SKIE verification using the accepted facades directly.
/// No Combine, no model wrapper, no repository construction in Swift.
@MainActor
final class NativeHomeSearchInteropTests: XCTestCase {
    private func home(_ fixture: NativeInteropFixture) -> NativeHomePresentation {
        fixture.selectHome()
        return fixture.session.state.value.main!.state.value.home!
    }

    private func journeyInput(_ fixture: NativeInteropFixture) -> NativeJourneySearchPresentation {
        fixture.session.state.value.main!.state.value.journey!.state.value.journeySearch!
    }

    func testSwapExchangesComposedTripTexts() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let input = journeyInput(fixture)
        input.stationText(origin: true, text: "Roma")
        input.stationText(origin: false, text: "Milano")
        XCTAssertEqual(input.state.value.originText, "Roma")
        XCTAssertEqual(input.state.value.destinationText, "Milano")
        input.swap()
        XCTAssertEqual(input.state.value.originText, "Milano")
        XCTAssertEqual(input.state.value.destinationText, "Roma")
        input.swap()
        XCTAssertEqual(input.state.value.originText, "Roma")
        XCTAssertEqual(input.state.value.destinationText, "Milano")
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testTypedDateTimeInvalidSubmitAndNoDuplicateNavigation() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let input = journeyInput(fixture)
        input.setDate(date: Kotlinx_datetimeLocalDate(year: 2026, month: 9, day: 15))
        input.setTime(hour: 18, minute: 30)
        XCTAssertEqual(input.state.value.date, Kotlinx_datetimeLocalDate(year: 2026, month: 9, day: 15))
        XCTAssertEqual(input.state.value.timeHour, 18)
        XCTAssertEqual(input.state.value.timeMinute, 30)
        input.setMode(mode: .arriveBy)
        XCTAssertEqual(input.state.value.mode, .arriveBy)
        // Empty endpoints are invalid; submission stays on Home with the flag.
        input.stationText(origin: true, text: "")
        input.stationText(origin: false, text: "")
        input.search()
        XCTAssertTrue(input.state.value.invalid)
        XCTAssertEqual(fixture.session.state.value.navigation.active.destination, .main)
        // A second invalid submission does not navigate or duplicate.
        input.search()
        XCTAssertEqual(fixture.session.state.value.navigation.active.destination, .main)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testHomeEntriesRouteToNativeEntriesAndBackToSameHome() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let presentation = home(fixture)
        let before = fixture.session.state.value.main!
        presentation.openTrainSearch()
        XCTAssertEqual(fixture.session.state.value.navigation.active.destination, .trainSearch)
        fixture.session.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .home)
        presentation.openStations()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .stationSearch)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .home)
        // Selecting Stations destroys non-adjacent Home; re-borrow the fresh
        // facade like production NativeHomeModel does on main-state updates.
        let refreshed = home(fixture)
        refreshed.openHistory()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .history)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .home)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertTrue(fixture.session.state.value.main! === before)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testJourneyCancellationLeavesNoStaleObserver() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let input = journeyInput(fixture)
        let states: SkieSwiftStateFlow<NativeJourneyInputState> = input.state
        let initial = expectation(description: "journey SKIE collecting")
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
        await fulfillment(of: [collectors], timeout: 5)
        acknowledgement.cancel()
        await acknowledgement.value
        XCTAssertEqual(fixture.networkRequests, 0)
    }
}
