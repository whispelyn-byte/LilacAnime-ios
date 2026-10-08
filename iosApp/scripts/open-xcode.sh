#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"
if [ "$(uname -s)" != Darwin ]; then
  echo "Run this inside macOS, with Xcode installed." >&2; exit 1
fi
command -v xcodegen >/dev/null || { echo "Install XcodeGen: brew install xcodegen" >&2; exit 1; }
java -version
xcodebuild -version
sh iosApp/scripts/build-native.sh
cd iosApp
xcodegen generate
open LilacAnime.xcodeproj
echo "Select LilacAnime and an iPhone Simulator in Xcode, then press Cmd+R."
