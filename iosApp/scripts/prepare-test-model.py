"""Fetch a tiny, hash-pinned GGUF only into the test bundle, never the app bundle."""
import hashlib
from pathlib import Path
import urllib.request

DESTINATION = Path(__file__).resolve().parents[1] / "LilacAnimeTests" / "Fixtures" / "stories260K.gguf"
URL = "https://huggingface.co/ggml-org/models-moved/resolve/499bc8821c6b12b4e53c5bffcb21ec206f212d81/tinyllamas/stories260K.gguf"
SHA256 = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d"
if not DESTINATION.exists() or hashlib.sha256(DESTINATION.read_bytes()).hexdigest() != SHA256:
    data = urllib.request.urlopen(URL, timeout=60).read(2_000_000)
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError("Test GGUF integrity check failed")
    DESTINATION.write_bytes(data)
print("Tiny GGUF test model verified")
