#!/usr/bin/env bash
# Compile the pure-Java ADB client + crypto (everything except AdbMdns) with the host JDK
# and run the in-process self-tests.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/app/app/src/main/java"
TEST="$ROOT/app/hosttest"
OUT="/tmp/adb-hosttest"
VECTOR="$ROOT/docs/spake2-backstage-vector.json"
VECTORS_OUT="$TEST/vectors/e2e-vectors-java.json"

rm -rf "$OUT"
mkdir -p "$OUT" "$(dirname "$VECTORS_OUT")"

mapfile -t SRC_FILES < <(
  {
    find "$SRC/com/deviceagent/adb" -name '*.java' ! -name 'AdbMdns.java'
    find "$SRC/com/deviceagent/crypto" -name '*.java'
    echo "$SRC/com/deviceagent/tools/Json.java"
    echo "$SRC/com/deviceagent/tools/FileChunks.java"
    echo "$SRC/com/deviceagent/tools/InputGuard.java"
    echo "$ROOT/app/app/src/debug/java/com/deviceagent/tools/DeviceCheckGuard.java"
    echo "$SRC/com/deviceagent/tools/IntentCommand.java"
    echo "$SRC/com/deviceagent/tools/UiHierarchy.java"
    echo "$SRC/com/deviceagent/tools/InputNotSentException.java"
    echo "$SRC/com/deviceagent/tools/TextEditPlan.java"
    echo "$SRC/com/deviceagent/tools/FieldText.java"
    echo "$SRC/com/deviceagent/tools/ClipboardResult.java"
    echo "$SRC/com/deviceagent/tools/NotificationRecords.java"
    echo "$SRC/com/deviceagent/tools/GesturePlan.java"
    echo "$SRC/com/deviceagent/tools/UiPredicate.java"
    echo "$SRC/com/deviceagent/tools/EvidenceFlow.java"
    echo "$SRC/com/deviceagent/tools/Observations.java"
    echo "$SRC/com/deviceagent/tools/TargetTap.java"
    echo "$SRC/com/deviceagent/tools/ObservationLayout.java"
    echo "$SRC/com/deviceagent/tools/LocalSequence.java"
    echo "$SRC/com/deviceagent/tools/PrimitiveArguments.java"
    echo "$SRC/com/deviceagent/tools/ToolCatalog.java"
    echo "$SRC/com/deviceagent/tools/JsonSchema.java"
    echo "$SRC/com/deviceagent/tools/ResultFields.java"
    echo "$SRC/com/deviceagent/tools/TaskResources.java"
    echo "$SRC/com/deviceagent/tools/ObservationCondition.java"
    echo "$SRC/com/deviceagent/tools/ActionDeadline.java"
    echo "$SRC/com/deviceagent/tools/LocationFix.java"
    echo "$SRC/com/deviceagent/tools/MediaState.java"
    echo "$SRC/com/deviceagent/tools/TaskSnapshot.java"
    echo "$SRC/com/deviceagent/tools/PrivacyFence.java"
    echo "$SRC/com/deviceagent/tools/ActivityTimeline.java"
    echo "$SRC/com/deviceagent/tools/PendingConfirmations.java"
    echo "$SRC/com/deviceagent/tools/SharedInbox.java"
    echo "$SRC/com/deviceagent/tools/DevDiagnostics.java"
    echo "$SRC/com/deviceagent/app/BuildInfo.java"
    echo "$SRC/com/deviceagent/app/AccountClient.java"
    echo "$SRC/com/deviceagent/app/SetupRules.java"
    echo "$SRC/com/deviceagent/app/LocationAccess.java"
    echo "$SRC/com/deviceagent/app/ConnectionRecovery.java"
    echo "$SRC/com/deviceagent/app/RelayLinker.java"
    echo "$SRC/com/deviceagent/rpc/SimpleHttp.java"
    echo "$SRC/com/deviceagent/daemon/DaemonConfig.java"
    echo "$SRC/com/deviceagent/daemon/DaemonLog.java"
    echo "$SRC/com/deviceagent/daemon/Starter.java"
    echo "$SRC/com/deviceagent/protocol/ShellCommand.java"
    echo "$SRC/com/deviceagent/shell/WifiTrustRules.java"
  } | sort
)
mapfile -t TEST_FILES < <(find "$TEST" -name '*.java' | sort)

echo "javac $(printf '%s\n' "${SRC_FILES[@]}" "${TEST_FILES[@]}" | wc -l) files -> $OUT"
javac --release 17 -encoding UTF-8 -d "$OUT" "${SRC_FILES[@]}" "${TEST_FILES[@]}"

echo "running HostTests"
java -cp "$OUT" \
  -Dbackstage.vector="$VECTOR" \
  -Dbackstage.vectors.out="$VECTORS_OUT" \
  -Dbackstage.fixtures="$TEST/fixtures" \
  com.deviceagent.adb.HostTests "$VECTOR" "$VECTORS_OUT"
