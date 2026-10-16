import SwiftUI

/// Installed app metadata belongs to the main application bundle, never the
/// SharedApp framework or the bundle containing the native shell's classes.
struct NativeAppMetadata {
    let name: String
    let version: String
    let buildDate: String?

    init(bundle: Bundle = .main) {
        func value(_ key: String) -> String? {
            (bundle.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 }
        }
        name = value("CFBundleDisplayName") ?? value("CFBundleName") ?? ""
        let short = value("CFBundleShortVersionString")
        let build = value("CFBundleVersion")
        if let short, let build, short != build {
            version = "\(short) (\(build))"
        } else {
            version = short ?? build ?? ""
        }
        buildDate = bundle.url(forResource: "TrenifyBuildDate", withExtension: "txt")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    }
}

struct NativeAboutView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var metadata = NativeAppMetadata()

    var body: some View {
        VStack(spacing: TrenifySpacing.s) {
            ScrollView {
                VStack(spacing: TrenifySpacing.s) {
                    Text(verbatim: metadata.name)
                        .font(TrenifyTypography.sectionTitle)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("about-name")
                    Image("AboutAppIcon", bundle: Bundle(for: NativeShellModel.self))
                        .resizable().scaledToFit()
                        .frame(width: 112, height: 112)
                        .clipShape(RoundedRectangle(cornerRadius: 24))
                        .accessibilityLabel(Text(verbatim: metadata.name))
                        .accessibilityIdentifier("about-icon")
                    Text(verbatim: metadata.version)
                        .accessibilityIdentifier("about-version")
                    if let date = metadata.buildDate {
                        Text(verbatim: NSLocalizedString("shell.buildDate", tableName: "Shell",
                            bundle: Bundle(for: NativeShellModel.self), comment: "") + ": " + date)
                            .accessibilityIdentifier("about-build-date")
                    }
                }
                .font(TrenifyTypography.body)
                .foregroundStyle(TrenifyColors.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            TrenifySecondaryAction(title: NSLocalizedString("shell.close", tableName: "Shell",
                bundle: Bundle(for: NativeShellModel.self), comment: "")) { dismiss() }
                .accessibilityIdentifier("about-close")
        }
        .padding(24)
    }
}
