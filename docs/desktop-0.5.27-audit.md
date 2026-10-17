# Desktop 0.5.27 port

Upstream: `0640d054f0424b9923f0f2f19c6f081df6893d95` after `f04138c` (0.5.26).

- Imported GGUF files expose per-file input mode (auto/chat/translation), source language (auto/ja/en/zh/ko), and custom prompt settings. Options persist in app preferences and remain associated with the filename after reimport.
- Auto detects source_lang_code and target_lang_code in the GGUF chat template without relying on the model filename. Explicit chat/translation modes override detection; custom prompts override auto mode.
- Native Jinja receives the untouched source as one user message, type=text, source_lang_code and target_lang_code=ko for translation mode. The translation language is cleared before ordinary chat generation to prevent cross-request leakage.
- Auto source detection follows desktop (kana/Han -> ja, Hangul -> ko, otherwise en); Chinese can be selected explicitly.
- Custom prompt substitutions are single-pass: {text}, {source_lang}, {target_lang}. Placeholders inside subtitle source text remain literal. Prompts require {text} and at most 8000 UTF-16 code units.
- Language-template output with letters but no Hangul is retried up to three times and cannot replace the original as a successful Korean translation.
- Per-model input options participate in the translation cache identity. Existing preferences without the new optional field retain automatic defaults.

Validation: desktop input tests 6/6 passed. Added eight iOS tests covering template detection, manual overrides, language detection, literal substitution, validation, persistence/cache migration, output acceptance, and actual native Jinja language globals. Final verification passed on `ec96e02c97ce2a7f9006e8631a99a5f71535fe02`:

- Kotlin JVM: 129 tests, zero failures.
- iOS unit tests: 170 tests, zero failures (including all eight new local-input tests).
- iPhone UI: 6 tests, zero failures; iPad UI: 4 tests, zero failures.
- Universal device build and arm64 iPhoneOS IPA packaging succeeded.
- Actions: https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/38998412699

This is a main-branch port; no new release tag was created. The CI artifact retains 0.4.17/build 59.
