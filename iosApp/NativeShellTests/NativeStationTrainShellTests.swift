import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

@MainActor
final class NativeStationTrainShellTests: XCTestCase {
    func testTrainDetailPlatformPresentationIsStopScopedAndTechnicalMetadataIsHidden() {
        XCTAssertTrue(StationTrainDetailPresentationPolicy.hidesTechnicalMetadata)
        XCTAssertEqual(stationTrainStopPlatformLines(scheduled: "5", actual: nil), [.expected("5")])
        XCTAssertEqual(stationTrainStopPlatformLines(scheduled: "5", actual: "7"), [.expected("5"), .actual("7")])
        XCTAssertEqual(stationTrainStopPlatformLines(scheduled: nil, actual: nil), [])
    }

    func testTrainDetailPlatformLabelsInterpolateLocalizedFormatStrings() {
        let expected = stationTrainPlatformLabel("expectedPlatform", value: "5")
        let actual = stationTrainPlatformLabel("actualPlatform", value: "7")

        XCTAssertFalse(expected.contains("%@"))
        XCTAssertFalse(actual.contains("%@"))
        XCTAssertTrue(["Expected platform: 5", "Binario previsto: 5"].contains(expected))
        XCTAssertTrue(["Actual platform: 7", "Binario effettivo: 7"].contains(actual))
    }

    private func delivery(_ model: NativeStationTrainModel, matching: @escaping () -> Bool, action: () -> Void = {}) async {
        let ready = expectation(description: "screen-level SKIE acknowledgement")
        ready.assertForOverFulfill = false
        model.onUpdate = { if matching() { ready.fulfill() } }
        action()
        if matching() { ready.fulfill() }
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
    }
    private func routeModel(_ fixture: NativeInteropFixture) -> NativeStationTrainModel {
        let model = NativeStationTrainModel(session: fixture.session, identity: fixture.session.shell.state.value.active.identity)
        model.start(); model.start()
        return model
    }
    private func station(_ fixture: NativeInteropFixture) -> NativeStationTrainModel {
        fixture.session.state.value.main!.state.value.home!.openStations()
        return routeModel(fixture)
    }
    private func trainSearch(_ fixture: NativeInteropFixture) -> NativeStationTrainModel {
        fixture.session.state.value.main!.state.value.home!.openTrainSearch()
        return routeModel(fixture)
    }
    private func stop(_ model: NativeStationTrainModel) async {
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

    func testStationCollectionStableIdentityUserBindingAndFailures() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = station(fixture)
        fixture.stageStationResults()
        await delivery(model, matching: { model.stationSearch?.results.count == 1 }) { model.editQuery("Roma") }
        let row = model.stationSearch!.results.first!
        XCTAssertEqual(row.stationId, "internal-station")
        let facadeTask = model.facadeTask
        model.start(); model.editQuery("Roma")
        XCTAssertNotNil(facadeTask); XCTAssertFalse(model.stationSearch!.observation.loading)
        fixture.stageStationFailure()
        await delivery(model, matching: { model.stationSearch?.observation.failure == .offline }) { model.editQuery("Milano") }
        XCTAssertTrue(model.stationSearch!.results.isEmpty)
        await stop(model); await zeroCollectors(fixture)
    }

    func testCachedRecentStationsRemainActionableAcrossObservationFailure() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageRecentStation()
        let model = station(fixture)
        await delivery(model, matching: { model.stationSearch?.recent.count == 1 })
        await delivery(model, matching: {
            model.stationSearch?.recencyFailed == true && model.stationSearch?.recent.count == 1
        }) { fixture.failRecentObservation() }
        XCTAssertEqual(model.stationSearch?.recent.first?.stationId, "internal-station")
        model.selectStation("internal-station")
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .stationBoard)
        await stop(model); await zeroCollectors(fixture)
    }

    func testRecentFailureClearsAfterSuccessfulRecovery() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageRecentStation()
        let model = station(fixture)
        await delivery(model, matching: { model.stationSearch?.recent.count == 1 })
        fixture.stageRecentFailure(failed: true)
        await delivery(model, matching: { model.stationSearch?.recencyFailed == true }) { model.clearRecent() }
        fixture.stageRecentFailure(failed: false)
        await delivery(model, matching: {
            model.stationSearch?.recencyFailed == false && model.stationSearch?.recent.isEmpty == true
        }) { model.clearRecent() }
        XCTAssertFalse(model.stationSearch!.recencyFailed)
        XCTAssertTrue(model.stationSearch!.recent.isEmpty)
        await stop(model); await zeroCollectors(fixture)
    }

    func testEmptyRecentFailureShowsFailureWithoutInventingRows() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        let model = station(fixture)
        await delivery(model, matching: { model.stationSearch?.recencyFailed == true }) {
            fixture.failRecentObservation()
        }
        XCTAssertTrue(model.stationSearch!.recencyFailed)
        XCTAssertTrue(model.stationSearch!.recent.isEmpty)
        await stop(model); await zeroCollectors(fixture)
    }

    func testStationBoardBothDirectionsExactTrainAndSharedBackChain() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageStationResults(); fixture.stageStationBoard(direction: .departures, disrupted: false)
        let search = station(fixture)
        await delivery(search, matching: { search.stationSearch?.results.count == 1 }) { search.editQuery("Roma") }
        search.selectStation("internal-station")
        let board = routeModel(fixture)
        await delivery(board, matching: { board.stationBoard?.station != nil })
        XCTAssertEqual(board.stationBoard!.direction, .departures)
        let boardIdentity = fixture.session.shell.state.value.active.identity
        await delivery(board, matching: { board.stationBoard?.direction == .arrivals }) { board.setDirection(.arrivals) }
        fixture.stageStationBoard(direction: .arrivals, disrupted: true)
        await delivery(board, matching: { board.stationBoard?.trains.count == 2 })
        XCTAssertEqual(board.stationBoard!.trains.last!.status, .cancelled)
        XCTAssertEqual(board.stationBoard!.trains.first!.providerName, "ViaggiaTreno")
        XCTAssertEqual(board.stationBoard!.observation.provenance.sourceTimestampEpochSeconds?.int64Value, fixture.stationTrainAt.epochSeconds - 60)
        let run = board.stationBoard!.trains.first!.identity.key
        board.openBoardTrain(run)
        let detail = routeModel(fixture)
        await delivery(detail, matching: { detail.trainDetail != nil })
        XCTAssertEqual(detail.trainDetail!.identity.key, run)
        await delivery(detail, matching: { detail.trainDetail?.stops.count == 5 }) {
            fixture.stageTrainDetail(status: .running, delayMinutes: KotlinInt(value: 12), stale: true, unknown: false)
        }
        XCTAssertEqual(detail.trainDetail!.observation.provenance.providerName, "ViaggiaTreno")
        XCTAssertEqual(detail.trainDetail!.summary?.providerName, "ViaggiaTreno")
        XCTAssertNotNil(detail.trainDetail!.observation.provenance.fetchedAtEpochSeconds)
        XCTAssertNotNil(detail.trainDetail!.observation.provenance.sourceTimestampEpochSeconds)
        detail.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.identity, boardIdentity)
        board.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .stationSearch)
        search.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .home)
        XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
        await stop(detail); await stop(board); await stop(search); await zeroCollectors(fixture)
    }

    func testTrainSearchMultiRunExactSelectionDetailAndBackToRetainedPicker() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageTrainRuns(multiple: true)
        let search = trainSearch(fixture)
        await delivery(search, matching: { search.trainSearch?.runs.count == 2 }) { search.editNumber("123"); search.submitTrainSearch() }
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
        let runs = search.trainSearch!.runs
        XCTAssertNotEqual(runs[0].identity.key, runs[1].identity.key)
        XCTAssertNotEqual(runs[0].originId, runs[1].originId)
        XCTAssertNotEqual(runs[0].operatorName, runs[1].operatorName)
        search.selectRun(runs[1].identity.key)
        let detail = routeModel(fixture)
        await delivery(detail, matching: { detail.trainDetail != nil })
        XCTAssertEqual(detail.trainDetail!.identity.key, runs[1].identity.key)
        detail.back()
        XCTAssertEqual(fixture.session.shell.state.value.active.identity, search.identity)
        XCTAssertEqual(search.trainSearch!.runs.count, 2)
        search.back(); XCTAssertEqual(fixture.session.shell.state.value.active.destination, .home)
        await stop(detail); await stop(search); await zeroCollectors(fixture)
    }

    func testOneUnambiguousRunUsesExistingDirectNavigation() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageTrainRuns(multiple: false)
        let search = trainSearch(fixture)
        search.editNumber("123"); search.submitTrainSearch()
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainDetail)
        XCTAssertNotNil(fixture.session.shell.state.value.active.trainDetail)
        await stop(search); await zeroCollectors(fixture)
    }

    func testDetailProgressProvenanceFavoriteFailureMonitoringAndTerminalGate() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageTrainRuns(multiple: false)
        let search = trainSearch(fixture); search.editNumber("123"); search.submitTrainSearch()
        let detail = routeModel(fixture)
        await delivery(detail, matching: { detail.trainDetail?.stops.count == 5 }) {
            fixture.stageTrainDetail(status: .running, delayMinutes: KotlinInt(value: 12), stale: true, unknown: false)
        }
        let state = detail.trainDetail!
        XCTAssertEqual(state.stops.map(\.progress), [.completed, .completed, .next, .future, .future])
        XCTAssertEqual(Set(state.stops.map(\.key)).count, 5)
        XCTAssertEqual(state.observation.freshness, .stale)
        XCTAssertEqual(state.observation.provenance.fetchedAtEpochSeconds?.int64Value, fixture.stationTrainAt.epochSeconds + 4200)
        XCTAssertEqual(state.observation.provenance.sourceTimestampEpochSeconds?.int64Value, fixture.stationTrainAt.epochSeconds + 4140)
        XCTAssertNotNil(state.positionObservedAtEpochSeconds)
        fixture.stageTrainFavoriteFailure()
        await delivery(detail, matching: { detail.trainDetail?.favorite.failed == true }) { detail.favoriteTrain() }
        XCTAssertNotNil(detail.trainDetail!.summary)
        await delivery(detail, matching: { detail.trainDetail?.monitored == true }) { detail.toggleMonitoring() }
        await delivery(detail, matching: { detail.trainDetail?.monitorNotificationsEnabled?.boolValue == false }) { detail.setMonitorNotifications(false) }
        await delivery(detail, matching: { detail.trainDetail?.notifyDelay == false }) { detail.setMonitorEvent(.delay, false) }
        await delivery(detail, matching: { detail.trainDetail?.monitorThresholdText == "18" }) { detail.editThreshold("18"); detail.saveThreshold() }
        await delivery(detail, matching: { detail.trainDetail?.ended == true }) { fixture.stageEndedTrainMonitor() }
        let refreshes = fixture.trainRefreshes
        detail.refreshTrain(); detail.toggleMonitoring(); detail.setMonitorNotifications(true)
        XCTAssertEqual(fixture.trainRefreshes, refreshes)
        XCTAssertFalse(detail.trainDetail!.canRefresh); XCTAssertFalse(detail.trainDetail!.canToggleMonitoring)
        XCTAssertTrue(detail.trainDetail!.ended)
        await stop(detail); await stop(search); await zeroCollectors(fixture)
    }

    func testUnknownStatusTimestampsAndNoSecondSessionOrLocalRouter() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        var constructions = 0
        let shell = NativeShellModel { constructions += 1; return fixture.session }
        shell.start(); shell.start()
        let search = trainSearch(fixture)
        search.editNumber("123"); search.submitTrainSearch()
        let detail = routeModel(fixture)
        await delivery(detail, matching: { detail.trainDetail?.summary?.status == .unknown }) {
            fixture.stageTrainDetail(status: .unknown, delayMinutes: nil, stale: false, unknown: true)
        }
        XCTAssertNil(detail.trainDetail!.summary!.delayMinutes)
        XCTAssertNil(detail.trainDetail!.observation.provenance.fetchedAtEpochSeconds)
        XCTAssertNil(detail.trainDetail!.observation.provenance.sourceTimestampEpochSeconds)
        XCTAssertEqual(detail.trainDetail!.observation.freshness, .unknown)
        XCTAssertTrue(detail.trainDetail!.observation.provenance.stale)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .unknown, delayMinutes: nil), .unknown)
        XCTAssertEqual(constructions, 1); XCTAssertTrue(fixture.sameRoot)
        // Authoritative path-prefix control reaches shared Back, never pushes native routes.
        let path = fixture.session.shell.state.value.path
        fixture.session.shell.requestPath(area: .search, identities: path.dropLast().map { KotlinLong(value: $0.identity) }, expectedIdentities: path.map { KotlinLong(value: $0.identity) })
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
        await stop(detail); await stop(search); shell.close(); await zeroCollectors(fixture)
    }

    func testRestoredSharedStationAndTrainRoutesBindNativeFacadesAndBack() async {
        for destination in [NativeDestination.stationSearch, .stationBoard, .trainSearch, .trainDetail] {
            let fixture = NativeInteropFixture(restoredStationTrainDestination: destination)
            let model = routeModel(fixture)
            XCTAssertEqual(fixture.session.shell.state.value.active.destination, destination)
            if destination == .stationSearch { XCTAssertEqual(model.stationSearch?.query, "Roma") }
            if destination == .stationBoard {
                await delivery(model, matching: { model.stationBoard?.station != nil })
                XCTAssertEqual(model.stationBoard?.stationId, "internal-station")
                XCTAssertEqual(model.stationBoard?.direction, .arrivals)
                model.back(); XCTAssertEqual(fixture.session.shell.state.value.active.destination, .stationSearch)
            }
            if destination == .trainSearch {
                XCTAssertEqual(model.trainSearch?.number, "123")
                XCTAssertEqual(model.trainSearch?.serviceDate, "2026-09-05")
                XCTAssertEqual(model.trainSearch?.expectedOriginId, "internal-station")
                XCTAssertEqual(model.trainSearch?.expectedOperatorName, "Trenitalia")
            }
            if destination == .trainDetail {
                XCTAssertEqual(model.trainDetail?.identity.number, "123")
                model.back(); XCTAssertEqual(fixture.session.shell.state.value.active.destination, .trainSearch)
            }
            XCTAssertTrue(fixture.sameRoot); XCTAssertEqual(fixture.networkRequests, 0)
            await stop(model); await zeroCollectors(fixture); fixture.close()
        }
    }

    func testObserverDeallocationCancelsBothTasksAndNoRowCollectors() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        var model: NativeStationTrainModel? = station(fixture)
        weak var weakModel = model
        let ready = expectation(description: "two screen observers only")
        let counts = Task { @MainActor in
            for await count in fixture.collectorCounts { if count.int32Value == 2 { ready.fulfill(); return } }
        }
        await fulfillment(of: [ready], timeout: 5); counts.cancel(); await counts.value
        model = nil; XCTAssertNil(weakModel)
        await zeroCollectors(fixture)
    }

    private func show(_ model: NativeStationTrainModel, name: String, dark: Bool = false, large: Bool = false, offset: CGFloat = 0) async {
        let ready = expectation(description: "native rendering layout acknowledgement")
        ready.assertForOverFulfill = false
        let window = UIWindow(windowScene: UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!)
        window.frame = CGRect(x: 0, y: 0, width: large ? 320 : 390, height: 844)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let content = NativeStationTrainView(model: model)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
        window.rootViewController = UIHostingController(rootView: NavigationStack {
            content.navigationTitle(stationTrainString(model.stationSearch != nil ? "stationSearch" : model.stationBoard != nil ? "stationBoard" : model.trainSearch != nil ? "trainSearch" : "trainDetail"))
                .navigationBarTitleDisplayMode(.inline)
        }.environment(\.colorScheme, dark ? .dark : .light).preferredColorScheme(dark ? .dark : .light).onAppear { ready.fulfill() })
        window.makeKeyAndVisible(); await fulfillment(of: [ready], timeout: 5); window.layoutIfNeeded()
        let rendered = expectation(description: "native transaction committed")
        CATransaction.begin(); CATransaction.setCompletionBlock { rendered.fulfill() }
        window.layoutIfNeeded(); CATransaction.commit()
        await fulfillment(of: [rendered], timeout: 5)
        if offset > 0, let scroll = scrollView(in: window) {
            scroll.setContentOffset(CGPoint(x: 0, y: offset), animated: false)
            window.layoutIfNeeded()
        }
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        window.isHidden = true; window.rootViewController = nil
    }

    private func scrollView(in view: UIView) -> UIScrollView? {
        if let scroll = view as? UIScrollView { return scroll }
        return view.subviews.lazy.compactMap { self.scrollView(in: $0) }.first
    }

    func testDeterministicNativeReviewCaptures() async {
        let fixture = NativeInteropFixture(); defer { fixture.close() }
        fixture.stageStationResults(); fixture.stageStationBoard(direction: .departures, disrupted: false)
        let search = station(fixture)
        await delivery(search, matching: { search.stationSearch?.results.count == 1 }) { search.editQuery("Roma") }
        await show(search, name: "ios-station-search")
        search.selectStation("internal-station")
        let board = routeModel(fixture); await delivery(board, matching: { board.stationBoard?.station != nil })
        await show(board, name: "ios-board-departures")
        await delivery(board, matching: { board.stationBoard?.direction == .arrivals }) { board.setDirection(.arrivals) }
        fixture.stageStationBoard(direction: .arrivals, disrupted: false)
        await show(board, name: "ios-board-arrivals")
        await delivery(board, matching: { board.stationBoard?.trains.count == 2 }) { fixture.stageStationBoard(direction: .arrivals, disrupted: true) }
        await show(board, name: "ios-board-disruption", offset: 140)
        board.back(); search.back()
        let train = trainSearch(fixture)
        await show(train, name: "ios-train-search")
        fixture.stageTrainRuns(multiple: true)
        await delivery(train, matching: { train.trainSearch?.runs.count == 2 }) { train.editNumber("123"); train.submitTrainSearch() }
        await show(train, name: "ios-train-picker", offset: 150)
        train.selectRun(train.trainSearch!.runs.first!.identity.key)
        let detail = routeModel(fixture)
        await delivery(detail, matching: { detail.trainDetail?.stops.count == 5 }) { fixture.stageTrainDetail(status: .running, delayMinutes: KotlinInt(value: 0), stale: false, unknown: false) }
        await show(detail, name: "ios-train-normal")
        await delivery(detail, matching: { detail.trainDetail?.summary?.delayMinutes?.int32Value == 12 }) { fixture.stageTrainDetail(status: .running, delayMinutes: KotlinInt(value: 12), stale: true, unknown: false) }
        await show(detail, name: "ios-train-delayed")
        await show(detail, name: "ios-train-route", offset: 730)
        await show(detail, name: "ios-train-dark", dark: true)
        await show(detail, name: "ios-train-large-text", large: true)
        await delivery(detail, matching: { detail.trainDetail?.summary?.status == .cancelled }) { fixture.stageTrainDetail(status: .cancelled, delayMinutes: nil, stale: false, unknown: false) }
        await show(detail, name: "ios-train-cancelled")
        await delivery(detail, matching: { detail.trainDetail?.summary?.status == .unknown }) { fixture.stageTrainDetail(status: .unknown, delayMinutes: nil, stale: false, unknown: true) }
        await show(detail, name: "ios-train-unknown")
        fixture.stageTrainFavoriteFailure()
        await delivery(detail, matching: { detail.trainDetail?.favorite.failed == true }) { detail.favoriteTrain() }
        await show(detail, name: "ios-train-favorite-failure", offset: 170)
        XCTAssertEqual(fixture.networkRequests, 0)
        await stop(detail); await stop(train); await stop(board); await stop(search); await zeroCollectors(fixture)
    }
}
