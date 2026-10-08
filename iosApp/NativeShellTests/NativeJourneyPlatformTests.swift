import XCTest
import SwiftUI
import UIKit
import Vision
@preconcurrency import SharedApp

@MainActor
final class NativeJourneyPlatformTests: XCTestCase {
    private func poll(_ description: String, _ predicate: @escaping () -> Bool) async {
        let ready = expectation(description: description)
        let task = Task { @MainActor in
            for _ in 0..<100 {
                if predicate() { ready.fulfill(); return }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        await fulfillment(of: [ready], timeout: 6)
        task.cancel()
        await task.value
    }

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
        fixture.session.state.value.main!.state.value.home!.openJourneySearch()
        let shell = fixture.session.shell
        await poll("results") { shell.state.value.active.journeyResults?.state.value.journeys.isEmpty == false }
    }

    private func openDetail(_ fixture: NativeInteropFixture) async -> NativeJourneyDetailModel {
        await searchToResults(fixture)
        let shell = fixture.session.shell
        shell.state.value.active.journeyResults!.selectJourney(index: 0)
        await poll("detail") { shell.state.value.active.journeyDetail != nil }
        let model = NativeJourneyDetailModel(session: fixture.session, identity: shell.state.value.active.identity)
        model.start()
        await poll("detail correlation settles") { model.state?.correlating == false && model.state?.legs.count == 1 }
        return model
    }

    private func renderedLines(_ window: UIWindow, name: String) throws -> [VNRecognizedTextObservation] {
        window.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        // Read the actual rendered SwiftUI rows. XCTest unit hosts cannot
        // query SwiftUI's accessibility runtime tree as XCUITest can.
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["en-US"]
        try VNImageRequestHandler(cgImage: try XCTUnwrap(image.cgImage)).perform([request])
        return request.results ?? []
    }

    private func renderedText(_ window: UIWindow, name: String) throws -> String {
        try renderedLines(window, name: name).compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
    }

    func testKnownOperatorWithoutPrefillRendersNoBookingOrEmptySpace() async throws {
        let fixture = NativeInteropFixture(bookingHandoffEnabled: true)
        let model = await openDetail(fixture)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let appeared = expectation(description: "journey detail appears")
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 430, height: 1000)
        window.rootViewController = UIHostingController(rootView:
            NativeJourneyDetailView(model: model).environment(\.locale, Locale(identifier: "en_US"))
                .onAppear { appeared.fulfill() })
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)
        defer { window.isHidden = true; _ = model.close(); fixture.close() }
        XCTAssertEqual(model.state?.legs.first?.operatorName, "Trenitalia")
        XCTAssertEqual(model.state?.bookingAvailable, false)
        XCTAssertNil(model.state?.bookingOperatorName)
        let lines = try renderedLines(window, name: "ios-journey-no-homepage-booking")
        let text = lines.compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
        XCTAssertTrue(text.contains("Leg 1 of 1"), text)
        XCTAssertFalse(text.contains("Continue to"), text)
        XCTAssertFalse(text.contains("Unable to open"), text)
        XCTAssertFalse(text.lowercased().contains("booking unavailable"), text)
        let leg = try XCTUnwrap(lines.first { $0.topCandidates(1).first?.string.contains("Leg 1 of 1") == true })
        // Vision coordinates start at the bottom; the first leg must remain
        // near the summary, with no hidden booking container occupying space.
        let legTop = (1 - leg.boundingBox.maxY) * window.bounds.height
        // The detail summary has two text rows and its own spacing; this bound
        // catches a retained booking container without coupling the test to a
        // precise font rasterization or Dynamic Type measurement.
        XCTAssertLessThan(legTop, 340, "Unexpected space before the first leg")
        model.buy()
        XCTAssertEqual(fixture.bookingLaunchCount, 0)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    func testScheduledAndActualPlatformRowsRenderIndependently() async throws {
        let cases: [(String?, String?, Bool)] = [
            ("7", nil, false), ("7", nil, true), ("7", "8", true),
            (nil, "8", true), (nil, nil, false), ("7", "7", true)
        ]
        for (index, entry) in (cases.map { ("Trenitalia", $0) } + cases.map { ("Italo", $0) }).enumerated() {
            let (operatorName, entry) = entry
            let (scheduled, actual, realtime) = entry
            let fixture = NativeInteropFixture()
            fixture.stageJourneyPlatformsForOperator(operatorName: operatorName, scheduledPlatform: scheduled,
                actualPlatform: actual, realtimeAvailable: realtime)
            let model = await openDetail(fixture)
            let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
            let appeared = expectation(description: "journey platform rows appear")
            let window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 430, height: 1000)
            window.rootViewController = UIHostingController(rootView:
                NativeJourneyDetailView(model: model).environment(\.locale, Locale(identifier: "en_US"))
                    .onAppear { appeared.fulfill() })
            window.makeKeyAndVisible()
            await fulfillment(of: [appeared], timeout: 5)
            defer { window.isHidden = true; _ = model.close(); fixture.close() }
            XCTAssertEqual(model.state?.legs.first?.scheduledPlatform, scheduled)
            XCTAssertEqual(model.state?.legs.first?.operatorName, operatorName)
            XCTAssertEqual(model.state?.legs.first?.actualPlatform, actual)
            let text = try renderedText(window, name: "ios-journey-platform-case-\(index)")
            if let scheduled {
                XCTAssertTrue(text.contains("Expected platform \(scheduled)"), text)
            } else {
                XCTAssertFalse(text.contains("Expected platform"), text)
            }
            if let actual {
                XCTAssertTrue(text.contains("Actual platform \(actual)"), text)
            } else {
                XCTAssertFalse(text.contains("Actual platform"), text)
            }
            if realtime {
                fixture.stageJourneyRealtimeFailure()
                await poll("realtime failure is observed") { model.state?.legs.first?.realtimeFailed == true }
                XCTAssertEqual(model.state?.legs.first?.scheduledPlatform, scheduled)
                XCTAssertNil(model.state?.legs.first?.actualPlatform)
                let failedText = try renderedText(window, name: "ios-journey-platform-failure-\(index)")
                if let scheduled { XCTAssertTrue(failedText.contains("Expected platform \(scheduled)"), failedText) }
                XCTAssertFalse(failedText.contains("Actual platform"), failedText)
            }
            XCTAssertEqual(fixture.networkRequests, 0)
        }
    }

    func testResultsRenderExpectedPlatformForBothOperatorsWithoutRealtime() async throws {
        for operatorName in ["Italo", "Trenitalia"] {
            for scheduled in ["7", nil] as [String?] {
                let fixture = NativeInteropFixture()
                fixture.stageJourneyPlatformsForOperator(operatorName: operatorName, scheduledPlatform: scheduled,
                    actualPlatform: nil, realtimeAvailable: false)
                await searchToResults(fixture)
                let model = NativeJourneyResultsModel(session: fixture.session,
                    identity: fixture.session.shell.state.value.active.identity)
                model.start()
                await poll("results model ready") { model.state?.journeys.count == 1 }
                let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
                let appeared = expectation(description: "journey results appear")
                let window = UIWindow(windowScene: scene)
                window.frame = CGRect(x: 0, y: 0, width: 430, height: 1000)
                window.rootViewController = UIHostingController(rootView:
                    NativeJourneyResultsView(model: model).environment(\.locale, Locale(identifier: "en_US"))
                        .onAppear { appeared.fulfill() })
                window.makeKeyAndVisible()
                await fulfillment(of: [appeared], timeout: 5)
                defer { window.isHidden = true; _ = model.close(); fixture.close() }
                XCTAssertEqual(model.state?.journeys.first?.legs.first?.scheduledPlatform, scheduled)
                let text = try renderedText(window, name: "ios-results-platform-\(operatorName)-\(scheduled ?? "missing")")
                if let scheduled {
                    XCTAssertTrue(text.contains("Expected platform \(scheduled)"), text)
                } else {
                    XCTAssertFalse(text.contains("Expected platform"), text)
                }
                XCTAssertFalse(text.contains("Actual platform"), text)
                XCTAssertFalse(text.contains("%@"), text)
                XCTAssertEqual(fixture.networkRequests, 0)
            }
        }
    }
}
