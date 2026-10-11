"""Read iOS release metadata, retaining the Android versionCode build baseline."""
import argparse
from pathlib import Path
import plistlib
import re

parser = argparse.ArgumentParser()
parser.add_argument("--version", action="store_true")
parser.add_argument("--build", action="store_true")
parser.add_argument("--tag", action="store_true")
parser.add_argument("--check", action="store_true")
parser.add_argument("--release-tag")
parser.add_argument("--app", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
module = (root / "app/module.toml").read_text(encoding="utf-8")
version = (root / "iosApp/version.txt").read_text().strip()
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise SystemExit("iosApp/version.txt must contain a three-part iOS app version")
build = re.search(r"^versionCode\s*=\s*(\d+)", module, re.M).group(1)
revision = int((root / "iosApp/revision.txt").read_text().strip())
build = str(int(build) + revision)
tag = "v" + version + ("-ios." + str(revision) if revision else "")
if args.check:
    project = (root / "iosApp/project.yml").read_text(encoding="utf-8")
    for key, expected in [("MARKETING_VERSION", version), ("CURRENT_PROJECT_VERSION", build)]:
        match = re.search(r'^\s*' + key + r':\s*"([^"\n]+)"\s*$', project, re.M)
        actual = match.group(1) if match else "missing"
        if actual != expected:
            raise SystemExit(f"{key} mismatch: project.yml={actual}, Android + iOS revision={expected}")
    if args.release_tag is not None and args.release_tag != tag:
        raise SystemExit(f"Release tag mismatch: received {args.release_tag}, expected {tag} (iOS revision {revision})")
    print(f"Verified version {version}, build {build}, release tag {tag}")
elif args.release_tag is not None:
    parser.error("--release-tag requires --check")
elif args.app:
    path = args.app / "Info.plist"
    info = plistlib.loads(path.read_bytes())
    assert info["CFBundleIdentifier"] == "com.lilac.anime.ios"
    assert info.get("DTPlatformName") == "iphoneos"
    assert not (args.app / "_CodeSignature").exists(), "Only unsigned apps may be repackaged"
    info["CFBundleShortVersionString"] = version
    info["CFBundleVersion"] = build
    path.write_bytes(plistlib.dumps(info, fmt=plistlib.FMT_BINARY))
    print("Applied iOS version:", version, "build", build)
elif args.tag:
    print(tag)
elif args.version:
    print(version)
elif args.build:
    print(build)
else:
    parser.error("Choose --version, --build, --tag, --check or --app")
