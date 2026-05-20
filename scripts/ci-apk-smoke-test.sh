#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# CI APK Smoke Test — Headless Android Emulator
# =============================================================================
# Usage: ./ci-apk-smoke-test.sh [path/to/app.apk]
# Default APK: app/build/outputs/apk/debug/app-debug.apk
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

APK_PATH="${1:-$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
AVD_NAME="ci-emulator"
PACKAGE_NAME="com.glassfiles.liquidmusicglass"
MAIN_ACTIVITY="com.glassfiles.liquidmusicglass.MainActivity"

# Timestamps
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_DIR="$PROJECT_ROOT/ci-reports/$TIMESTAMP"
SCREENSHOT_PATH="$REPORT_DIR/screenshot.png"
LOGCAT_PATH="$REPORT_DIR/logcat.txt"
REPORT_PATH="$REPORT_DIR/report.txt"

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# =============================================================================
# Helpers
# =============================================================================

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

wait_for_boot() {
    log_info "Waiting for emulator boot (sys.boot_completed)..."
    local attempts=0
    local max_attempts=60
    while true; do
        local boot_status
        boot_status=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || true
        if [ "$boot_status" = "1" ]; then
            log_info "Emulator booted successfully."
            return 0
        fi
        attempts=$((attempts + 1))
        if [ $attempts -ge $max_attempts ]; then
            log_error "Emulator failed to boot within ${max_attempts}s"
            return 1
        fi
        echo -n "."
        sleep 1
    done
}

kill_emulator() {
    log_info "Stopping emulator..."
    adb emu kill 2>/dev/null || true
    # Also kill any lingering emulator processes
    pkill -f "emulator.*$AVD_NAME" 2>/dev/null || true
    sleep 2
}

cleanup() {
    log_info "Cleanup..."
    kill_emulator
}

# =============================================================================
# Main
# =============================================================================

trap cleanup EXIT

mkdir -p "$REPORT_DIR"

# --- Validate APK exists -----------------------------------------------------
if [ ! -f "$APK_PATH" ]; then
    log_error "APK not found: $APK_PATH"
    exit 1
fi
log_info "APK: $APK_PATH"

# --- Verify Android SDK ------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then
    log_error "ANDROID_HOME is not set"
    exit 1
fi

export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

if ! command -v emulator >/dev/null 2>&1; then
    log_error "emulator not found in PATH"
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    log_error "adb not found in PATH"
    exit 1
fi

# --- Kill any existing emulator ----------------------------------------------
kill_emulator

# --- Start headless emulator -------------------------------------------------
log_info "Starting headless emulator (AVD: $AVD_NAME)..."
emulator -avd "$AVD_NAME" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    -no-snapshot-save \
    -memory 2048 \
    -cores 2 \
    > "$REPORT_DIR/emulator.log" 2>&1 &

EMULATOR_PID=$!
log_info "Emulator PID: $EMULATOR_PID"

# --- Wait for ADB ------------------------------------------------------------
log_info "Waiting for ADB..."
adb wait-for-device

# --- Wait for full boot ------------------------------------------------------
if ! wait_for_boot; then
    log_error "Boot failed. See $REPORT_DIR/emulator.log"
    exit 1
fi

# Small extra delay for system services
sleep 5

# --- Install APK -------------------------------------------------------------
log_info "Installing APK..."
if ! adb install -r -t "$APK_PATH" > "$REPORT_DIR/install.log" 2>&1; then
    log_error "APK installation failed. See $REPORT_DIR/install.log"
    cat "$REPORT_DIR/install.log"
    exit 1
fi
log_info "APK installed successfully."

# --- Clear logcat before launch ----------------------------------------------
adb logcat -c

# --- Launch MainActivity -----------------------------------------------------
log_info "Launching $PACKAGE_NAME/$MAIN_ACTIVITY ..."
adb shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY" > "$REPORT_DIR/launch.log" 2>&1
sleep 5

# --- Screenshot --------------------------------------------------------------
log_info "Capturing screenshot..."
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png "$SCREENSHOT_PATH" > /dev/null 2>&1
adb shell rm /sdcard/screen.png
log_info "Screenshot saved: $SCREENSHOT_PATH"

# --- Collect logcat ----------------------------------------------------------
log_info "Collecting logcat..."
adb logcat -d > "$LOGCAT_PATH"

# --- Analyze logcat for crashes ----------------------------------------------
log_info "Analyzing logcat for crashes..."

CRASH_FOUND=0
CRASH_DETAILS=""

if grep -q "FATAL EXCEPTION" "$LOGCAT_PATH"; then
    CRASH_FOUND=1
    CRASH_DETAILS="$(grep -A 20 "FATAL EXCEPTION" "$LOGCAT_PATH" | head -30)"
fi

if grep -q "UninitializedPropertyAccessException" "$LOGCAT_PATH"; then
    CRASH_FOUND=1
    CRASH_DETAILS="${CRASH_DETAILS}\n$(grep -A 10 "UninitializedPropertyAccessException" "$LOGCAT_PATH" | head -20)"
fi

if grep -q "AndroidRuntime.*Exception" "$LOGCAT_PATH"; then
    CRASH_FOUND=1
    CRASH_DETAILS="${CRASH_DETAILS}\n$(grep -A 10 "AndroidRuntime.*Exception" "$LOGCAT_PATH" | head -20)"
fi

# --- Generate report ---------------------------------------------------------
{
    echo "========================================"
    echo "  APK Smoke Test Report"
    echo "  Timestamp: $TIMESTAMP"
    echo "========================================"
    echo ""
    echo "APK Path:    $APK_PATH"
    echo "AVD Name:    $AVD_NAME"
    echo "Package:     $PACKAGE_NAME"
    echo ""
    echo "--- Installation ---"
    cat "$REPORT_DIR/install.log"
    echo ""
    echo "--- Launch ---"
    cat "$REPORT_DIR/launch.log"
    echo ""
    echo "--- Crash Analysis ---"
    if [ $CRASH_FOUND -eq 1 ]; then
        echo "RESULT: FAILED — Crash detected!"
        echo ""
        echo "$CRASH_DETAILS"
    else
        echo "RESULT: PASSED — No crashes detected."
    fi
    echo ""
    echo "--- Files ---"
    echo "Screenshot:  $SCREENSHOT_PATH"
    echo "Logcat:      $LOGCAT_PATH"
    echo "Emulator:    $REPORT_DIR/emulator.log"
    echo ""
} > "$REPORT_PATH"

cat "$REPORT_PATH"

# --- Summary -----------------------------------------------------------------
echo ""
if [ $CRASH_FOUND -eq 1 ]; then
    log_error "SMOKE TEST FAILED — App crashed on startup."
    log_error "See full report: $REPORT_PATH"
    exit 1
else
    log_info "SMOKE TEST PASSED — App launched without crashes."
    log_info "Report: $REPORT_PATH"
    exit 0
fi
