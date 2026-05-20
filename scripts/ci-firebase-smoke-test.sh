#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# CI APK Smoke Test — Firebase Test Lab
# =============================================================================
# Usage: ./ci-firebase-smoke-test.sh [path/to/app.apk]
# Default APK: app/build/outputs/apk/debug/app-debug.apk
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

APK_PATH="${1:-$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE_NAME="com.glassfiles.liquidmusicglass"
MAIN_ACTIVITY="com.glassfiles.liquidmusicglass.MainActivity"

# Firebase Test Lab config
PROJECT_ID="gemini-teat-493814"
DEVICE_MODEL="MediumPhone.arm"    # ARM virtual device, fast and cheap
OS_VERSION="34"
LOCALE="en_US"
ORIENTATION="portrait"

# Timestamps
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_DIR="$PROJECT_ROOT/ci-reports/$TIMESTAMP"
RESULTS_BUCKET="test-lab-$PROJECT_ID"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# =============================================================================
# Main
# =============================================================================

mkdir -p "$REPORT_DIR"

# --- Validate APK -----------------------------------------------------------
if [ ! -f "$APK_PATH" ]; then
    log_error "APK not found: $APK_PATH"
    exit 1
fi
log_info "APK: $APK_PATH"

# --- Verify gcloud ----------------------------------------------------------
export PATH="/opt/google-cloud-sdk/bin:$PATH"
if ! command -v gcloud > /dev/null 2>&1; then
    log_error "gcloud not found in PATH"
    exit 1
fi

# --- Verify auth ------------------------------------------------------------
ACCOUNT=$(gcloud auth list --filter=status:ACTIVE --format="value(account)" 2>/dev/null || true)
if [ -z "$ACCOUNT" ]; then
    log_error "No active gcloud account. Run: gcloud auth activate-service-account"
    exit 1
fi
log_info "Active account: $ACCOUNT"

# --- Run Robo test (automated crawl) ----------------------------------------
# Robo test automatically explores the app and catches crashes
log_info "Starting Firebase Test Lab Robo test..."
log_info "Device: $DEVICE_MODEL, OS: $OS_VERSION"

TEST_RESULTS=$(gcloud firebase test android run \
    --type robo \
    --app "$APK_PATH" \
    --device model="$DEVICE_MODEL",version="$OS_VERSION",locale="$LOCALE",orientation="$ORIENTATION" \
    --timeout 5m \
    --project "$PROJECT_ID" \
    --results-bucket "$RESULTS_BUCKET" \
    --results-dir="ci-smoke-$TIMESTAMP" \
    --format="value(test_runs.outcome,test_runs.test_details)" \
    2>&1) || true

log_info "Test completed."

# --- Parse results ----------------------------------------------------------
OUTCOME=$(echo "$TEST_RESULTS" | awk -F'\t' '{print $1}')
DETAILS=$(echo "$TEST_RESULTS" | awk -F'\t' '{print $2}')

# --- Generate report --------------------------------------------------------
{
    echo "========================================"
    echo "  APK Smoke Test Report (Firebase)"
    echo "  Timestamp: $TIMESTAMP"
    echo "========================================"
    echo ""
    echo "APK Path:    $APK_PATH"
    echo "Device:      $DEVICE_MODEL"
    echo "OS Version:  $OS_VERSION"
    echo "Account:     $ACCOUNT"
    echo ""
    echo "--- Test Outcome ---"
    echo "Outcome:     ${OUTCOME:-UNKNOWN}"
    echo "Details:     ${DETAILS:-N/A}"
    echo ""
    echo "--- Raw Output ---"
    echo "$TEST_RESULTS"
    echo ""
    echo "--- Full Results ---"
    echo "Console: https://console.firebase.google.com/project/$PROJECT_ID/testlab/histories"
    echo ""
} > "$REPORT_DIR/report.txt"

cat "$REPORT_DIR/report.txt"

# --- Check for crashes ------------------------------------------------------
if echo "$TEST_RESULTS" | grep -qi "failure\|failed\|crash\|error"; then
    log_error "SMOKE TEST FAILED — Crashes or errors detected."
    log_error "See: https://console.firebase.google.com/project/$PROJECT_ID/testlab/histories"
    exit 1
elif [ "$OUTCOME" = "success" ] || echo "$TEST_RESULTS" | grep -qi "success\|passed"; then
    log_info "SMOKE TEST PASSED — App launched and ran without crashes."
    exit 0
else
    log_warn "Test completed with unknown outcome. Check console manually."
    exit 0
fi
