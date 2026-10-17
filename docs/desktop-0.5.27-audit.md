# Desktop 0.5.27 port

Upstream: `0640d05` after `f04138c` (0.5.26).

- Imported GGUF files expose per-file input mode (auto/chat/translation), source language (auto/ja/en/zh/ko), and custom prompt settings. Options persist in app preferences and remain associated with the filename after reimport.
- Auto detects source_lang_code and target_lang_code in the GGUF chat template without relying on the model filename. Explicit chat/translation modes override detection; custom prompts override auto mode.
- Native Jinja receives the untouched source as one user message, type=text, source_lang_code and target_lang_code=ko for translation mode. The translation language is cleared before ordinary chat generation to prevent cross-request leakage.
- Auto source detection follows desktop (kana/Han -> ja, Hangul -> ko, otherwise en); Chinese can be selected explicitly.
- Custom prompt substitutions are single-pass: {text}, {source_lang}, {target_lang}. Placeholders inside subtitle source text remain literal. Prompts require {text} and at most 8000 UTF-16 code units.
- Language-template output with letters but no Hangul is retried up to three times and cannot replace the original as a successful Korean translation.
- Per-model input options participate in the translation cache identity. Existing preferences without the new optional field retain automatic defaults.

Validation: desktop input tests 6/6 passed. Added eight iOS tests covering template detection, manual overrides, language detection, literal substitution, validation, persistence/cache migration, output acceptance, and actual native Jinja language globals. Final iOS build verification pending.
