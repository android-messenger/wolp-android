#!/usr/bin/env bash
# cdp.sh '<js expression>' — forwards the app's WebView devtools socket and evaluates the expression.
set -euo pipefail
PKG="${PKG:-org.wolpmessenger}"
PID=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$PID" ] || { echo "app not running" >&2; exit 1; }
adb forward tcp:${CDP_PORT:-9222} "localabstract:webview_devtools_remote_$PID" >/dev/null
exec node "$(dirname "$0")/cdp.mjs" "$1"
