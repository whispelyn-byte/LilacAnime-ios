#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TARGET="$ROOT/iosApp/Native/Frameworks/llama.xcframework"
if [ -f "$TARGET/Info.plist" ] && python3 - "$TARGET/Info.plist" <<'PY'
import plistlib, sys
libraries = plistlib.load(open(sys.argv[1], "rb"))["AvailableLibraries"]
sys.exit(0 if any(v.get("SupportedPlatform") == "ios" and v.get("SupportedPlatformVariant") == "simulator"
                 and {"arm64", "x86_64"}.issubset(v.get("SupportedArchitectures", [])) for v in libraries) else 1)
PY
then exit 0; fi
mkdir -p "$ROOT/iosApp/build/native" "$ROOT/iosApp/Native/Frameworks"
cd "$ROOT/iosApp/build/native"
PIN=9c2e0e491a822adae1f0b1c831adb4160057d24f
if [ ! -d llama-source/.git ]; then
  git clone --depth 1 --branch b11490 https://github.com/ggml-org/llama.cpp.git llama-source
fi
cd llama-source
test "$(git rev-parse HEAD)" = "$PIN"
# Build the device slice and a universal simulator slice from the same source.
python3 - <<'PY'
from pathlib import Path
import subprocess
p=Path("build-xcframework.sh")
# Reset patches left by an earlier arm64-only local checkout.
original = subprocess.check_output(["git", "show", "HEAD:build-xcframework.sh"], text=True)
s=original.replace("IOS_MIN_OS_VERSION=16.4", "IOS_MIN_OS_VERSION=16.0").replace("MAX_PARALLEL_BUILDS=1", "MAX_PARALLEL_BUILDS=2")
p.write_text(s)
PY
bash build-xcframework.sh ios-sim ios-device
rm -rf "$TARGET"
cp -R build-apple/llama.xcframework "$TARGET"
cp LICENSE "$ROOT/iosApp/Native/Frameworks/llama-LICENSE"
