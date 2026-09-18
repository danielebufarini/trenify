import SwiftUI
import UIKit

/// Bottom reachability above the native tab chrome (T8.5 post-acceptance).
///
/// The system tab bar stays native, including floating Liquid Glass. A
/// floating bar does not extend the safe area, so safe-area insets alone
/// cannot keep scrolled content reachable: the last rows would rest beneath
/// the glass. This reader measures, in window coordinates, how far the real
/// tab bar extends above the bottom safe-area edge:
///
///   extra = max(0, safeAreaTopEdge − tabBarTop)
///
/// Home applies exactly that as extra scroll inset. No hard-coded bar
/// height: the value is re-measured on every layout and safe-area change,
/// so chrome show/hide, rotation and Dynamic Type all behave. Light/dark agnostic;
/// keyboard presentation still adjusts the safe area underneath.
struct TabChromeOverlapReader: UIViewRepresentable {
    @Binding var overlap: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> ReaderView {
        let view = ReaderView()
        // Copying the parent copies its Binding box, so writes propagate.
        view.onOverlap = { context.coordinator.parent.overlap = $0 }
        return view
    }

    func updateUIView(_ uiView: ReaderView, context: Context) {
        context.coordinator.parent = self
    }

    final class Coordinator {
        var parent: TabChromeOverlapReader
        init(_ parent: TabChromeOverlapReader) { self.parent = parent }
    }

    final class ReaderView: UIView {
        var onOverlap: (CGFloat) -> Void = { _ in }
        private var last: CGFloat = -1
        private var link: CADisplayLink?

        override init(frame: CGRect) {
            super.init(frame: frame)
            // Measurement only: never intercept touches or draw.
            isUserInteractionEnabled = false
            backgroundColor = .clear
            isOpaque = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil {
                startLink()
            } else {
                stopLink()
            }
            publish()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            publish()
        }

        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            publish()
        }

        /// Chrome can appear, move or vanish without relayout of this view
        /// (a sibling overlay, a pushed route hiding the bar), so observe
        /// continuously while on screen. The walk is tiny and publishes only
        /// on real change, so SwiftUI never churns.
        private func startLink() {
            guard link == nil else { return }
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            self.link = link
        }

        private func stopLink() {
            link?.invalidate()
            link = nil
        }

        @objc private func tick() { publish() }

        private func publish() {
            let next = compute()
            guard abs(next - last) > 0.5 else { return }
            last = next
            onOverlap(next)
        }

        private func compute() -> CGFloat {
            guard let window = self.window else { return 0 }
            let safeEdge = window.bounds.maxY - window.safeAreaInsets.bottom
            var extra: CGFloat = 0
            var stack: [UIView] = [window]
            while let view = stack.popLast() {
                if let bar = view as? UITabBar, !bar.isHidden, bar.alpha > 0.01, bar.window != nil {
                    let top = bar.convert(bar.bounds, to: window).minY
                    extra = max(extra, safeEdge - top)
                }
                stack.append(contentsOf: view.subviews)
            }
            return max(0, extra)
        }
    }
}

/// Applies the measured chrome overlap as scroll inset. `contentMargins`
/// compose with the automatic safe-area margins on iOS 26+.
struct HomeTabReachabilityInset: ViewModifier {
    let overlap: CGFloat

    func body(content: Content) -> some View {
        content.contentMargins(.bottom, overlap, for: .scrollContent)
    }
}
