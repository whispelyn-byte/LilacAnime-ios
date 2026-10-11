"""Keep IPA/source versions consistent and catch SideStore build-only update regressions."""
import json
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
import zipfile


class SourceVersionTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.ipa = self.root / "LilacAnime-SideStore.ipa"
        self.previous = self.root / "previous.json"
        self.output = self.root / "source.json"
        self.script = Path(__file__).with_name("generate-source.py")

    def generate(self, version="0.4.4", build="46", previous_version="0.4.3", previous_build="43", previous_tag="v0.4.3-ios.13"):
        info = dict(CFBundleShortVersionString=version, CFBundleVersion=build,
                    CFBundleIdentifier="com.lilac.anime.ios", DTPlatformName="iphoneos", MinimumOSVersion="16.0")
        with zipfile.ZipFile(self.ipa, "w") as archive:
            archive.writestr("Payload/LilacAnime.app/Info.plist", plistlib.dumps(info))
        url = "https://github.com/whispelyn-byte/LilacAnime-ios/releases/download/" + previous_tag + "/" + self.ipa.name
        self.previous.write_text(json.dumps({"apps": [{"bundleIdentifier": info["CFBundleIdentifier"], "versions": [
            {"version": previous_version, "buildVersion": previous_build, "downloadURL": url}]}]}))
        return subprocess.run([sys.executable, str(self.script), str(self.ipa), "--tag", "v0.4.4-ios.16",
                               "--previous-source", str(self.previous), "--output", str(self.output)], capture_output=True, text=True)

    def test_new_version_uses_actual_ipa_metadata(self):
        result = self.generate()
        self.assertEqual(result.returncode, 0, result.stderr)
        entry = json.loads(self.output.read_text(encoding="utf-8"))["apps"][0]["versions"][0]
        self.assertEqual((entry["version"], entry["buildVersion"]), ("0.4.4", "46"))

    def test_build_only_update_is_rejected(self):
        result = self.generate(previous_version="0.4.4", previous_build="45", previous_tag="v0.4.4-ios.15")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Increase iosApp/version.txt", result.stderr)

    def test_older_app_version_is_rejected(self):
        result = self.generate(previous_version="0.4.5")
        self.assertNotEqual(result.returncode, 0)

    def test_older_build_is_rejected(self):
        result = self.generate(previous_build="47")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("build number must increase", result.stderr)

    def test_same_release_can_be_republished(self):
        result = self.generate(previous_version="0.4.4", previous_build="46", previous_tag="v0.4.4-ios.16")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_release_tag_must_match_actual_app_version(self):
        result = self.generate(version="0.4.3")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("IPA version does not match", result.stderr)


if __name__ == "__main__":
    unittest.main()
