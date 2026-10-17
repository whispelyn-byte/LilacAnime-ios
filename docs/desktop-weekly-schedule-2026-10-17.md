# Desktop weekly schedule port

Upstream implementation: `11e4f25465e673f7766cb09a72bd13e5b16a3bab` (after `aadf40a`). Rechecked desktop `f04138cdbf7c4262f9ce335932ab2077e1ed3097`, version 0.5.26: only package.json/package-lock.json version numbers changed; no additional runtime changes.

- Home displays Monday–Sunday tabs, Korean calendar dates and Today, six collapsed rows, expansion, compact posters, episode/track/genre metadata and cancellation/first-airing labels.
- RE:Anime uses its current-week schedule and prefers sub over raw, retaining dub separately.
- Miruro loads the weekly range with cursor validation, filters by Korean day, and joins anime metadata in batches of 200.
- Animenosub preserves its weekday containers; 애니24 uses weekday page props; 링크애니 follows each weekday list's pagination; Linkkf uses the seven existing weekday tags with limit 200.
- Failed source lookups use Anissia with an explicit label and retry. Native empty days remain empty. Successful responses cache for five minutes; concurrent requests share the cached result through a mutex.
- Source/day changes clear previous rows and discard stale callbacks. Calendar rollover is checked when returning to the foreground. Home refresh also refreshes the schedule.
- Fallback entries match known source titles before opening details, or open search when no match exists. Matching uses desktop NFKC/lowercase/punctuation rules and preserves season numbers. Display sorting uses the Korean locale for equal broadcast times.

Validation completed on implementation commit `83aeac805514b5b02cec56689f15f172174dde22`:

- Upstream schedule unit tests: 10/10 passed.
- Kotlin JVM suite: 129/129 passed.
- iOS unit tests, including calendar boundaries and season-preserving title matching: 162/162 passed.
- iPhone UI tests: 6/6 passed; iPad UI tests: 4/4 passed.
- Kotlin Native framework, universal iOS device build, and IPA packaging succeeded.
- Actions: https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/38989792549

This applies the desktop changes to main; it does not create a new release tag. The CI build retains the repository's current 0.4.17/build 59 version.

An existing concurrent Ohli mock recorded requests in an unsynchronized mutable list. Its recorder now uses a mutex; no playback code was changed.

No playback, subtitle, autoskip, or filter behavior changed by this commit.
