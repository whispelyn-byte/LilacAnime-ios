# Desktop 0.5.28–0.5.37 → iOS

Baseline: [12a5712](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/12a5712ffc6e776fdaa320ce0299755c153e1b51). Upstream: [2415d34 / v0.5.37](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/2415d34a), 21 commits. 0.5.28 (7b48a07) only changed package version numbers.

## Subtitle auto sync (0.5.29–0.5.36)

The desktop runs ffsubsync in its own Python (installed by uv). iOS cannot run Python, so the same pipeline is native:

- `SubtitleSync.kt` (shared): the desktop's dialogue selection (`dialogueVtt`: captions, lyrics and top signs left out), the site track's ASS speech styles (`assSpeech`: OP/ED/sign styles and placed lines left out), `fitScore`, `merged`, `keptSegments`, `shiftAt`/`mover`, VTT/SRT/ASS retiming, and the candidate choice with the desktop's floors and margins (track 0.6/0.03, audio 0.3/0.03; segments only 0.02 above the best single shift; the most common shift reported).
- ffsubsync's alignment itself is reimplemented as FFT cross-correlation of ±1 speech signals at 100 Hz: one shift within 60 s, 5 min and the whole episode, the whole file within 5 min, film/PAL frame-rate ratios against a site track only, and segments within 150 s and 5 min. ffsubsync's `--split-penalty` alignment has no direct equivalent; segments come from per-block (12 lines, 60 s) shifts and are then filtered by the desktop's `keptSegments`.
- Audio reference: `LilacSpeechLevels` (NativeAudio.m) decodes the episode or stream, takes the centre of the mix with the same `dialoguenhance=original=0:enhance=3,pan=mono|c0=FC` filter (plain mono if the filter is unavailable), and writes the 300–3400 Hz level every 10 ms. `speechFromLevels` replaces WebRTC VAD with a local-noise-floor threshold. Its output differs from WebRTC VAD; the desktop's scoring safeguards still decide whether anything changes.
- Player: every subtitle put on is checked once (자동으로 맞추기, on by default), queued behind a running sync, never re-run for a synced copy or for a translation whose source was checked. A change is saved as "… · 자동 싱크", resets a manual 자막 타이밍 to 0, and shows 되돌리기. A subtitle still being translated keeps the fix and the translation's lines are shown retimed; the finished translation is retimed and saved. The 자막 tab has 음성에 맞춰 싱크 맞추기 (press again to cancel).
- Not applicable: uv/ffsubsync installation and its macOS check workflow.

## Translation (0.5.29–0.5.32)

- 번역 프롬프트: 추가 지시사항 and 고급: 번역 규칙 전체 교체 (4000 characters, 기본 규칙 불러오기, 초기화) reach Gemini/OpenAI/Qwen (`DesktopCloudPrompt.build`) and the local Gemma/general/chat requests (`DesktopTranslationPrompt.request`), not DeepL or HY-MT/Hy-MT2/ja-ko-vn. They are part of the cache identity only when set, so existing translations stay valid.
- 효과음·화자 이름 빼고 번역: `DesktopCaptions.drop` (desktop `dropCaptions`); a line left empty is omitted from the translated file.
- Language templates (TranslateGemma) always receive the source alone in 자동; once a model's template is known, a custom prompt is refused for it with the desktop's reason and the field is replaced by the note.
- `desktop-oracle.cjs` now copies `TRANSLATION_RULES` and records localRules, cloudRules and dropCaptions cases from the desktop source.

## Downloads, player and next episode

- Download subtitles prefer Korean (saved Korean/translation, site Korean track, Kairan/Csora/Anissia) over a saved subtitle in another language, so a Korean fansub prevents the separate translation.
- The `#` remux fix and the `file:` URL OP/ED fix do not apply: iOS download folders are hashed IDs and the audio is opened by path.
- Player 재생 tab: 뒤로/앞으로 이동 5/10/15/30/60 s and a 1–120 s field (same setting as 설정 > 재생).
- 설정 > 재생 > 다음 화 미리 준비: 영상 (the next episode's resolved streams are kept and used when it opens if their first bytes still answer), 자막과 싱크 (its Korean subtitle is found and, with site tracks, synced by track and kept as "· 자동 싱크"; a prepared translation is synced the same way), AI 번역 (the former 다음 화 자막 미리 번역).
- Not ported: the desktop FlixCloud proxy's held playlists and first segments (`stream:warm`). iOS creates its HLS proxy per playback and has no shared segment cache to hold them in.

Validation passed on implementation commit `5e68315a325cbc304c37ba2fcf8fb838d22da6bf`:

- Kotlin JVM: 144 tests, zero failures (prompt rules, caption removal and subtitle-sync functions compared with the desktop source; synthetic shift, segment, unchanged and other-episode cases); Kotlin Native simulator tests and framework build passed.
- iOS unit tests: 187 tests, zero failures (including eight new sync, download-subtitle, seek-step and prompt tests); iPhone and iPad UI tests passed.
- Universal iOS device build and arm64 iPhoneOS IPA packaging succeeded.
- Actions: https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/39124293687

The audio path (LilacSpeechLevels on a real stream) is compiled and linked but not exercised by CI; it needs a check on a device. No release tag was created; the CI artifact keeps 0.4.18/build 60.
