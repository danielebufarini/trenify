import XCTest
import SwiftUI
import UIKit
@preconcurrency import SharedApp

/// Bottom-reachability proofs for native Home (T8.5 post-acceptance).
///
/// The simulator renders a docked tab bar with full automatic insets, so the
/// device-only floating-glass shortfall cannot reproduce with real chrome
/// in-process. This test therefore simulates the exact device condition — a
/// real UITabBar hovering above the bottom safe edge, which the fixed
/// automatic insets do not cover — against the production Home view and its
/// measured chrome inset. A companion invariant in NativeShellTests proves
/// the last content stays above the real tab bar in the full shell.
@MainActor
final class NativeHomeLayoutTests: XCTestCase {
    func testFloatingChromeDrivesMeasuredInsetAndReachability() async {
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
            XCTFail("Home reachability check needs the native window scene")
            return
        }
        let appeared = expectation(description: "production home appeared")
        appeared.assertForOverFulfill = false
        let host = UIHostingController(rootView:
            NativeHomeView(model: model).onAppear { appeared.fulfill() })
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = host
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)

        // Floating-glass simulation: a real UITabBar hovering 70pt above the
        // window bottom, past the small test-window safe area — the geometry
        // a floating bar has on device, where fixed insets fall short.
        let glassTop = window.bounds.maxY - 70
        let glass = UITabBar(frame: CGRect(x: 16, y: glassTop,
                                           width: window.bounds.width - 32, height: 58))
        window.addSubview(glass)
        window.layoutIfNeeded()

        let views = viewDescendants(of: window)
        guard let homeScroll = views.compactMap({ $0 as? UIScrollView }).first(where: { scroll in
            viewDescendants(of: scroll).contains(where: { $0 is UITextField })
        }) else {
            XCTFail("Home scroll view with composer fields missing")
            return
        }
        // Settle the measured inset with a bounded acknowledgement, never a sleep.
        var lastInset: CGFloat = -1
        var stableTurns = 0
        await settle(desc: "chrome inset settled") {
            let current = homeScroll.adjustedContentInset.bottom
            if current == lastInset { stableTurns += 1 } else { stableTurns = 0; lastInset = current }
            return stableTurns >= 2
        }
        // The measured overlap must actually reach the scroll view: the
        // effective bottom inset covers the glass beyond the safe area.
        let measuredOverlap = (window.bounds.maxY - window.safeAreaInsets.bottom) - glassTop
        XCTAssertGreaterThan(measuredOverlap, 0, "simulated glass must overlap past the safe edge")
        XCTAssertGreaterThanOrEqual(homeScroll.adjustedContentInset.bottom,
                                    window.safeAreaInsets.bottom + measuredOverlap - 1)
        // Overflow is required: without it the reachability proof is vacuous.
        XCTAssertGreaterThan(homeScroll.contentSize.height, homeScroll.bounds.height,
                             "Home content must overflow the viewport")
        homeScroll.setContentOffset(CGPoint(
            x: 0,
            y: homeScroll.contentSize.height - homeScroll.bounds.height + homeScroll.adjustedContentInset.bottom,
        ), animated: false)
        window.layoutIfNeeded()
        // The content end, mapped into window coordinates, sits fully above
        // the simulated glass: the last actionable item can be brought clear
        // of floating chrome by scrolling.
        let scrollFrame = homeScroll.convert(homeScroll.bounds, to: window)
        let endInWindow = scrollFrame.minY + (homeScroll.contentSize.height - homeScroll.contentOffset.y)
        XCTAssertLessThanOrEqual(endInWindow, glassTop + 1)

        glass.removeFromSuperview()
        window.isHidden = true
        window.rootViewController = nil
        _ = model.close()
        XCTAssertTrue(fixture.sameRoot)
        XCTAssertEqual(fixture.networkRequests, 0)
    }

    private func settle(desc: String, _ predicate: @escaping () -> Bool) async {
        let settled = expectation(description: desc)
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

    private func viewDescendants(of view: UIView) -> [UIView] {
        var out = [view]
        var index = 0
        while index < out.count {
            out.append(contentsOf: out[index].subviews)
            index += 1
        }
        return out
    }
}
