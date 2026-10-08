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
APP_VERSION="$(python3 scripts/android-version.py --version)"
APP_BUILD="$(python3 scripts/android-version.py --build)"
if [ "${GITHUB_REF_TYPE:-}" = tag ]; then
  test "$GITHUB_REF_NAME" = "$(python3 scripts/android-version.py --tag)"
  APP_VERSION="${GITHUB_REF_NAME#v}"
  APP_VERSION="${APP_VERSION%%-ios.*}"
  python3 -c 'import re,sys; assert re.fullmatch(r"\d+\.\d+(?:\.\d+)?", sys.argv[1]), "Invalid release version"' "$APP_VERSION"
  test "$APP_VERSION" = "$(python3 scripts/android-version.py --version)"
fi
sh scripts/build-native.sh
python3 scripts/prepare-test-model.py
xcodegen generate
mkdir -p build
for ATTEMPT in 1 2 3; do
  if xcodebuild -resolvePackageDependencies -project LilacAnime.xcodeproj -scheme LilacAnime -derivedDataPath build/DerivedData; then
    break
  fi
  if [ "$ATTEMPT" = 3 ]; then exit 1; fi
  sleep 5
done
SIMULATOR_ID="$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); phones=[v for group in d["devices"].values() for v in group if v.get("isAvailable") and "iPhone" in v["name"]]; assert phones, "No available iPhone simulator"; print(phones[0]["udid"])')"
RESULT="build/Tests-$(date +%Y%m%d-%H%M%S).xcresult"
xcodebuild -project LilacAnime.xcodeproj -scheme LilacAnime -configuration Debug -derivedDataPath build/DerivedData -destination "platform=iOS Simulator,id=$SIMULATOR_ID" -resultBundlePath "$RESULT" CODE_SIGNING_ALLOWED=NO MARKETING_VERSION="$APP_VERSION" CURRENT_PROJECT_VERSION="$APP_BUILD" test
mkdir -p build/screenshots
xcrun simctl bootstatus "$SIMULATOR_ID" -b
xcrun simctl install "$SIMULATOR_ID" build/DerivedData/Build/Products/Debug-iphonesimulator/LilacAnime.app
xcrun simctl status_bar "$SIMULATOR_ID" override --time '9:41' --batteryState charged --batteryLevel 100
for SCREEN in home detail player; do
  xcrun simctl ui "$SIMULATOR_ID" appearance dark
  xcrun simctl launch --terminate-running-process "$SIMULATOR_ID" com.lilac.anime.ios --ui-preview "$SCREEN"
  sleep 3
  xcrun simctl io "$SIMULATOR_ID" screenshot "build/screenshots/$SCREEN-dark.png"
done
xcrun simctl ui "$SIMULATOR_ID" appearance light
xcrun simctl launch --terminate-running-process "$SIMULATOR_ID" com.lilac.anime.ios --ui-preview home
sleep 3
xcrun simctl io "$SIMULATOR_ID" screenshot build/screenshots/home-light.png
xcrun simctl terminate "$SIMULATOR_ID" com.lilac.anime.ios
IPAD_ID="$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); pads=[v for group in d["devices"].values() for v in group if v.get("isAvailable") and "iPad" in v["name"]]; print(pads[0]["udid"] if pads else "")')"
if [ -n "$IPAD_ID" ]; then
  xcrun simctl boot "$IPAD_ID" || true
  xcrun simctl bootstatus "$IPAD_ID" -b
  xcrun simctl install "$IPAD_ID" build/DerivedData/Build/Products/Debug-iphonesimulator/LilacAnime.app
  xcrun simctl ui "$IPAD_ID" appearance dark
  xcrun simctl launch --terminate-running-process "$IPAD_ID" com.lilac.anime.ios --ui-preview workspace
  sleep 3
  xcrun simctl io "$IPAD_ID" screenshot build/screenshots/workspace-ipad-dark.png
  xcrun simctl terminate "$IPAD_ID" com.lilac.anime.ios
fi
xcodebuild -project LilacAnime.xcodeproj -scheme LilacAnime -configuration Debug -derivedDataPath build/DerivedData -destination "generic/platform=iOS" CODE_SIGNING_ALLOWED=NO MARKETING_VERSION="$APP_VERSION" CURRENT_PROJECT_VERSION="$APP_BUILD" build

ditto -c -k --sequesterRsrc --keepParent build/DerivedData/Build/Products/Debug-iphonesimulator/LilacAnime.app build/LilacAnime-simulator.zip
ditto -c -k --sequesterRsrc --keepParent build/DerivedData/Build/Products/Debug-iphoneos/LilacAnime.app build/LilacAnime-device-unsigned.zip
sh scripts/package-ipa.sh build/DerivedData/Build/Products/Debug-iphoneos/LilacAnime.app build/LilacAnime-SideStore.ipa
