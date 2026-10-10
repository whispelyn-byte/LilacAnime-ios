"""Exercise release metadata validation without changing the working tree."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ReleaseVersionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        scripts = self.root / "iosApp/scripts"
        scripts.mkdir(parents=True)
        (self.root / "app").mkdir()
        (self.root / "app/module.toml").write_text('versionName = "0.4.0"\nversionCode = 30\n')
        (self.root / "iosApp/revision.txt").write_text("9\n")
        self.project = self.root / "iosApp/project.yml"
        self.project.write_text('    MARKETING_VERSION: "0.4.0"\n    CURRENT_PROJECT_VERSION: "39"\n')
        self.script = scripts / "android-version.py"
        shutil.copyfile(Path(__file__).with_name("android-version.py"), self.script)

    def run_check(self, *arguments):
        return subprocess.run([sys.executable, str(self.script), "--check", *arguments],
                              capture_output=True, text=True)

    def test_matching_release(self):
        result = self.run_check("--release-tag", "v0.4.0-ios.9")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("build 39", result.stdout)

    def test_stale_revision_fails_before_build(self):
        (self.root / "iosApp/revision.txt").write_text("8\n")
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CURRENT_PROJECT_VERSION mismatch", result.stderr)
        self.assertIn("revision=38", result.stderr)

    def test_wrong_marketing_version(self):
        self.project.write_text(self.project.read_text().replace('"0.4.0"', '"0.5.0"'))
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MARKETING_VERSION mismatch", result.stderr)

    def test_wrong_release_tag_reports_expected_tag(self):
        result = self.run_check("--release-tag", "v0.4.0-ios.6")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected v0.4.0-ios.9", result.stderr)

    def test_original_android_release_has_no_ios_suffix(self):
        (self.root / "iosApp/revision.txt").write_text("0\n")
        self.project.write_text(self.project.read_text().replace('"39"', '"30"'))
        result = self.run_check("--release-tag", "v0.4.0")
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
