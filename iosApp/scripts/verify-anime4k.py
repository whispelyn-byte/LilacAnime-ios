"""Check the pinned, unmodified Anime4K assets before packaging the iOS app."""
import hashlib
import json
from pathlib import Path

folder = Path(__file__).resolve().parents[1] / "LilacAnime/Resources/Anime4K"
manifest = json.loads((folder / "upstream.json").read_text(encoding="utf-8"))
assert manifest["revision"] == "4029bf701ecaa15f163cdc49cffe5501c1acf410"
assert manifest["version"] == "4.0.1"
assert len(manifest["files"]) == 11
names = set()
for entry in manifest["files"]:
    name = entry["name"]
    assert Path(name).name == name and name not in names
    names.add(name)
    data = (folder / name).read_bytes()
    assert hashlib.sha256(data).hexdigest() == entry["sha256"], name + " differs from upstream"
    if name.endswith(".glsl"):
        assert b"//!HOOK MAIN" in data and b"vec4 hook()" in data, name
        assert b"MIT License" in data, name
assert "LICENSE" in names
assert {p.name for p in folder.glob("*.glsl")} == names - {"LICENSE"}
print("Verified Anime4K 4.0.1: ten original shaders and the MIT license.")
