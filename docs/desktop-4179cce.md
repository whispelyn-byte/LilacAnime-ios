# Desktop 0.5.40–0.5.44 → iOS

Reference: LilacAnime-desktop [fef5857](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/fef5857) (v0.5.40) through [4179cce](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/4179cce) (v0.5.44). The previous port was [0ac2590](desktop-0ac2590.md); v0.5.39 (14003f5) only bumps the version.

## Community subtitles (v0.5.41–0.5.43)

`DesktopCommunity` / `DesktopCommunityFiles` follow `community-matching.cjs` and `main.cjs/communityLinks`:

- Episode lists split on `+` and `·`, and a range may carry 화 on both ends ("1+5화", "1화~3화").
- File names: release-style tags in brackets or parentheses are dropped, a release group glued after the number ("Show - 01SubsPlease", "Show 01_ToonsHub") and a Korean title with the number glued on ("약사의 혼잣말03") are read, and "Show S2 - 04" names season 2. Files alike but for one number are numbered by it, so an unparsed set is never taken as unnumbered (episode 1 got episode 8's subtitle).
- Link texts only order the downloads (`plan`: named for the episode alone, covering it, naming none; fonts last); the files inside decide (`pick`). A post's tiers are downloaded one at a time by `DesktopSubtitlePreparer.apply`, so another bundle is not fetched once the episode's own link has the file. Last posts (完), cumulative archives (≥ 90 KB or "포함/누적") and per-season links in a series post are handled as on desktop.
- A season the title marks (`fileSeason`) filters files; an unmarked title keeps later-season files but prefers first-season or unmarked ones. Roman numerals before a subtitle ("클레바테스Ⅱ ~…~") and post titles naming several seasons ("1기/2기/3기") count.
- Anissia: the desktop's title queries (subtitle, hyphen-subtitle and roman marks removed, first words), up to three pages each, and Anissia's bare-digit seasons ("이세계 유유자적 농가 2").
- Google Drive folder links are searched breadth first (a folder named like the title, newest years and quarters first, at most 40 listings) by `CommunityAssets`, and only the episode's file (or the folder's unnumbered archives) is downloaded.
- A Tistory attachment named "tfile.smi" is named by its link text when that is a file name.

Not ported: the percent-encoded Referer (iOS community downloads send no Referer; URLSession encodes the URL itself).

## Korean site search (v0.5.40)

애니24 and 링크애니 searches that find nothing are searched again by three pieces of the query, keeping the titles that hold the whole query with spaces ignored (`searchIgnoringSpaces`). The 애니24 airing-list 원제 matching added in v0.5.40 was removed again in v0.5.41, so it is not ported.

## Anime4K (v0.5.40)

A+A, B+B, C+A and 고화질 (HQ, the VL networks) on the single 2x pass, in the desktop order. Three more unmodified upstream 4.0.1 shaders are bundled (`Anime4K_Restore_CNN_VL`, `Anime4K_Restore_CNN_Soft_S`, `Anime4K_Upscale_CNN_x2_VL`), pinned in `upstream.json` and checked by `verify-anime4k.py`. HQ is heavy; device performance is not verified.

## Detail artwork (v0.5.43–0.5.44)

`DesktopMetadataRepository.artwork` gives a TMDB backdrop (else AniList's banner) and up to three Korean logos; TMDB is searched by the English and original titles, then the Korean one (`DesktopArtwork.koreanTmdbQueries`), and season marks such as "第2期" and "シーズン 2" are dropped from queries. `TitleArtworkStore` loads them, measures each logo's lettering lightness, keeps the result a month (a miss a week) and picks the logo that stands out on the theme, outlined when it still blends in. The detail page waits up to 1.5 s for the header picture and logo; the text title stays small under the logo. The home spotlight shows the logo too, and its picks are fetched ahead.

Not ported: card hover prefetch (no pointer hover on iPhone), waiting for the Korean overview (iOS loads it with the title metadata), one-at-a-time title resolution (iOS already applies each title as found) and the weekly schedule arrow (iOS never had it).

## Validation

`desktop-oracle.cjs` now loads every `community-matching.cjs` export and records link plans, link texts, seasons, picks, Korean TMDB queries and new file-name cases from the desktop source. New tests: `CommunityTiersTest`, `KoreanSpacingSearchTest`, Anime4K profile checks.
