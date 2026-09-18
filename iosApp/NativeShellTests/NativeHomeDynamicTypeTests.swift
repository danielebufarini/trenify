import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

/// Real increased Dynamic Type verification for native Home (T8.5
/// corrective): the composer is measured at .large and .accessibility3 in a
/// narrow window. Growth proves scaling applies with no fixed-height
/// clipping; key controls must still exist in the rendered hierarchy.
@MainActor
final class NativeHomeDynamicTypeTests: XCTestCase {
    func testHomeComposerGrowsWithDynamicTypeWithoutClipping() async {
        let fixture = NativeInteropFixture()
        defer { fixture.close() }
        fixture.selectHome()
        let model = NativeHomeModel(session: fixture.session)
        model.start()
        let ready = expectation(description: "home content ready")
        ready.assertForOverFulfill = false
        model.onUpdate = {
            if model.homeState != nil && model.journeyInput != nil { ready.fulfill() }
        }
        await fulfillment(of: [ready], timeout: 5)
        model.onUpdate = nil

        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            XCTFail("Home Dynamic Type check needs the native window scene")
            return
        }
        // Window-hosted measurement at both sizes: windowless sizeThatFits
        // mis-measures the compact date pickers, while the scroll content
        // size of a laid-out window is the true laid-out content height.
        // NativeHomeView is itself the scrolling root, as in production.
        let normalHeight = await laidOutContentHeight(scene: scene, model: model, size: .large)
        let largeHeight = await laidOutContentHeight(scene: scene, model: model, size: .accessibility3)
        XCTAssertTrue(normalHeight.isFinite && largeHeight.isFinite)
        XCTAssertGreaterThan(normalHeight, 500)
        XCTAssertGreaterThan(largeHeight, normalHeight * 1.3)

        let appeared = expectation(description: "large home hierarchy appeared")
        appeared.assertForOverFulfill = false
        let host = UIHostingController(rootView:
            NativeHomeView(model: model).environment(\.dynamicTypeSize, .accessibility3)
                .onAppear { appeared.fulfill() })
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 1500)
        window.rootViewController = host
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)
        host.view.frame = window.bounds
        host.view.layoutIfNeeded()
        var rendered = false
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            rendered = host.view.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        XCTAssertTrue(rendered)
        // SwiftUI stamps accessibilityIdentifiers on the AX runtime tree,
        // which unit tests cannot query; the backing UIKit views below are
        // the verifiable part. Both station fields and both pickers must be
        // materialized at accessibility size.
        let views = descendants(of: window)
        let fields = views.compactMap { $0 as? UITextField }
        XCTAssertGreaterThanOrEqual(fields.count, 2, "origin/destination fields missing at accessibility text size")
        let pickers = views.compactMap { $0 as? UIDatePicker }
        XCTAssertGreaterThanOrEqual(pickers.count, 2, "date/time pickers missing at accessibility text size")
        let attachment = XCTAttachment(image: image)
        attachment.name = "ios-home-dynamic-type"
        attachment.lifetime = .keepAlways
        add(attachment)
        window.isHidden = true
        _ = model.close()
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    /// Hosts Home at the given Dynamic Type size in a real window, renders
    /// it, and returns the laid-out scroll content height.
    private func laidOutContentHeight(scene: UIWindowScene, model: NativeHomeModel, size: DynamicTypeSize) async -> CGFloat {
        let appeared = expectation(description: "home laid out at \(size)")
        appeared.assertForOverFulfill = false
        let host = UIHostingController(rootView:
            NativeHomeView(model: model).environment(\.dynamicTypeSize, size)
                .onAppear { appeared.fulfill() })
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 1500)
        window.rootViewController = host
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)
        host.view.frame = window.bounds
        host.view.layoutIfNeeded()
        XCTAssertTrue(host.view.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        let height = descendants(of: window).compactMap { $0 as? UIScrollView }.map { $0.contentSize.height }.max() ?? 0
        window.isHidden = true
        return height
    }

    private func descendants(of view: UIView) -> [UIView] {
        var out = [view]
        var index = 0
        while index < out.count {
            out.append(contentsOf: out[index].subviews)
            index += 1
        }
        return out
    }
}
