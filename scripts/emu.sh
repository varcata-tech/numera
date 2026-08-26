#!/usr/bin/env bash
# Drive the local test emulator. See "Emulator" in CLAUDE.md.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$JAVA_HOME/bin:$PATH"

AVD=numera-api36
# The debug build carries applicationIdSuffix ".debug", so the installed package is NOT
# app.numera.calculator. Launching the unsuffixed id gives "No activities found to run".
PKG=app.numera.calculator.debug
ACT=app.numera.calculator.MainActivity
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Another project's emulator may well be running alongside ours. Every adb call and the
# Gradle install task must be pinned to OUR serial, or an unlucky day installs the debug
# build onto someone else's device — or fails outright with "more than one device/emulator".
serial() {
  local s
  for s in $(adb devices | awk '/^emulator-/ {print $1}'); do
    if [ "$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ]; then
      echo "$s"; return 0
    fi
  done
  return 1
}

boot() {
  if ! ANDROID_SERIAL="$(serial)"; then
    emulator -avd "$AVD" -no-boot-anim >/tmp/numera-emulator.log 2>&1 &
    until ANDROID_SERIAL="$(serial)"; do sleep 2; done
  fi
  export ANDROID_SERIAL
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
}

pin() {
  ANDROID_SERIAL="$(serial)" || { echo "$AVD is not running — try: $0 start" >&2; exit 1; }
  export ANDROID_SERIAL
}

case "${1:-run}" in
  start) boot; echo "$AVD ready on $ANDROID_SERIAL" ;;
  run)
    boot
    (cd "$ROOT" && ./gradlew :app:installDebug)
    adb shell am start -n "$PKG/$ACT" >/dev/null
    echo "launched $PKG"
    ;;
  shot)
    pin
    # Screenshots are the only way to check what JVM tests cannot: RTL mirroring, locale
    # digits, and that the release licence screen survived R8.
    out="${2:-$ROOT/build/shot.png}"; mkdir -p "$(dirname "$out")"
    adb exec-out screencap -p > "$out"; echo "$out"
    ;;
  lang)
    pin
    # Empty second arg clears the override and returns the app to the system language.
    adb shell cmd locale set-app-locales "$PKG" --locales "${2:-}"
    adb shell am force-stop "$PKG"; adb shell am start -n "$PKG/$ACT" >/dev/null
    echo "locale=${2:-<system>}"
    ;;
  fresh) pin; adb shell pm clear "$PKG"; adb shell am start -n "$PKG/$ACT" >/dev/null ;;
  log)   pin; adb logcat -d | grep -iE "FATAL|AndroidRuntime|numera" | tail -40 ;;
  stop)  pin; adb emu kill 2>/dev/null || true ;;
  *) echo "usage: $0 {start|run|shot [path]|lang [tag]|fresh|log|stop}" >&2; exit 2 ;;
esac
