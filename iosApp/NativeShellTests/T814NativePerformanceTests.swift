import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

/// T8.14-R5 bounded native iOS performance evidence (simulator).
///
/// No invented thresholds: every case asserts functional completion and
/// attaches a `T8.14-IOS-PERF` median/range summary for continuous
/// comparison. Simulator evidence only — no physical-device claim.
/// Glass conclusion (source-audited): the only custom glass in production is
/// the system `.glass`/`.glassProminent` button style behind the pure
/// `TrenifyActionMaterialPolicy` gate; `testGlassPolicyGating` pins the gate.
@MainActor
final class T814NativePerformanceTests: XCTestCase {
    private func summary(name: String, rows: Int, samples: [Double], extra: String = "") -> String {
        let sorted = samples.sorted()
        return "T8.14-IOS-PERF \(name) rows=\(rows) " +
            "median=\(String(format: "%.2f", sorted[sorted.count / 2]))ms " +
            "min=\(String(format: "%.2f", sorted.first!))ms " +
            "max=\(String(format: "%.2f", sorted.last!))ms n=\(sorted.count)\(extra)"
    }

    private func attach(_ text: String, name: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    // MARK: - Journey Results production render (T8.14-R3)

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

    private func windowScene() -> UIWindowScene {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!
    }

    private func findScroll(in view: UIView) -> UIScrollView? {
        if let scroll = view as? UIScrollView { return scroll }
        for sub in view.subviews { if let found = findScroll(in: sub) { return found } }
        return nil
    }

    /// Renders the real production Journey Results view with 200 deterministic
    /// journeys end to end (fixture graph -> shared facade -> model -> SwiftUI),
    /// times repeated host/layout/draw passes, scrolls past the first viewport
    /// through the real UIScrollView and proves late content renders. The screen
    /// also exercises the glass-styled refresh/sort buttons (R3 §6 sanity).
    func testJourneyResults200RenderAndScroll() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.stageManyJourneys(count: 200)
        // Drive a real search through production facades to the Results destination.
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
        let identity = fixture.session.shell.state.value.path
            .first(where: { $0.destination == .journeyResults })!.identity
        let model = NativeJourneyResultsModel(session: fixture.session, identity: identity)
        let ready = expectation(description: "results 200 delivery")
        ready.assertForOverFulfill = false
        model.onUpdate = { if (model.state?.journeys.count ?? 0) == 200 { ready.fulfill() } }
        model.start()
        await fulfillment(of: [ready], timeout: 10)
        model.onUpdate = nil
        // Data proof: the production model holds all 200 journeys.
        XCTAssertEqual(model.state?.journeys.count, 200)

        func render() -> UIWindow {
            let window = UIWindow(windowScene: windowScene())
            window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
            window.rootViewController = UIHostingController(rootView: NativeJourneyResultsView(model: model))
            window.makeKeyAndVisible()
            window.layoutIfNeeded()
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            return window
        }
        func timed() -> Double {
            let start = DispatchTime.now().uptimeNanoseconds
            _ = render()
            return Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000.0
        }
        _ = timed()
        _ = timed()
        let samples = (0..<5).map { _ in timed() }
        // Scroll past the first viewport and prove late content renders: the
        // list must extend beyond the viewport and reach the bottom offset.
        let window = render()
        guard let scroll = findScroll(in: window) else {
            XCTFail("production journey view has no UIScrollView")
            return
        }
        window.layoutIfNeeded()
        XCTAssertGreaterThan(scroll.contentSize.height, scroll.bounds.height,
                             "200 cards must extend beyond the first viewport")
        scroll.setContentOffset(
            CGPoint(x: 0, y: scroll.contentSize.height - scroll.bounds.height), animated: false)
        window.layoutIfNeeded()
        XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        XCTAssertGreaterThan(scroll.contentOffset.y, 0, "must reach content past the first viewport")
        let shot = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let capture = XCTAttachment(image: shot)
        capture.name = "t814-journey-200-scrolled-bottom"
        capture.lifetime = .keepAlways
        add(capture)
        let text = summary(name: "journeyResultsRender", rows: 200, samples: samples,
                           extra: " host+layout+draw; glass-styled refresh/sort rendered")
        attach(text, name: "t814-ios-journey-render")
        model.close()
    }

    // MARK: - Liquid Glass gate (audit executable part)

    func testGlassPolicyGating() {
        // Baseline iOS 26 device without accessibility adaptations: glass.
        XCTAssertTrue(TrenifyActionMaterialPolicy.usesGlass(osMajor: 26, reduceTransparency: false, increasedContrast: false))
        // Reduce Transparency and Increased Contrast always fall back to opaque bordered styles.
        XCTAssertFalse(TrenifyActionMaterialPolicy.usesGlass(osMajor: 26, reduceTransparency: true, increasedContrast: false))
        XCTAssertFalse(TrenifyActionMaterialPolicy.usesGlass(osMajor: 26, reduceTransparency: false, increasedContrast: true))
        XCTAssertFalse(TrenifyActionMaterialPolicy.usesGlass(osMajor: 26, reduceTransparency: true, increasedContrast: true))
        // Pre-26 OS versions never take the glass branch (formality gate on the iOS 26+ baseline).
        XCTAssertFalse(TrenifyActionMaterialPolicy.usesGlass(osMajor: 18, reduceTransparency: false, increasedContrast: false))
    }

    // MARK: - Row formatting hot path (T8.14-C2 production path)

    func testRowFormatting500() {
        let epochs: [Int64] = (0..<500).map { 1_788_610_200 + Int64($0 * 120) }
        func once() -> Double {
            let start = DispatchTime.now().uptimeNanoseconds
            var sink = 0
            for epoch in epochs {
                sink += RailwayFormatting.time(epoch).count
                sink += RailwayFormatting.date(epoch).count
                sink += RailwayFormatting.dateTime(epoch).count
            }
            XCTAssertGreaterThan(sink, 0)
            return Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000.0
        }
        for _ in 0..<3 { once() }
        let samples = (0..<11).map { _ in once() }
        // Spot-check formatting correctness on the cached path.
        XCTAssertEqual(RailwayFormatting.time(1_788_610_200), "14:10")
        let text = summary(name: "rowFormatting", rows: 500, samples: samples)
        attach(text, name: "t814-ios-row-formatting")
    }

    // MARK: - Shell navigation observation cycles

    func testShellNavigationCycles() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        let model = NativeShellModel { fixture.session }
        defer { model.close() }
        model.start()
        let areas: [NativePrimaryArea] = [.monitoring, .saved, .alerts, .search]
        func cycle() async {
            for area in areas {
                if model.state.primaryArea == area { continue }
                let changed = expectation(description: "shell ack \(area)")
                changed.assertForOverFulfill = false
                model.onUpdate = { if $0.primaryArea == area { changed.fulfill() } }
                model.select(area)
                await fulfillment(of: [changed], timeout: 5)
                model.onUpdate = nil
            }
        }
        await cycle()
        await cycle()
        var samples: [Double] = []
        for _ in 0..<8 {
            let start = DispatchTime.now().uptimeNanoseconds
            await cycle()
            samples.append(Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000.0)
        }
        XCTAssertEqual(model.state.primaryArea, .search)
        XCTAssertTrue(fixture.sameRoot)
        let text = summary(name: "shellNavigationCycle", rows: areas.count, samples: samples, extra: " cycle=4-tab-round-trip")
        attach(text, name: "t814-ios-shell-navigation")
    }

    // MARK: - Route observation lifecycle (push/pop equivalent)

    func testRouteObservationLifecycle20() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.sharedSelect(area: .monitoring)
        let identity = fixture.session.shell.state.value.active.identity
        func open() async {
            let model = NativeMonitoringModel(session: fixture.session, identity: identity)
            model.start()
            model.start()
            let ready = expectation(description: "route ack")
            ready.assertForOverFulfill = false
            model.onUpdate = { if model.monitoring != nil { ready.fulfill() } }
            if model.monitoring != nil { ready.fulfill() }
            await fulfillment(of: [ready], timeout: 5)
            model.onUpdate = nil
            XCTAssertNotNil(model.monitoring)
            for task in model.close() { await task.value }
        }
        await open()
        let start = DispatchTime.now().uptimeNanoseconds
        for _ in 0..<20 { await open() }
        let totalMs = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000.0
        // No accumulation: Kotlin collectors return to zero after the cycles.
        let drained = expectation(description: "collectors drained")
        let watch = Task { @MainActor in
            for await count in fixture.collectorCounts {
                if count.int32Value == 0 { drained.fulfill(); return }
            }
        }
        await fulfillment(of: [drained], timeout: 5)
        watch.cancel()
        await watch.value
        let text = "T8.14-IOS-PERF routeObservationLifecycle cycles=20 " +
            "total=\(String(format: "%.1f", totalMs))ms " +
            "perCycle=\(String(format: "%.2f", totalMs / 20))ms collectorsDrained=true"
        attach(text, name: "t814-ios-route-lifecycle")
    }
}
