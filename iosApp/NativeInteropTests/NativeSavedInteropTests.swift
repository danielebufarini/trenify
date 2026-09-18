import XCTest
@preconcurrency import SharedApp

@MainActor
final class NativeSavedInteropTests: XCTestCase {
    private func collect<T: AnyObject>(_ states: SkieSwiftStateFlow<T>, matching: @escaping (T) -> Bool, action: () -> Void) async {
        let ready = expectation(description: "typed SKIE projection delivery")
        let observer = Task { @MainActor in
            for await value in states { if matching(value) { ready.fulfill(); return } }
        }
        action(); await fulfillment(of: [ready], timeout: 5)
        observer.cancel(); await observer.value
    }
    func testSavedFacadeSectionsIdentityAndActionsThroughSKIE() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.setFavoriteStation(favorite: true)
        fixture.stageFavoriteRoute()
        fixture.stageFavoriteTrain(number: "9410", originId: "roma-termini", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: "Milano Centrale")
        fixture.stageFavoriteTrain(number: "9410", originId: "napoli-centrale", originName: "Napoli Centrale",
            operatorName: "Italo", destinationName: "Milano Centrale")
        fixture.recordJourneyHistory()
        fixture.recordTrainHistory(number: "8640", serviceDate: nil, originId: "internal-station",
            originName: "Roma Termini", operatorName: "Trenitalia")
        fixture.sharedSelect(area: .saved)
        let saved = fixture.session.shell.state.value.active.saved!
        await collect(saved.state, matching: {
            $0.trains.count == 2 && $0.journeyHistory.count == 1 && $0.trainHistory.count == 1
        }) {}
        let state = saved.state.value
        XCTAssertEqual(state.stations.count, 1)
        XCTAssertEqual(state.routes.count, 1)
        // Same-number trains stay distinguishable by shared semantic identity.
        XCTAssertEqual(Set(state.trains.map { $0.identity }).count, 2)
        XCTAssertTrue(state.trains.allSatisfy { $0.canOpen })
        // Journey repeat preserves the recorded request through shared routing.
        saved.repeatJourney(entryId: state.journeyHistory.first!.entryId)
        XCTAssertEqual(fixture.session.shell.state.value.primaryArea, .search)
        fixture.sharedSelect(area: .saved)
        let rebound = fixture.session.shell.state.value.active.saved!
        await collect(rebound.state, matching: { $0.trainHistory.count == 1 }) {}
        // Train repeat enters the shared Train Search flow with discrimination.
        rebound.repeatTrain(entryId: rebound.state.value.trainHistory.first!.entryId)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
        fixture.session.shell.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .saved)
        let current = fixture.session.shell.state.value.active.saved!
        await collect(current.state, matching: { $0.trains.count == 1 }) {
            current.removeTrain(identity: state.trains.first!.identity)
        }
        XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
    }
    func testSavedFavoriteOpenCarriesDiscriminationThroughSKIE() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageFavoriteTrain(number: "123", originId: "internal-station", originName: "Roma Termini",
            operatorName: "Trenitalia", destinationName: nil)
        fixture.sharedSelect(area: .saved)
        let saved = fixture.session.shell.state.value.active.saved!
        await collect(saved.state, matching: { $0.trains.count == 1 }) {}
        saved.openTrain(identity: saved.state.value.trains.first!.identity)
        // Fresh discriminated lookup, never a silent mismatching open.
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
        XCTAssertNotNil(fixture.session.shell.state.value.active.trainSearch)
        XCTAssertEqual(fixture.networkRequests, 0)
    }
}
