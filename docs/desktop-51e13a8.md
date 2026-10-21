# Desktop 0.5.46 → iOS

Reference: LilacAnime-desktop [51e13a8](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/51e13a8) (v0.5.46, GitHub issue #16). The previous port was [9e5897c](desktop-9e5897c.md).

## Leaving the player (v0.5.46)

Desktop now closes the anime page for the player and reopens it on the way out, with its episode page, tab and scroll position, refreshing the 이어보기 button (`closeDetailForPlayer` / `reopenDetailAfterPlayer`), and puts the page's scroll position back (`scrollBeforePlayer`).

iOS already behaves this way: the player is a full-screen cover over the whole app (`LilacAnimeApp` `fullScreenCover`), so the detail page underneath keeps its tab, episode page and scroll, and its 재생/이어보기 button is derived from the watch history, which the player updates. No change.

## In-app confirm dialog (v0.5.46)

Desktop replaced the browser's `confirm()` with its own dialog (`askConfirm`), because on Windows the native box left the search box unable to take typing. iOS asks with SwiftUI alerts and confirmation dialogs, which have no such focus problem. Not ported.

## iOS fixes in the same release

- 2배속 hold: the touch that ends a hold no longer counts as a tap (`PlayerControls.tapAllowed`). Its release was taken as the first tap, so the next tap became a double tap that seeked ±10 s, or paused in the middle zone.
- `&nbsp;` in subtitles: mpv's SRT and SAMI readers draw it as text. Subtitles are written with it as a non-breaking space (`SubtitleFiles.playable`, also for WinPNG output), subtitles saved earlier are fixed when put on (`SubtitleFiles.repairEntities`), and `SubtitleDocument.modelText` no longer sends it to translation. Test: `TesterRegressionTest.smiClearingNbspIsNotSubtitleText`.
