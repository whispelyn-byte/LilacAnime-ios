#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT/iosApp"
sh scripts/build-native.sh
swift scripts/build-icons.swift
xcodegen generate
APP_VERSION="$(python3 scripts/android-version.py --version)"
APP_BUILD="$(python3 scripts/android-version.py --build)"
xcodebuild -project LilacAnime.xcodeproj -scheme LilacAnime -configuration Debug \
  -derivedDataPath build/DerivedData-Intel -destination 'generic/platform=iOS Simulator' \
  ARCHS=x86_64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO \
  MARKETING_VERSION="$APP_VERSION" CURRENT_PROJECT_VERSION="$APP_BUILD" build
APP=build/DerivedData-Intel/Build/Products/Debug-iphonesimulator/LilacAnime.app
xcrun lipo "$APP/LilacAnime" -verify_arch x86_64
ditto -c -k --sequesterRsrc --keepParent "$APP" build/LilacAnime-simulator-intel.zip
