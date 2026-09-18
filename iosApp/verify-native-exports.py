#!/usr/bin/env python3
"""Verify the SharedApp presentation framework export census.

Without flags it inspects the given header paths (and fails closed when they
carry the wrong fixture state). With --build it first rebuilds the framework
in the requested mode, and with --evidence-dir it snapshots the inspected
header plus a build-mode record into a dedicated directory, so normal
production evidence and fixture-enabled evidence can never be confused:

  normal production evidence:
    python3 iosApp/verify-native-exports.py --build normal \\
      --evidence-dir iosApp/framework-evidence/normal-framework
  fixture-enabled evidence:
    python3 iosApp/verify-native-exports.py --build fixture --expect-fixture \\
      --evidence-dir iosApp/framework-evidence/fixture-framework
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FRAMEWORK = REPO / "shared/app/build/bin/iosSimulatorArm64/debugFramework/SharedApp.framework"
SWIFT = REPO / "shared/app/build/skie/binaries/debugFramework/DEBUG/iosSimulatorArm64/swift/generated"
GROUPS = {
    "composition": r"^SharedApp(?:AppGraph|AppComponentFactory|DefaultRootComponent|DefaultMainComponent)",
    "database": r"^SharedAppDatabase",
    "sqldelight_runtime": r"^SharedAppRuntime",
    "ktor": r"^SharedAppKtor",
    "network": r"^SharedAppNetwork",
    "concrete_providers_repositories": r"^SharedApp(?:Provider|DataSqlDelight|DataRealtimeRepositories)",
    "decompose_essenty": r"^SharedApp(?:Decompose|Lifecycle|State_keeper|Instance_keeper|Back_handler)",
    "repositories_use_cases": r"^SharedAppDomain.*(?:Repository|Observe|Search|Load|MonitoringPolicy)",
    "implementation_coordinators_localizers": r"^SharedApp(?:MonitoringCoordinator|StrikeCoordinator|ForegroundNotificationPolicy|NotificationLocalizer|ResourceNotificationLocalizer|IosNotificationTapRouter)",
    "native_presentation": r"^SharedApp(?:Native|IosNativeApplication)",
}
FORBIDDEN = (
    r"\bSharedApp(?:AppGraph\w*|AppComponentFactory\w*|Database\w*|Runtime\w*|Ktor\w*|Network\w*)\b",
    r"\bSharedApp(?:Decompose|Lifecycle|State_keeper|Instance_keeper|Back_handler)\w*\b",
    r"\bSharedApp\w*(?:Repository|ProviderAdapter|SqlDelight|Modifier|Painter|StringResource)\w*\b",
    r"\bSharedApp(?:MonitoringCoordinator|StrikeCoordinator|NotificationLocalizer|ResourceNotificationLocalizer)\w*\b",
    r"\bSharedAppNativeInteropFixture\b",
)


def census(path):
    raw = path.read_bytes()
    names = sorted(re.findall(r"^@(?:interface|protocol)\s+(\w+)", raw.decode(), re.M))
    return {
        "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
        "declarations": len(names),
        "groups": {group: [n for n in names if re.search(pattern, n)] for group, pattern in GROUPS.items()},
        "all_declarations": names,
    }


FIXTURE_SYMBOL = "SharedAppNativeInteropFixture"
FIXTURE_PROPERTY = "trenify.nativeInteropTests=true"

REQUIRED_PRODUCTION = frozenset({
    "DomainDomainFailure",
    "DomainMonitorEventKind",
    "DomainMonitorThresholds",
    "DomainMonitoredTrainSnapshot",
    "DomainStrikeImpact",
    "DomainTrainMonitor",
    "DomainTrainMonitorEvent",
    "DomainTrainMonitorEventArrived",
    "DomainTrainMonitorEventCancelled",
    "DomainTrainMonitorEventDelayThresholdCrossed",
    "DomainTrainMonitorEventDeparted",
    "DomainTrainMonitorEventPartiallyCancelled",
    "DomainTrainMonitorEventPlatformChanged",
    "DomainTrainMonitorEventRouteChanged",
    "DomainTrainMonitorEventScheduleChanged",
    "DomainTrainMonitorEventStatusChanged",
    "HomeHomeState",
    "IosNativeApplication",
    "IosNotificationLaunch",
    "JourneyRouteFavoriteState",
    "MainTab",
    "ModelBoardKind",
    "ModelDataFreshness",
    "ModelDataFreshnessFresh",
    "ModelDataFreshnessStale",
    "ModelDataFreshnessUnknown",
    "ModelFavoriteRoute",
    "ModelFavoriteTrain",
    "ModelJourneySearchHistoryEntry",
    "ModelJourneySearchIntent",
    "ModelJourneySearchMode",
    "ModelJourneySearchRequest",
    "ModelJourneySort",
    "ModelOperationalPosition",
    "ModelOperator",
    "ModelSearchHistoryEntry",
    "ModelStation",
    "ModelStopProgress",
    "ModelStopStatus",
    "ModelStrike",
    "ModelStrikeGeography",
    "ModelStrikeRelevance",
    "ModelStrikeSource",
    "ModelStrikeStatus",
    "ModelTrainCategory",
    "ModelTrainLookupIntent",
    "ModelTrainRun",
    "ModelTrainRunId",
    "ModelTrainRunSummary",
    "ModelTrainSearchHistoryEntry",
    "ModelTrainStatus",
    "ModelTrainStop",
    "NativeAlertsPresentation",
    "NativeAlertsState",
    "NativeApplicationSession",
    "NativeDestination",
    "NativeFavoriteRouteRow",
    "NativeFavoriteStationRow",
    "NativeFavoriteTrainRow",
    "NativeHistoryPresentation",
    "NativeHistoryState",
    "NativeHomePresentation",
    "NativeJourneyCardPresentation",
    "NativeJourneyDetailPresentation",
    "NativeJourneyDetailState",
    "NativeJourneyHistoryRow",
    "NativeJourneyInputState",
    "NativeJourneyLegPresentation",
    "NativeJourneyResultsPresentation",
    "NativeJourneyResultsState",
    "NativeJourneySearchPresentation",
    "NativeLegDetailPresentation",
    "NativeMainEntry",
    "NativeMainPresentation",
    "NativeMainState",
    "NativeMonitorCard",
    "NativeMonitoringPresentation",
    "NativeMonitoringState",
    "NativeNavigationEntry",
    "NativeNavigationPresentation",
    "NativeNavigationState",
    "NativePrimaryArea",
    "NativeRealtimeFreshness",
    "NativeRealtimeObservation",
    "NativeRealtimeProvenancePresentation",
    "NativeRootState",
    "NativeSavedPresentation",
    "NativeSavedState",
    "NativeSemanticIdentity",
    "NativeServiceStrikeWarningPresentation",
    "NativeSessionPhase",
    "NativeSettingsPresentation",
    "NativeShellEntry",
    "NativeShellPresentation",
    "NativeShellState",
    "NativeStationBoardPresentation",
    "NativeStationBoardState",
    "NativeStationRow",
    "NativeStationSearchPresentation",
    "NativeStationSearchState",
    "NativeStrikeRow",
    "NativeTrainDetailPresentation",
    "NativeTrainDetailState",
    "NativeTrainHistoryRow",
    "NativeTrainRow",
    "NativeTrainRunIdentity",
    "NativeTrainSearchPresentation",
    "NativeTrainSearchState",
    "NativeTrainStop",
    "PlatformEffectiveNotificationPermission",
    "PlatformNotificationDestination",
    "PlatformNotificationDestinationStrike",
    "PlatformNotificationDestinationTrain",
    "SettingsFailedSettingsSave",
    "SettingsFailedSettingsSaveEventFlag",
    "SettingsFailedSettingsSaveGlobal",
    "SettingsFailedSettingsSaveStrike",
    "SettingsFailedSettingsSaveThreshold",
    "SettingsPersonalDataDeletionStatus",
    "SettingsSettingsState",
    "SettingsThresholdValidation",
    "TrainTrainFavoriteState",
    "UiRealtimeState",
})

# T8.15: the legacy-transitional category is intentionally EMPTY. The three
# T8.14 transitional exports (IosLegacyScreenHost, IosLegacyScreenHosting,
# MainViewControllerKt) were removed with the Compose host path. The set is
# kept (empty) so `known()` below fails closed: any reintroduced legacy
# symbol is reported as an unexpected production export.
LEGACY_TRANSITIONAL = frozenset()
# Test-only symbols that must never appear in the normal production header.
TEST_ONLY_SYMBOLS = frozenset({FIXTURE_SYMBOL[len("SharedApp"):]})
# Toolchain/runtime plumbing: compiler ABI surface, never project API.
PLUMBING_PATTERNS = (
    r"^SharedAppKotlin",
    r"^SharedAppKotlinx_",
    r"^SharedAppSkie",
    r"^SharedApp__Skie",
    r"^__Skie",
)
PLUMBING_EXACT = frozenset({
    "Base", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double",
    "UByte", "UShort", "UInt", "ULong", "Number",
    "MutableDictionary", "MutableSet", "NSError",
})


def build_framework(mode):
    """Rebuild the framework in the requested mode; returns the gradle command."""
    command = [str(REPO / "gradlew"), ":shared:app:linkDebugFrameworkIosSimulatorArm64"]
    if mode == "fixture":
        command.append(f"-P{FIXTURE_PROPERTY}")
    print(f"Building {mode} framework: {' '.join(command)}", flush=True)
    subprocess.run(command, cwd=REPO, check=True)
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--header", type=Path, default=FRAMEWORK / "Headers/SharedApp.h")
    parser.add_argument("--swift", type=Path, default=SWIFT)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apinotes", type=Path, default=SWIFT.parent.parent / "swift-compiler/apinotes/SharedApp.apinotes")
    parser.add_argument("--build", choices=("normal", "fixture"), default=None,
                        help="rebuild the framework in this mode before inspecting it")
    parser.add_argument("--evidence-dir", type=Path, default=None,
                        help="snapshot the inspected header plus a build-mode record here")
    parser.add_argument("--expect-fixture", action="store_true",
                        help="require the fixture symbol (fixture-enabled framework evidence)")
    args = parser.parse_args()
    command = build_framework(args.build) if args.build else None
    mode = args.build or "inspect-only"
    if args.expect_fixture and mode == "normal":
        raise SystemExit("--expect-fixture contradicts --build normal")
    header_path = args.header
    header = header_path.read_text()
    fixture_present = FIXTURE_SYMBOL in header
    if not args.expect_fixture and fixture_present:
        raise SystemExit(
            f"Refusing to verify: {header_path} contains {FIXTURE_SYMBOL}, "
            "so this is a fixture-enabled framework, not the normal production one. "
            "Re-run with --build normal (or --expect-fixture for fixture evidence).")
    if args.expect_fixture and not fixture_present:
        raise SystemExit(
            f"Refusing to verify: {header_path} lacks {FIXTURE_SYMBOL}, "
            "so this is not the expected fixture-enabled framework.")
    # In fixture mode the fixture symbol is expected evidence, not a leak.
    active_forbidden = (tuple(pattern for pattern in FORBIDDEN if FIXTURE_SYMBOL not in pattern)
                        if args.expect_fixture else FORBIDDEN)
    files = sorted(args.swift.rglob("*.swift"))
    if not files:
        raise SystemExit("No generated SKIE Swift sources found")
    swift = "\n".join(path.read_text() for path in files)
    forbidden = sorted({match for pattern in active_forbidden for match in re.findall(pattern, header)})
    stale_swift = [str(path.relative_to(args.swift)) for path in files
                   if any(re.search(pattern, path.read_text()) for pattern in active_forbidden)]
    # T8.14-R9: the required contract covers the complete current production
    # native API (shell/state/entry/destination, Home, Journey Results/Detail/
    # Search, Station/Train, Monitoring, Saved, History, Alerts, Settings plus
    # consumed value types), derived from actual Swift production use
    # (see doc/design/review/t8_14/skie-symbol-classification.json).
    required = sorted(REQUIRED_PRODUCTION)
    missing = [name for name in required if f'swift_name("{name}")' not in header]
    # T8.14-R10: fail closed on any production export outside the accepted
    # contract (required + toolchain plumbing + companions of required
    # types; T8.15 emptied the legacy-transitional set so reintroduced
    # legacy symbols fail here). Counts alone are not the contract.
    declared = sorted(set(re.findall(r"^@(?:interface|protocol)\s+(\w+)", header, re.M)))

    def known(name):
        if not name.startswith("SharedApp"):
            return True  # Foundation/system declarations, never project API.
        bare = name[len("SharedApp"):]
        if bare in TEST_ONLY_SYMBOLS:
            # Expected evidence in fixture builds; a leak (caught below) otherwise.
            return bool(args.expect_fixture)
        return (bare in REQUIRED_PRODUCTION or bare in LEGACY_TRANSITIONAL
                or bare in PLUMBING_EXACT
                or any(re.search(pattern, name) for pattern in PLUMBING_PATTERNS)
                or (bare.endswith("Companion") and bare[:-len("Companion")] in REQUIRED_PRODUCTION))

    unexpected = sorted(name for name in declared if not known(name))
    if not args.expect_fixture:
        leaked = sorted(set(re.findall(r"^@(?:interface|protocol)\s+(SharedApp\w+)", header, re.M))
                        & {"SharedApp" + bare for bare in TEST_ONLY_SYMBOLS})
    else:
        leaked = []
    if "case trainSearch" not in swift or "case strikeDetail" not in swift:
        missing.append("SKIE NativeDestination typed enum")
    apinotes = args.apinotes.read_text()
    if "SharedAppSkieKotlinStateFlow<SharedAppHomeHomeState *>" not in apinotes:
        missing.append("typed Home StateFlow API notes")
    if "makeAsyncIterator() -> SharedApp.SkieSwiftFlowIterator<T>" not in swift:
        missing.append("SKIE StateFlow AsyncSequence iterator")
    report = {"before": census(args.before) if args.before else None,
              "after": census(args.header), "generated_swift_files": len(files),
              "apinotes_sha256": hashlib.sha256(apinotes.encode()).hexdigest(),
              "forbidden_header_symbols": forbidden, "forbidden_swift_files": stale_swift,
              "missing_required_exports": missing,
              "unexpected_production_exports": unexpected,
              "test_only_leak_into_production": leaked,
              "required_contract_size": len(required),
              "build": {"mode": ("fixture" if args.expect_fixture else mode),
                        "property": (FIXTURE_PROPERTY if args.expect_fixture or mode == "fixture"
                                     else "absent (normal production)"),
                        "command": command,
                        "header_sha256": hashlib.sha256(header.encode()).hexdigest(),
                        "fixture_symbol_present": fixture_present}}
    if args.evidence_dir:
        args.evidence_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(header_path, args.evidence_dir / "SharedApp.h")
        (args.evidence_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Evidence snapshotted to {args.evidence_dir}", flush=True)
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key not in ("before", "after")}, indent=2))
    print(f"Header: {report['after']['bytes']} bytes, {report['after']['declarations']} declarations")
    if forbidden or stale_swift or missing or unexpected or leaked:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
