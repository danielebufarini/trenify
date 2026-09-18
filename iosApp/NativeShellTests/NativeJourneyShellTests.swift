import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

/// T8.6 native Journey models, formatting and review captures. Production
/// models only, through the accepted SKIE boundary; one session/root per
/// fixture. No repository/provider construction in Swift.
@MainActor
final class NativeJourneyShellTests: XCTestCase {
    // MARK: - Helpers

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
        if predicate(model.state) { return }
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

    private func show(_ model: NativeShellModel, dark: Bool, width: CGFloat = 390, typeSize: DynamicTypeSize? = nil) async -> UIWindow {
        let appeared = expectation(description: "Native shell rendering appeared")
        appeared.assertForOverFulfill = false
        let window = UIWindow(windowScene: windowScene())
        window.frame = CGRect(x: 0, y: 0, width: width, height: 844)
        let base = NativeAppShell(model: model).environment(\.colorScheme, dark ? .dark : .light)
        if let typeSize {
            window.rootViewController = UIHostingController(rootView: base.environment(\.dynamicTypeSize, typeSize)
                .onAppear { appeared.fulfill() })
        } else {
            window.rootViewController = UIHostingController(rootView: base.onAppear { appeared.fulfill() })
        }
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)
        window.layoutIfNeeded()
        return window
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

    /// Drives a valid search through the production facades and selects the
    /// Journey branch, like NativeHomeModel.search() does.
    private func searchToResults(_ fixture: NativeInteropFixture) async {
        fixture.selectHome()
        let input = fixture.session.state.value.main!.state.value.journey!.state.value.journeySearch!
        input.stationText(origin: true, text: "Roma")
        await poll("origin suggestions") { !input.state.value.suggestions.isEmpty }
        input.selectStation(station: input.state.value.suggestions.first(where: { $0.name == "Roma Termini" })!)
        input.stationText(origin: false, text: "Milano")
        await poll("destination suggestions") { !input.state.value.suggestions.isEmpty }
        input.selectStation(station: input.state.value.suggestions.first(where: { $0.name == "Milano Centrale" })!)
        input.search()
        XCTAssertFalse(input.state.value.invalid)
        fixture.session.state.value.main!.state.value.home!.openJourneySearch()
        await poll("results above home") {
            fixture.session.shell.state.value.path.contains(where: { $0.destination == .journeyResults })
        }
    }

    private func resultsIdentity(_ fixture: NativeInteropFixture) -> Int64 {
        fixture.session.shell.state.value.path.first(where: { $0.destination == .journeyResults })!.identity
    }

    private func startedResults(_ fixture: NativeInteropFixture) async -> NativeJourneyResultsModel {
        let model = NativeJourneyResultsModel(session: fixture.session, identity: resultsIdentity(fixture))
        let ready = expectation(description: "results model initial delivery")
        ready.assertForOverFulfill = false
        model.onUpdate = { if model.state != nil { ready.fulfill() } }
        model.start()
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
        return model
    }

    // MARK: - Formatting (pure, no fixture)

    func testRailwayFormattingRomeTimesDurationAndTones() {
        // 2026-09-05 14:10 Europe/Rome (CEST) renders independent of device zone.
        XCTAssertEqual(RailwayFormatting.time(1788610200), "14:10")
        XCTAssertEqual(RailwayFormatting.time(1788621540), "17:19")
        XCTAssertEqual(RailwayFormatting.duration(minutes: 189), "3h 09m")
        XCTAssertEqual(RailwayFormatting.duration(minutes: 45), "45m")
        XCTAssertEqual(RailwayFormatting.statusTone(status: .running, delayMinutes: KotlinInt(value: 15)), .delayed)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .running, delayMinutes: KotlinInt(value: 0)), .onTime)
        // A null delay never implies on-time.
        XCTAssertEqual(RailwayFormatting.statusTone(status: .running, delayMinutes: nil), .unknown)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .notDeparted, delayMinutes: nil), .unknown)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .cancelled, delayMinutes: nil), .cancelled)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .partiallyCancelled, delayMinutes: nil), .cancelled)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .arrived, delayMinutes: nil), .arrived)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .diverted, delayMinutes: nil), .warning)
        XCTAssertEqual(RailwayFormatting.statusTone(status: .unknown, delayMinutes: nil), .unknown)
    }

    func testJourneyCardSummaryKeepsDurationAndStructureInline() {
        XCTAssertEqual(journeyCardSummaryLabel(duration: "13m", structure: "Direct"), "13m · Direct")
        XCTAssertEqual(
            journeyCardSummaryLabel(duration: "45m", structure: "1 change · via Bologna Centrale"),
            "45m · 1 change · via Bologna Centrale"
        )
    }

    // MARK: - Results model

    func testResultsModelObservesLiveFacadeAndForwardsActions() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        await searchToResults(fixture)
        let model = await startedResults(fixture)
        defer { _ = model.close() }
        XCTAssertEqual(model.state?.originName, "Roma Termini")
        XCTAssertEqual(model.state?.destinationName, "Milano Centrale")
        XCTAssertEqual(model.state?.journeys.count, 1)
        XCTAssertEqual(model.state?.sort, .departure)
        // A second start is idempotent: no duplicate collectors.
        model.start()
        try? await Task.sleep(nanoseconds: 300_000_000)
        let collectorsAfterRestart = fixture.collectorCount
        // Sort forwards through the shared component and arrives via SKIE.
        model.setSort(.duration)
        await poll("sort reaches model") { model.state?.sort == .duration }
        XCTAssertEqual(model.state?.journeys.count, 1)
        // Selection drives the SAME shared stack to Detail (no second router).
        model.select(index: 0)
        await poll("detail above results") {
            fixture.session.shell.state.value.path.contains(where: { $0.destination == .journeyDetail })
        }
        // Back returns to the same Results identity with content intact.
        model.back()
        await poll("back to results") {
            fixture.session.shell.state.value.active.destination == .journeyResults
        }
        XCTAssertEqual(model.state?.journeys.count, 1)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
        XCTAssertLessThanOrEqual(fixture.collectorCount, collectorsAfterRestart + 1)
    }

    func testResultsModelCloseCancelsCollectorsAndIgnoresActions() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        await searchToResults(fixture)
        let model = await startedResults(fixture)
        _ = model.close()
        // Closed models never act: no navigation follows.
        model.select(index: 0)
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(fixture.session.shell.state.value.active.destination, .journeyResults)
        await noCollectors(fixture)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    // MARK: - Detail model

    func testDetailModelObservesLegsBookingAndTrainAction() async {
        let fixture = NativeInteropFixture(bookingHandoffEnabled: true)
        defer { fixture.close() }
        fixture.selectHome()
        fixture.stageJourneyCorrelatedRun(status: .running, delayMinutes: KotlinInt(value: 12))
        await searchToResults(fixture)
        let shell = fixture.session.shell
        shell.state.value.path.first(where: { $0.destination == .journeyResults })!.journeyResults!.selectJourney(index: 0)
        await poll("detail above results") {
            shell.state.value.path.contains(where: { $0.destination == .journeyDetail })
        }
        let identity = shell.state.value.path.first(where: { $0.destination == .journeyDetail })!.identity
        let model = NativeJourneyDetailModel(session: fixture.session, identity: identity)
        let ready = expectation(description: "detail model initial delivery")
        ready.assertForOverFulfill = false
        model.onUpdate = { if model.state != nil { ready.fulfill() } }
        model.start()
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
        defer { _ = model.close() }
        await poll("correlated enrichment") {
            model.state?.legs.first?.realtimeStatus == .running
        }
        XCTAssertEqual(model.state?.legs.first?.delayMinutes?.intValue, 12)
        XCTAssertEqual(model.state?.legs.first?.hasTrainAction, true)
        // Default fake journey sources are genuinely unknown: unknown
        // stays unknown, never inferred from the operator.
        XCTAssertNil(model.state?.journeySourceNames)
        XCTAssertEqual(model.state?.bookingOperatorName, "Trenitalia")
        // Booking forwards through the shared policy; the test opener accepts.
        model.buy()
        await poll("booking settles") { model.state?.bookingInProgress == false }
        XCTAssertEqual(model.state?.bookingFailed, false)
        // T8.7: the same shared root now exposes native Train Detail.
        model.openTrain(legIndex: 0)
        await poll("native train detail") {
            fixture.session.state.value.navigation.active.destination == .trainDetail
        }
        XCTAssertNotNil(shell.state.value.active.trainDetail)
        // Shared Back returns to the originating native Journey Detail.
        model.back()
        await poll("back to detail") { shell.state.value.active.destination == .journeyDetail }
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testDetailModelFavoriteFailureKeepsContent() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        // Staged failure: the toggle reports failed instead of inventing
        // optimistic state (restored T7-era behavior).
        fixture.stageRouteFavoriteFailure()
        await searchToResults(fixture)
        let shell = fixture.session.shell
        shell.state.value.path.first(where: { $0.destination == .journeyResults })!.journeyResults!.selectJourney(index: 0)
        await poll("detail above results") {
            shell.state.value.path.contains(where: { $0.destination == .journeyDetail })
        }
        let identity = shell.state.value.path.first(where: { $0.destination == .journeyDetail })!.identity
        let model = NativeJourneyDetailModel(session: fixture.session, identity: identity)
        let ready = expectation(description: "detail model initial delivery")
        ready.assertForOverFulfill = false
        model.onUpdate = { if model.state != nil { ready.fulfill() } }
        model.start()
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
        defer { _ = model.close() }
        await poll("favorite offered") { model.favoriteRoute?.available == true }
        XCTAssertEqual(model.favoriteRoute?.failed, false)
        model.toggleFavoriteRoute()
        await poll("favorite failure visible") { model.favoriteRoute?.failed == true }
        // Content stays intact: legs still render, nothing favorited.
        XCTAssertEqual(model.favoriteRoute?.favorite, false)
        XCTAssertEqual(model.state?.legs.count, 1)
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testDetailModelExposesJourneySourceAttribution() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        // One genuine known provider: Detail must surface the shared
        // Journey source set through SKIE.
        fixture.stageKnownJourneySources()
        await searchToResults(fixture)
        let shell = fixture.session.shell
        shell.state.value.path.first(where: { $0.destination == .journeyResults })!.journeyResults!.selectJourney(index: 0)
        await poll("detail above results") {
            shell.state.value.path.contains(where: { $0.destination == .journeyDetail })
        }
        let identity = shell.state.value.path.first(where: { $0.destination == .journeyDetail })!.identity
        let model = NativeJourneyDetailModel(session: fixture.session, identity: identity)
        let ready = expectation(description: "detail model initial delivery")
        ready.assertForOverFulfill = false
        model.onUpdate = { if model.state != nil { ready.fulfill() } }
        model.start()
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil
        defer { _ = model.close() }
        await poll("sources exposed") { model.state?.journeySourceNames == "ViaggiaTreno" }
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testHomeResultsBackHomeKeepsOneSessionRoot() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let shellModel = NativeShellModel { fixture.session }
        defer { _ = shellModel.close() }
        shellModel.start()
        fixture.selectHome()
        await searchToResults(fixture)
        await poll("results active") { shellModel.state.active.destination == .journeyResults }
        shellModel.back()
        await poll("home active") { shellModel.state.active.destination == .home }
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
        // The native journey path renders through SwiftUI only.
    }

    // MARK: - Review captures (deterministic, window-hosted)

    func testNativeJourneyReviewCapturesResults() async {
        // Direct Results, light.
        do {
            let fixture = NativeInteropFixture()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: { $0.active.destination == .journeyResults }) {}
            let window = await show(model, dark: false)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-results-direct")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Transfer Results with interchange information, light.
        do {
            let fixture = NativeInteropFixture()
            fixture.stageTransferJourney()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: {
                $0.active.destination == .journeyResults && ($0.active.journeyResults?.state.value.journeys.first?.changes ?? 0) > 0
            }) {}
            let window = await show(model, dark: false)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-results-transfer")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Disrupted Results: strike warning card, light.
        do {
            let fixture = NativeInteropFixture()
            fixture.stageStrikeWarning()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: {
                $0.active.destination == .journeyResults && ($0.active.journeyResults?.state.value.journeys.first?.warningCount ?? 0) > 0
            }) {}
            let window = await show(model, dark: false)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-results-warning")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Empty Results, light.
        do {
            let fixture = NativeInteropFixture()
            fixture.stageEmptyJourneyResult()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: {
                $0.active.destination == .journeyResults && ($0.active.journeyResults?.state.value.empty ?? false)
            }) {}
            let window = await show(model, dark: false)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-results-empty")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Provider failure without content, light.
        do {
            let fixture = NativeInteropFixture()
            fixture.stageJourneyFailure()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: {
                $0.active.destination == .journeyResults && ($0.active.journeyResults?.state.value.failure != nil)
            }) {}
            let window = await show(model, dark: false)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-results-error")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
    }

    func testNativeJourneyReviewCapturesDetail() async {
        // Journey Detail with delayed realtime enrichment, light.
        do {
            let fixture = NativeInteropFixture(bookingHandoffEnabled: true)
            fixture.stageJourneyCorrelatedRun(status: .running, delayMinutes: KotlinInt(value: 15))
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: { $0.active.destination == .journeyResults }) {}
            model.state.active.journeyResults!.selectJourney(index: 0)
            await update(model, matching: {
                $0.active.destination == .journeyDetail && ($0.active.journeyDetail?.state.value.legs.first?.delayMinutes?.intValue ?? -1) == 15
            }) {}
            let window = await show(model, dark: false)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-detail")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Journey Detail, dark.
        do {
            let fixture = NativeInteropFixture(bookingHandoffEnabled: true)
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: { $0.active.destination == .journeyResults }) {}
            model.state.active.journeyResults!.selectJourney(index: 0)
            await update(model, matching: { $0.active.destination == .journeyDetail }) {}
            let window = await show(model, dark: true)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-detail-dark")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
        // Journey Detail, narrow width with enlarged type (no fixed-height clipping).
        do {
            let fixture = NativeInteropFixture()
            let model = NativeShellModel { fixture.session }
            model.select(.search)
            model.start()
            await searchToResults(fixture)
            await update(model, matching: { $0.active.destination == .journeyResults }) {}
            model.state.active.journeyResults!.selectJourney(index: 0)
            await update(model, matching: { $0.active.destination == .journeyDetail }) {}
            let window = await show(model, dark: false, width: 320, typeSize: .accessibility2)
            window.layoutIfNeeded()
            capture(window, name: "ios-journey-detail-narrow")
            window.isHidden = true
            window.rootViewController = nil
            await model.close()?.value
            await noCollectors(fixture)
            XCTAssertEqual(fixture.networkRequests, 0)
        }
    }
}
