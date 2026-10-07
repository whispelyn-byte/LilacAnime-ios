#!/bin/sh
set -eu
APP_PATH="$1"
IPA_PATH="$2"
test -d "$APP_PATH"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/Payload"
ditto "$APP_PATH" "$STAGE/Payload/LilacAnime.app"
ditto -c -k --norsrc --keepParent "$STAGE/Payload" "$IPA_PATH"
python3 - "$IPA_PATH" <<'PY'
import plistlib, sys, zipfile, struct
with zipfile.ZipFile(sys.argv[1]) as z:
    root = "Payload/LilacAnime.app/"
    info = plistlib.loads(z.read(root + "Info.plist"))
    assert "iPhoneOS" in info["CFBundleSupportedPlatforms"], "Simulator apps cannot be sideloaded"
    binary = z.read(root + info["CFBundleExecutable"])
    assert binary[:4] == b"\xcf\xfa\xed\xfe", "Expected a 64-bit device Mach-O"
    assert struct.unpack("<I", binary[4:8])[0] == 0x0100000c, "Expected arm64"
    assert not any(n.startswith("__MACOSX/") for n in z.namelist())
    print("Verified IPA:", sys.argv[1], info["CFBundleIdentifier"], "arm64 iPhoneOS")
PY
