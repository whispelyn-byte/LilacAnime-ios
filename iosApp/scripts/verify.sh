#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"
if [ "$(uname -m)" != arm64 ]; then
  echo "This simulator target requires an Apple Silicon Mac." >&2
  exit 1
fi
command -v xcodegen >/dev/null
sh ./gradlew :shared:iosSimulatorArm64Test
cd iosApp
xcodegen generate
mkdir -p build
xcodebuild -resolvePackageDependencies -project LilacAnime.xcodeproj -scheme LilacAnime
SIMULATOR_ID="$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); phones=[v for group in d["devices"].values() for v in group if v.get("isAvailable") and "iPhone" in v["name"]]; assert phones, "No available iPhone simulator"; print(phones[0]["udid"])')"
RESULT="build/Tests-$(date +%Y%m%d-%H%M%S).xcresult"
xcodebuild -project LilacAnime.xcodeproj -scheme LilacAnime -configuration Debug -derivedDataPath build/DerivedData -destination "platform=iOS Simulator,id=$SIMULATOR_ID" -resultBundlePath "$RESULT" CODE_SIGNING_ALLOWED=NO test
xcodebuild -project LilacAnime.xcodeproj -scheme LilacAnime -configuration Debug -derivedDataPath build/DerivedData -destination "generic/platform=iOS" CODE_SIGNING_ALLOWED=NO build

ditto -c -k --sequesterRsrc --keepParent build/DerivedData/Build/Products/Debug-iphonesimulator/LilacAnime.app build/LilacAnime-simulator.zip
ditto -c -k --sequesterRsrc --keepParent build/DerivedData/Build/Products/Debug-iphoneos/LilacAnime.app build/LilacAnime-device-unsigned.zip
