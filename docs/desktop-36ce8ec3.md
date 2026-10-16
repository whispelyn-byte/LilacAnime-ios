# Desktop 36ce8ec3 → iOS

Source: [36ce8ec3](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/36ce8ec3bfb32de17ca621bfac4698cf0b5854dc), 2026-10-16.

Final remote check also covered [6664dd8 / v0.5.24](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/6664dd868bd5d5852b5a384b364e76c4985590fc). That subsequent commit only changes desktop package version numbers from 0.5.23 to 0.5.24; no additional feature code needs porting.

The iOS `codex/ios-anime4k` branch includes the existing native Anime4K profiles and playback-time auto-skip countdown, plus the source-specific catalog filters from this desktop commit.

## Catalog

| Source | Added conditions |
| --- | --- |
| RE:Anime | Tags, status, country, dub availability, age rating, studio, character, staff; all server formats |
| Animenosub | Studio, status, sub/dub/RAW, explicit ordering |
| Miruro | Genre, format, status, country, original source, adult flag, comma-separated tags, minimum/maximum score |
| Linkani | Server genre/format/year taxonomy, weekday, combined taxonomy routes |
| Ohli24 | Genre, year/decade, status, weekday, ordering through root search |
| Linkkf | Report invalid/unavailable taxonomy responses instead of caching an empty success |

Extra-only selections use the remote catalog. Miruro detailed catalog updates sort metadata (`-updated_at`); the home episode-update feed retains its existing behavior. Upcoming titles returned by Miruro are retained in detailed searches. Pagination uses Miruro cursors, Ohli24 `next_page_url`, and Linkani's next-page control.

Source-specific validation rejects incompatible conditions: RE:Anime extras with episode-update sorting, Miruro upcoming titles with score bounds, Linkani genre plus weekday, and Ohli24 movies plus airing conditions or finished shows plus weekday. RE:Anime switches to popularity order when adding extra conditions from episode-update order. Explicit source ordering hides the general sort chips.

All selects reuse the existing scrollable options sheet; parent catalog refreshes do not replace its presented choices. RE:Anime preserves the original server format values, including case variants, as requested, rather than folding or removing choices. Conditions remain draft values until Apply is pressed. Reset clears extra conditions too.

## Translation

API Save validates the entered key and model list before replacing saved credentials. Qwen region changes are drafts until a successful Save. Failed connections, authentication errors, empty/ineligible model lists, and responses for stale inputs leave saved credentials and region intact. An empty Save still removes a key.

Kotlin coroutine retry delays were already cancellable. Explicit cancellation checks now run before retry requests; regression coverage cancels rate-limit waits for Gemini, OpenAI, Qwen and DeepL and verifies no extra request follows. Existing request formats and fallback behavior are retained.

## Platform mapping and validation

Desktop Electron CORS/WebGL integration is not copied into native iOS. Anime4K uses the previously verified mpv GLSL implementation; see [anime4k-ios.md](anime4k-ios.md) for profile parity, PiP and screenshot limitations.

Validation run: [GitHub Actions 38895790116](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/38895790116), code commit `036c9a3ff486a6359625d3e45d1fce481734d67d`: **passed**. Shared JVM tests: 121, zero failures; Kotlin/Native simulator tests passed; Swift unit tests: 142, zero failures; iPhone UI tests: 6, zero failures; iPad UI tests: 4, zero failures. The universal device Release build and IPA packaging passed. Added regression tests cover taxonomy, API request parameters, pagination, rejected combinations, translation cancellation and Swift/Kotlin validation error bridging.

Downloaded development IPA: 25,933,944 bytes, version 0.4.13/build 55 (development artifact, not a new public release). SHA-256: `1513abeceeb6962430a9de7cb1c4980e6cc8dcb973f1d1159046a428b884af8c`. Verified exactly seven GLSL files and one Anime4K MIT license in the IPA against all eight pinned upstream hashes.

Read-only live taxonomy checks on 2026-10-16 confirmed RE:Anime's formats/tags (421 tags, including case variants in formats) and Animenosub's studio/status/sub/order/season input groups. Linkani and Ohli24 connections were reset by the remote host during this check; their request behavior is covered by fixtures. Physical-device rendering/performance is not covered. These commits do not create a new public release.
