#!/bin/sh
# Canonical native-test entry point (T8.5 post-acceptance cleanup).
#
# Every run is hermetic:
#   - an explicit isolated -derivedDataPath under .trenify-derived/<Scheme>,
#     cleaned deterministically before each run, so no developer-global or
#     stale DerivedData state (and no fixture-less embed copy) can shadow the
#     framework the tests compile against;
#   - the fixture-enabled framework is rebuilt fresh by this script, and its
#     header is preflight-checked for the fixture symbol with a clear abort
#     instead of cryptic Swift "not in scope" errors.
#
# Usage:
#   iosApp/run-native-tests.sh NativeShellTests
#   iosApp/run-native-tests.sh NativeInteropTests
#   iosApp/run-native-tests.sh DesignSystemTests
#   iosApp/run-native-tests.sh iosApp        # production app build (normal framework)
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCHEME="${1:?usage: run-native-tests.sh <Scheme>}"
DERIVED="$ROOT/.trenify-derived/$SCHEME"

rm -rf "$DERIVED"
mkdir -p "$DERIVED"

if [ "$SCHEME" = "iosApp" ]; then
  # Production app: normal framework, no fixture property anywhere near it.
  "$ROOT/gradlew" -p "$ROOT" :shared:app:linkDebugFrameworkIosSimulatorArm64
  exec xcodebuild -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme iosApp \
    -sdk iphonesimulator -configuration Debug -derivedDataPath "$DERIVED" \
    CODE_SIGNING_ALLOWED=NO build
fi

# Test schemes: fresh fixture-enabled framework, preflight-checked.
"$ROOT/gradlew" -p "$ROOT" :shared:app:linkDebugFrameworkIosSimulatorArm64 \
  -Ptrenify.nativeInteropTests=true
HEADER="$ROOT/shared/app/build/bin/iosSimulatorArm64/debugFramework/SharedApp.framework/Headers/SharedApp.h"
if ! grep -q "SharedAppNativeInteropFixture" "$HEADER"; then
  echo "run-native-tests: ABORT: $HEADER lacks the fixture symbol;" \
    "the fixture framework build did not land where tests resolve it." >&2
  exit 1
fi

# Resolve a simulator dynamically: prefer iPhone 17, else first available
# iPhone, so the command works without a recorded device id.
SIM_ID="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = [d for runtime in json.load(sys.stdin)["devices"].values() for d in runtime if d.get("isAvailable")]
iphones = [d for d in devices if "iPhone" in d.get("name", "")]
pick = next((d for d in iphones if d["name"] == "iPhone 17"), None) or (iphones[0] if iphones else None)
if pick is None:
    sys.exit("no available iPhone simulator")
print(pick["udid"])')"

exec xcodebuild -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme "$SCHEME" \
  -configuration Debug -derivedDataPath "$DERIVED" \
  -destination "platform=iOS Simulator,id=$SIM_ID" \
  CODE_SIGNING_ALLOWED=NO test
