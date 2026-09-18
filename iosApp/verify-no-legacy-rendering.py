#!/usr/bin/env python3
"""T8.15 architecture guard: prevent reintroduction of shared production
feature rendering and iOS Compose hosting.

Path-aware (not blind grep): Android-owned Compose in androidApp is
legitimate and never scanned for @Composable. Fails closed with the
offending file:line list. Run: python3 iosApp/verify-no-legacy-rendering.py
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
failures: list[str] = []


def check(pattern: str, files: list[Path], label: str) -> None:
    rx = re.compile(pattern)
    for path in files:
        try:
            text = path.read_text()
        except (OSError, UnicodeDecodeError):
            continue
        for i, line in enumerate(text.splitlines(), 1):
            if rx.search(line):
                failures.append(f"{label}: {path.relative_to(REPO)}:{i}: {line.strip()[:160]}")


def kt(dirs: list[str]) -> list[Path]:
    out: list[Path] = []
    for d in dirs:
        root = REPO / d
        if root.is_dir():
            out += sorted(root.rglob("*.kt"))
    return out


def swift(d: str) -> list[Path]:
    root = REPO / d
    return sorted(root.rglob("*.swift")) if root.is_dir() else []


SHARED_PROD = [
    "shared/app/src/commonMain", "shared/app/src/androidMain", "shared/app/src/iosMain",
    "shared/core/ui/src/commonMain", "shared/core/ui/src/androidMain", "shared/core/ui/src/iosMain",
    "shared/feature/favorites/src/commonMain", "shared/feature/home/src/commonMain",
    "shared/feature/journey/src/commonMain", "shared/feature/monitoring/src/commonMain",
    "shared/feature/settings/src/commonMain", "shared/feature/station/src/commonMain",
    "shared/feature/strikes/src/commonMain", "shared/feature/train/src/commonMain",
]
SHARED_TEST = [
    "shared/app/src/commonTest", "shared/app/src/androidHostTest", "shared/app/src/iosTest",
]
ANDROID_MAIN = ["androidApp/src/main"]
IOS_PROD = "iosApp/iosApp"

# 1. Shared production Kotlin must contain no rendering Composables
#    (ADR 0005: final-state commonMain has no rendering Composables).
check(r"@Composable", kt(SHARED_PROD), "shared-production-composable")

# 2. Removed lease/host/renderer APIs must not reappear in production Kotlin.
LEGACY_KT = (
    r"LegacyScreenContent|IosLegacyScreenHost|IosLegacyScreenHosting|"
    r"MainViewController\(|fun TrenifyApp\(|fun legacy\(|watchLegacy|"
    r"LegacyShellRecord|LegacyAlertContent|legacyBackControl|"
    r"legacyHostCounts|legacyRenderCounts|legacyRenderedIdentities|"
    r"ComposeUIViewController"
)
check(LEGACY_KT, kt(SHARED_PROD) + kt(ANDROID_MAIN), "legacy-renderer-api")

# 3. Deleted shared screen entry points must not be referenced by shared
#    tests either (prevents oracle resurrection outside production).
SCREENS = (
    r"(?<![A-Za-z])HomeScreen\(|JourneySearchScreen\(|JourneyResultsScreen\(|"
    r"JourneyDetailScreen\(|SearchHistoryScreen\(|JourneyTabContent\(|"
    r"StationSearchScreen\(|StationBoardScreen\(|StationsTabContent\(|"
    r"TrainSearchScreen\(|TrainDetailScreen\(|TrainRunPicker\(|"
    r"MonitoringTabContent\(|FavoritesScreen\(|SettingsScreen\(|"
    r"StrikeListScreen\(|StrikeDetailScreen\(|AlertsTabContent\("
)
check(SCREENS, kt(SHARED_PROD) + kt(SHARED_TEST), "deleted-screen-reference")

# 4. iOS production Swift must not host Compose UI.
check(r"ComposeUIViewController|IosLegacyScreenHost|LegacyScreenView",
      swift(IOS_PROD), "ios-compose-host")
check(LEGACY_KT, swift(IOS_PROD), "ios-legacy-api")

# 5. Shared Gradle modules must not regain renderer-only dependencies.
#    Justified exceptions (resource plumbing, no @Composable in either module):
#    shared/core/ui keeps api(compose.runtime) + api(compose.components.resources);
#    shared/app keeps implementation(compose.runtime) so the Compose resource
#    pipeline aggregates the core:ui Res catalog next to iOS test binaries.
STRICT_GRADLE = ["shared/feature/favorites/build.gradle.kts", "shared/feature/home/build.gradle.kts",
                 "shared/feature/journey/build.gradle.kts", "shared/feature/monitoring/build.gradle.kts",
                 "shared/feature/settings/build.gradle.kts", "shared/feature/station/build.gradle.kts",
                 "shared/feature/strikes/build.gradle.kts", "shared/feature/train/build.gradle.kts"]
for rel in STRICT_GRADLE:
    p = REPO / rel
    if p.is_file():
        check(r"decompose\.extensions\.compose|compose\.ui\.test|"
              r"\(compose\.runtime|\(compose\.foundation|\(compose\.material3|\(compose\.ui\b",
              [p], "renderer-only-dependency")
app_gradle = REPO / "shared/app/build.gradle.kts"
if app_gradle.is_file():
    check(r"decompose\.extensions\.compose|compose\.ui\.test|"
          r"\(compose\.foundation|\(compose\.material3|\(compose\.ui\b",
          [app_gradle], "renderer-only-dependency")
    if "implementation(compose.runtime)" not in app_gradle.read_text():
        failures.append("renderer-only-dependency: implementation(compose.runtime) missing from shared/app")
ui_gradle = REPO / "shared/core/ui/build.gradle.kts"
if ui_gradle.is_file():
    check(r"decompose\.extensions\.compose|compose\.ui\.test|"
          r"\(compose\.foundation|\(compose\.material3|\(compose\.ui\b",
          [ui_gradle], "renderer-only-dependency")
    text = ui_gradle.read_text()
    for required in ("api(compose.runtime)", "api(compose.components.resources)"):
        if required not in text:
            failures.append(f"renderer-only-dependency: {required} missing from shared/core/ui")

if failures:
    print(f"FAIL: {len(failures)} legacy-rendering violation(s):")
    for f in failures:
        print(f"  {f}")
    sys.exit(1)
print("PASS: no shared production rendering, iOS Compose hosting, or renderer-only deps found.")
