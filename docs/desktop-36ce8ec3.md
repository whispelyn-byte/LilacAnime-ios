# Desktop 36ce8ec3 → iOS

Source: [36ce8ec3](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/36ce8ec3bfb32de17ca621bfac4698cf0b5854dc), 2026-10-16.

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

All selects reuse the existing scrollable options sheet; parent catalog refreshes do not replace its presented choices. Conditions remain draft values until Apply is pressed. Reset clears extra conditions too.

## Translation

API Save validates the entered key and model list before replacing saved credentials. Qwen region changes are drafts until a successful Save. Failed connections, authentication errors, empty/ineligible model lists, and responses for stale inputs leave saved credentials and region intact. An empty Save still removes a key.

Kotlin coroutine retry delays were already cancellable. Explicit cancellation checks now run before retry requests; regression coverage cancels rate-limit waits for Gemini, OpenAI, Qwen and DeepL and verifies no extra request follows. Existing request formats and fallback behavior are retained.

## Platform mapping and validation

Desktop Electron CORS/WebGL integration is not copied into native iOS. Anime4K uses the previously verified mpv GLSL implementation; see [anime4k-ios.md](anime4k-ios.md) for profile parity, PiP and screenshot limitations.

Validation run: [GitHub Actions 38894090599](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/38894090599). Results will be recorded after completion. Added regression tests cover taxonomy, API request parameters, pagination, rejected combinations, translation cancellation and Swift/Kotlin validation error bridging.

Live source responses and physical-device rendering/performance are not covered by the fixtures. These commits do not create a new public release.
