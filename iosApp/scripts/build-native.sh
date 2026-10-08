#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TARGET="$ROOT/iosApp/Native/Frameworks/llama.xcframework"
if [ -f "$TARGET/Info.plist" ]; then exit 0; fi
mkdir -p "$ROOT/iosApp/build/native" "$ROOT/iosApp/Native/Frameworks"
cd "$ROOT/iosApp/build/native"
PIN=9c2e0e491a822adae1f0b1c831adb4160057d24f
if [ ! -d llama-source/.git ]; then
  git clone --depth 1 --branch b11490 https://github.com/ggml-org/llama.cpp.git llama-source
fi
cd llama-source
test "$(git rev-parse HEAD)" = "$PIN"
# Official release has no simulator slice. Build both arm64 slices from the same source.
python3 - <<'PY'
from pathlib import Path
p=Path("build-xcframework.sh")
s=p.read_text().replace("IOS_MIN_OS_VERSION=16.4", "IOS_MIN_OS_VERSION=16.0")
s=s.replace("MAX_PARALLEL_BUILDS=1", "MAX_PARALLEL_BUILDS=2")
s=s.replace("arm64;x86_64", "arm64").replace('archs="arm64 x86_64"', 'archs="arm64"')
p.write_text(s)
PY
bash build-xcframework.sh ios-sim ios-device
cp -R build-apple/llama.xcframework "$TARGET"
cp LICENSE "$ROOT/iosApp/Native/Frameworks/llama-LICENSE"
