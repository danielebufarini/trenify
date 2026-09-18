import XCTest
@preconcurrency import SharedApp

@MainActor
final class NativeStationTrainInteropTests: XCTestCase {
    private func collect<T: AnyObject>(_ states: SkieSwiftStateFlow<T>, matching: @escaping (T) -> Bool, action: () -> Void) async {
        let ready = expectation(description: "typed SKIE projection delivery")
        let observer = Task { @MainActor in
            for await value in states { if matching(value) { ready.fulfill(); return } }
        }
        action(); await fulfillment(of: [ready], timeout: 5)
        observer.cancel(); await observer.value
    }
    func testStationBoardFacadeDirectionProvenanceAndNativeTrainDestination() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageStationResults(); fixture.stageStationBoard(direction: .departures, disrupted: false)
        fixture.session.state.value.main!.state.value.home!.openStations()
        let search = fixture.session.shell.state.value.active.stationSearch!
        await collect(search.state, matching: { $0.results.count == 1 }) { search.editQuery(query: "Roma") }
        XCTAssertEqual(search.state.value.results.first!.stationId, "internal-station")
        search.selectStation(stationId: "internal-station")
        let board = fixture.session.shell.state.value.active.stationBoard!
        await collect(board.state, matching: { $0.direction == .arrivals }) { board.setDirection(direction: .arrivals) }
        fixture.stageStationBoard(direction: .arrivals, disrupted: true)
        XCTAssertEqual(board.state.value.trains.first!.providerName, "ViaggiaTreno")
        XCTAssertEqual(board.state.value.trains.last!.status, .cancelled)
        XCTAssertNotNil(board.state.value.observation.provenance.sourceTimestampEpochSeconds)
        let exact = board.state.value.trains.first!.identity.key
        board.openTrain(key: exact)
        let detail = fixture.session.shell.state.value.active.trainDetail!
        XCTAssertEqual(detail.state.value.identity.key, exact)
        await collect(detail.state, matching: { $0.stops.count == 5 }) { fixture.stageTrainDetail(status: .running, delayMinutes: KotlinInt(value: 12), stale: true, unknown: false) }
        XCTAssertEqual(detail.state.value.observation.provenance.providerName, "ViaggiaTreno")
        XCTAssertEqual(detail.state.value.summary?.providerName, "ViaggiaTreno")
        XCTAssertNotNil(detail.state.value.observation.provenance.fetchedAtEpochSeconds)
        XCTAssertNotNil(detail.state.value.observation.provenance.sourceTimestampEpochSeconds)
        fixture.session.shell.back(); XCTAssertEqual(fixture.session.shell.state.value.active.destination, .stationBoard)
        XCTAssertEqual(fixture.networkRequests, 0)
    }
    func testTrainPickerRunIdentityDetailProgressAndFavoriteFailureThroughSKIE() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageTrainRuns(multiple: true)
        fixture.session.state.value.main!.state.value.home!.openTrainSearch()
        let search = fixture.session.shell.state.value.active.trainSearch!
        await collect(search.state, matching: { $0.runs.count == 2 }) { search.editNumber(number: "123"); search.submit() }
        let runs = search.state.value.runs
        XCTAssertNotEqual(runs[0].identity.key, runs[1].identity.key)
        search.selectRun(key: runs[1].identity.key)
        let detail = fixture.session.shell.state.value.active.trainDetail!
        await collect(detail.state, matching: { $0.stops.count == 5 }) { fixture.stageTrainDetail(status: .running, delayMinutes: KotlinInt(value: 12), stale: true, unknown: false) }
        XCTAssertEqual(detail.state.value.identity.key, runs[1].identity.key)
        XCTAssertEqual(detail.state.value.stops.map(\.progress), [.completed, .completed, .next, .future, .future])
        XCTAssertEqual(detail.state.value.observation.freshness, .stale)
        fixture.stageTrainFavoriteFailure()
        await collect(detail.state, matching: { $0.favorite.failed }) { detail.toggleFavorite() }
        XCTAssertNotNil(detail.state.value.summary)
        fixture.session.shell.back(); XCTAssertEqual(fixture.session.shell.state.value.active.trainSearch!.state.value.runs.count, 2)
        XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
    }
}
