# Fixtures

- gemma4-template.txt: Google gemma-4-E4B-it chat template, captured from its Hugging Face repository, used to exercise the actual Jinja interpreter and disable thinking.
- subtitle.7z: py7zr LZMA2 archive containing an authored Korean SRT at nested/한국어.srt.
- subtitle.rar: authored, uncompressed RAR 5 archive containing the same caption. Header layout follows https://www.rarlab.com/technote.htm; CRC32 covers header and file bytes.
- subtitle-cp949.zip: authored ZIP with a CP949 Korean filename (UTF-8 flag clear) and ../../escape.srt. Tests require flattened extraction into the requested directory.
- stories260K.gguf: fetched at verification time by prepare-test-model.py; ignored by Git and excluded from the application bundle. Commit and SHA-256 are pinned there.
