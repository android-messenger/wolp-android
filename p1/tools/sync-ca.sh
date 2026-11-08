#!/usr/bin/env bash
# Copies the stand's test root CA into the debug APK resources (trust anchor for debug builds only).
set -euo pipefail
P1="$(cd "$(dirname "$0")/.." && pwd)"
cp "$P1/stand/pki/out/root.pem" "$P1/app/android/app/src/debug/res/raw/p1_test_root.pem"
echo "copied root.pem -> app/src/debug/res/raw/p1_test_root.pem"
