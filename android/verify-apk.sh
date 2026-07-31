#!/usr/bin/env bash
#
# Sanity-checks a built APK.
#
# A green Gradle build does not by itself prove the emulator made it into the package:
# a wrong rename or packaging flag would still produce a perfectly valid APK, just one
# with no box64 in it, and the problem would only surface on device as
# "Box64 is missing from this build".
#
# Usage: android/verify-apk.sh [path/to/app.apk]
#
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/marathondroid-debug.apk}"

if [ ! -f "$APK" ]; then
    echo "error: no APK at $APK" >&2
    exit 1
fi

echo "Checking $APK"

# --- box64 must be packaged as a native library -------------------------------------
#
# Android only extracts (and only permits exec() of) files matching lib*.so from the
# APK's native library folder, which is why the box64 executable is named libbox64.so.
if ! unzip -l "$APK" | grep -q "lib/arm64-v8a/libbox64.so"; then
    echo "error: libbox64.so is missing - the launcher would have no emulator." >&2
    echo "packaged native libraries:" >&2
    unzip -l "$APK" | grep "lib/arm64-v8a/" >&2 || echo "  (none)" >&2
    exit 1
fi

echo "  found lib/arm64-v8a/libbox64.so"

# --- and it must still be an ARM64 executable, despite the .so name -----------------
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

unzip -o -q "$APK" "lib/arm64-v8a/libbox64.so" -d "$workdir"
extracted="$workdir/lib/arm64-v8a/libbox64.so"

if command -v file > /dev/null 2>&1; then
    description="$(file -b "$extracted")"
    echo "  $description"

    case "$description" in
        *ARM\ aarch64*) ;;
        *) echo "error: libbox64.so is not an ARM64 binary." >&2; exit 1 ;;
    esac
else
    # Fall back to reading the ELF header directly: e_machine == 0xB7 (AArch64).
    machine="$(od -An -tx1 -j18 -N2 "$extracted" | tr -d ' \n')"

    if [ "$machine" != "b700" ]; then
        echo "error: libbox64.so is not an ARM64 ELF (e_machine=$machine)." >&2
        exit 1
    fi

    echo "  ELF e_machine=0xB7 (AArch64)"
fi

# --- the launcher's own JNI library ---------------------------------------------------
if ! unzip -l "$APK" | grep -q "lib/arm64-v8a/libmarathondroid.so"; then
    echo "error: libmarathondroid.so is missing - the input bridge would not load." >&2
    exit 1
fi

echo "  found lib/arm64-v8a/libmarathondroid.so"

echo
echo "Packaged native libraries:"
unzip -l "$APK" | grep "lib/arm64-v8a/" || true

echo
echo "APK looks good."
