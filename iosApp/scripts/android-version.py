"""Read the Android version, optionally apply it to an unsigned device app."""
import argparse
from pathlib import Path
import plistlib
import re

parser = argparse.ArgumentParser()
parser.add_argument("--version", action="store_true")
parser.add_argument("--build", action="store_true")
parser.add_argument("--tag", action="store_true")
parser.add_argument("--app", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
module = (root / "app/module.toml").read_text(encoding="utf-8")
version = re.search(r'^versionName\s*=\s*"(\d+\.\d+\.\d+)"', module, re.M).group(1)
build = re.search(r"^versionCode\s*=\s*(\d+)", module, re.M).group(1)
revision = int((root / "iosApp/revision.txt").read_text().strip())
build = str(int(build) + revision)
if args.app:
    path = args.app / "Info.plist"
    info = plistlib.loads(path.read_bytes())
    assert info["CFBundleIdentifier"] == "com.lilac.anime.ios"
    assert info.get("DTPlatformName") == "iphoneos"
    assert not (args.app / "_CodeSignature").exists(), "Only unsigned apps may be repackaged"
    info["CFBundleShortVersionString"] = version
    info["CFBundleVersion"] = build
    path.write_bytes(plistlib.dumps(info, fmt=plistlib.FMT_BINARY))
    print("Applied Android version:", version, "build", build)
elif args.tag:
    print("v" + version + ("-ios." + str(revision) if revision else ""))
elif args.version:
    print(version)
elif args.build:
    print(build)
else:
    parser.error("Choose --version, --build or --app")
