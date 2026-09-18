import XCTest
import SharedApp


@MainActor
final class NativePresentationInteropTests: XCTestCase {
    private func home(_ fixture: NativeInteropFixture) -> NativeHomePresentation {
        fixture.selectHome()
        return fixture.session.state.value.main!.state.value.home!
    }

    func testInitialFeatureStateSameComponentUpdateAndMainActorHandoff() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.session.foreground()
        let observer = NativeHomeObserver(presentation: home(fixture))
        let initial = expectation(description: "SKIE initial state")
        let changed = expectation(description: "same Home component update")
        var initialDelivered = false
        observer.onUpdate = { state in
            MainActor.preconditionIsolated()
            XCTAssertTrue(Thread.isMainThread)
            if !initialDelivered { initialDelivered = true; initial.fulfill() }
            if state.favoriteStations.count == 1 { changed.fulfill() }
        }
        let creations = fixture.homeObservations
        observer.start()
        observer.start() // Idempotent: no second collector or component.
        await fulfillment(of: [initial], timeout: 5)
        XCTAssertEqual(fixture.collectorCount, 1)
        fixture.setFavoriteStation(favorite: true)
        await fulfillment(of: [changed], timeout: 5)
        XCTAssertTrue(fixture.homeMatchesSharedComponent)
        XCTAssertEqual(observer.state.favoriteStations.count, 1)
        let stationIdentity: String = NativeSemanticIdentity.shared.station(station: observer.state.favoriteStations[0])
        XCTAssertEqual(stationIdentity, "internal-station")
        XCTAssertFalse(observer.state.observationFailed)
        XCTAssertEqual(fixture.homeObservations, creations)
        XCTAssertEqual(fixture.networkRequests, 0)
        observer.openTrainSearch()
        XCTAssertEqual(fixture.session.state.value.navigation.active.destination, .trainSearch)
        fixture.session.back()
        await observer.stop()?.value
        await awaitNoCollectors(fixture)
        XCTAssertEqual(fixture.collectorCount, 0)
    }

    func testSwiftTaskCancellationStopsCollectionWithoutDomainFailure() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let presentation = home(fixture)
        let observer = NativeHomeObserver(presentation: presentation)
        let initial = expectation(description: "collector started")
        var initialDelivered = false
        observer.onUpdate = { _ in
            if !initialDelivered { initialDelivered = true; initial.fulfill() }
        }
        observer.start()
        await fulfillment(of: [initial], timeout: 5)
        observer.onUpdate = nil
        let before = observer.updateCount
        let cancelled = observer.stop()!
        await cancelled.value
        XCTAssertTrue(cancelled.isCancelled)
        await awaitNoCollectors(fixture)
        XCTAssertEqual(fixture.collectorCount, 0)
        // A fresh independent SKIE iterator is an acknowledgement barrier for the shared change.
        fixture.setFavoriteStation(favorite: true)
        for await state in presentation.state {
            if state.favoriteStations.count == 1 { break }
        }
        XCTAssertEqual(observer.updateCount, before)
        XCTAssertEqual(observer.state.favoriteStations.count, 0)
        XCTAssertFalse(presentation.state.value.observationFailed)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testSessionCloseTerminatesSuspendedNativeCollectorAndClosesGraphExactlyOnce() async {
        let fixture = NativeInteropFixture()
        fixture.session.foreground()
        fixture.session.foreground()
        XCTAssertEqual(fixture.session.phase.value, .active)
        fixture.session.background()
        fixture.session.background()
        XCTAssertEqual(fixture.session.phase.value, .inactive)
        fixture.session.foreground()
        let observer = NativeHomeObserver(presentation: home(fixture))
        let initial = expectation(description: "collector suspended after initial")
        var initialDelivered = false
        observer.onUpdate = { _ in
            if !initialDelivered { initialDelivered = true; initial.fulfill() }
        }
        observer.start()
        await fulfillment(of: [initial], timeout: 5)
        observer.onUpdate = nil
        let rootStarted = expectation(description: "root collector started")
        let rootFinished = expectation(description: "close terminates suspended root iterator")
        let homeFinished = expectation(description: "close terminates suspended Home iterator")
        let homeCollection = observer.task!
        let rootCollector = Task { @MainActor in
            for await _ in fixture.session.state { rootStarted.fulfill() }
            rootFinished.fulfill()
        }
        await fulfillment(of: [rootStarted], timeout: 5)
        XCTAssertEqual(fixture.collectorCount, 2)
        let homeCompletion = Task { @MainActor in
            await homeCollection.value
            homeFinished.fulfill()
        }
        fixture.close()
        fixture.close()
        fixture.session.foreground()
        await fulfillment(of: [rootFinished, homeFinished], timeout: 5)
        await rootCollector.value
        await homeCompletion.value
        await observer.stop()?.value
        XCTAssertEqual(fixture.session.phase.value, .closed)
        await awaitNoCollectors(fixture)
        XCTAssertEqual(fixture.collectorCount, 0)
        XCTAssertEqual(fixture.projectionCount, 0)
        XCTAssertEqual(fixture.driverCloses, 1)
        XCTAssertEqual(fixture.connectivityCloses, 1)
        XCTAssertEqual(fixture.networkRequests, 0)
        // New session can attach after the former graph detaches.
        let replacement = NativeInteropFixture()
        replacement.close()
        XCTAssertEqual(replacement.driverCloses, 1)
    }

    func testFixtureEnabledFrameworkIsLoaded() {
        // Runtime proof that the loaded framework is the fixture-enabled
        // one: the compile-time preflight in run-native-tests.sh plus this
        // check bracket the shadowing problem on both sides.
        XCTAssertNotNil(NSClassFromString("SharedAppNativeInteropFixture"))
    }

    func testRepeatedFixtureCreateUseCloseCycles() async {
        // Regression for the SQLDelight native pool close race: coordinator
        // foreground/background work runs transactions on background
        // dispatchers while close() runs on main. Closing immediately after
        // foreground, with a suspended collector, maximizes the overlap
        // window. Every cycle must close exactly once without crashing; no
        // sleep, no retry, no swallowed error — a crash fails the run.
        for cycle in 0..<12 {
            let fixture = NativeInteropFixture()
            fixture.session.foreground()
            let observer = NativeHomeObserver(presentation: home(fixture))
            let initial = expectation(description: "cycle \(cycle) initial home")
            var delivered = false
            observer.onUpdate = { _ in
                if !delivered { delivered = true; initial.fulfill() }
            }
            observer.start()
            await fulfillment(of: [initial], timeout: 5)
            observer.onUpdate = nil
            fixture.session.background()
            fixture.close()
            await observer.stop()?.value
            XCTAssertEqual(fixture.driverCloses, 1, "cycle \(cycle)")
            XCTAssertEqual(fixture.connectivityCloses, 1, "cycle \(cycle)")
            XCTAssertEqual(fixture.networkRequests, 0, "cycle \(cycle)")
            XCTAssertTrue(fixture.sameRoot, "cycle \(cycle)")
        }
    }

    func testAdapterDeallocationCancelsItsSuspendedTask() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let presentation = home(fixture)
        var observer: NativeHomeObserver? = NativeHomeObserver(presentation: presentation)
        weak var weakObserver = observer
        let initial = expectation(description: "adapter collecting")
        var initialDelivered = false
        observer!.onUpdate = { _ in
            if !initialDelivered { initialDelivered = true; initial.fulfill() }
        }
        observer!.start()
        await fulfillment(of: [initial], timeout: 5)
        let collection = observer!.task!
        observer = nil
        XCTAssertNil(weakObserver)
        await collection.value
        XCTAssertTrue(collection.isCancelled)
        await awaitNoCollectors(fixture)
        XCTAssertEqual(fixture.collectorCount, 0)
    }

    func testTypedSKIEEnumSwitchAndSharedNotificationRouting() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let main = fixture.session.state.value.main!
        let input = main.state.value.journey!.state.value.journeySearch!
        input.setDate(date: input.state.value.date)
        input.setTime(hour: 18, minute: 30)
        input.setMode(mode: .arriveBy)
        XCTAssertEqual(input.state.value.timeHour, 18)
        XCTAssertEqual(input.state.value.timeMinute, 30)
        XCTAssertEqual(input.state.value.mode, .arriveBy)
        XCTAssertFalse(input.state.value.invalid)
        main.select(area: .saved)
        XCTAssertEqual(main.state.value.selectedDestination, .favorites)
        main.select(area: .alerts)
        let destination: any PlatformNotificationDestination = PlatformNotificationDestinationStrike(strikeId: "strike-a")
        switch onEnum(of: destination) {
        case .strike(let strike): XCTAssertEqual(strike.strikeId, "strike-a")
        case .train: XCTFail("Expected the typed strike destination")
        }
        fixture.session.deliverNotificationDestination(destination: destination)
        let alerts = main.state.value.alerts!
        XCTAssertEqual(alerts.state.value.active.strikeId, "strike-a")
        XCTAssertEqual(destinationKind(alerts.state.value.active.destination), 13)
        let identity = alerts.state.value.active.identity
        fixture.session.deliverNotificationDestination(destination: PlatformNotificationDestinationStrike(strikeId: "strike-b"))
        fixture.session.deliverNotificationDestination(destination: PlatformNotificationDestinationStrike(strikeId: "strike-a"))
        XCTAssertEqual(alerts.state.value.active.identity, identity)
        XCTAssertEqual(alerts.state.value.entries.count, 3)
        alerts.back()
        XCTAssertEqual(alerts.state.value.active.strikeId, "strike-b")
    }

    /// Kotlin iterator cleanup follows Swift task completion asynchronously. Await its real
    /// acknowledgement with a bounded XCTest deadline, rather than sleeping or assuming timing.
    private func awaitNoCollectors(_ fixture: NativeInteropFixture) async {
        let stopped = expectation(description: "Kotlin collector teardown acknowledged")
        let acknowledgement = Task { @MainActor in
            for await count in fixture.collectorCounts {
                if count.int32Value == 0 { stopped.fulfill(); return }
            }
        }
        await fulfillment(of: [stopped], timeout: 5)
        acknowledgement.cancel()
        await acknowledgement.value
    }

    /// Generated SKIE Swift enum: exhaustive, with no default or enum-name comparisons.
    private func destinationKind(_ destination: NativeDestination) -> Int {
        switch destination {
        case .main: return 0
        case .home: return 1
        case .trainSearch: return 2
        case .trainDetail: return 3
        case .stationSearch: return 4
        case .stationBoard: return 5
        case .journeySearch: return 6
        case .journeyResults: return 7
        case .journeyDetail: return 8
        case .history: return 9
        case .monitoring: return 10
        case .saved: return 11
        case .alertsOverview: return 12
        case .strikeDetail: return 13
        case .settings: return 14
        }
    }
}
