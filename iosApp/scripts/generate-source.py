"""Generate a SideStore/AltStore source from the actual unsigned IPA."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import plistlib
import re
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("ipa", type=Path)
parser.add_argument("--repo", default="whispelyn-byte/LilacAnime-ios")
parser.add_argument("--tag", required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
if not re.fullmatch(r"v\d+\.\d+(?:\.\d+)?(?:-ios\.\d+)?", args.tag):
    raise SystemExit("Expected a release tag such as v1.0.1")
with zipfile.ZipFile(args.ipa) as archive:
    info = plistlib.loads(archive.read("Payload/LilacAnime.app/Info.plist"))
    if archive.testzip() is not None:
        raise SystemExit("Invalid IPA archive")
version = info["CFBundleShortVersionString"]
if version != args.tag[1:].split("-ios.")[0]:
    raise SystemExit("IPA version does not match release tag")
if info["CFBundleIdentifier"] != "com.lilac.anime.ios" or info.get("DTPlatformName") != "iphoneos":
    raise SystemExit("Expected the LilacAnime iPhoneOS app")
base = "https://github.com/" + args.repo
raw = "https://raw.githubusercontent.com/" + args.repo + "/main/"
entry = {
    "version": version,
    "buildVersion": info["CFBundleVersion"],
    "date": datetime.now(timezone.utc).isoformat(),
    "localizedDescription": "릴리스 페이지에서 변경 사항을 확인하세요.",
    "downloadURL": base + "/releases/download/" + args.tag + "/" + args.ipa.name,
    "size": args.ipa.stat().st_size,
    "minOSVersion": info["MinimumOSVersion"],
}
app = {
    "name": "LilacAnime iOS",
    "bundleIdentifier": info["CFBundleIdentifier"],
    "developerName": "whispelyn-byte · LilacAnime contributors",
    "localizedDescription": "LilacAnime의 KMP/SwiftUI iOS 포트. 작품 탐색, 회차 재생, 자막 검색·번역과 다운로드.",
    "iconURL": raw + "docs/icon.png",
    "tintColor": "B98FD6",
    "screenshots": [raw + "docs/screenshots/" + name + "-dark.png" for name in ["home", "detail", "player"]],
    "versions": [entry],
    # Legacy fields for older source readers.
    "version": entry["version"],
    "versionDate": entry["date"],
    "downloadURL": entry["downloadURL"],
    "size": entry["size"],
    "appPermissions": {
        "entitlements": [],
        "privacy": {key: value for key, value in info.items() if key.endswith("UsageDescription")},
    },
}
source = {
    "name": "LilacAnime iOS",
    "identifier": "io.github.whispelyn-byte.lilacanime-ios",
    "sourceURL": base + "/releases/latest/download/source.json",
    "website": base,
    "tintColor": "B98FD6",
    "apps": [app],
    "news": [],
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Generated source for", version, "build", info["CFBundleVersion"])
