# Desktop weekly schedule port

Upstream: `11e4f25465e673f7766cb09a72bd13e5b16a3bab` (after `aadf40a`, desktop 0.5.25).

- Home displays Monday–Sunday tabs, Korean calendar dates and Today, six collapsed rows, expansion, compact posters, episode/track/genre metadata and cancellation/first-airing labels.
- RE:Anime uses its current-week schedule and prefers sub over raw, retaining dub separately.
- Miruro loads the weekly range with cursor validation, filters by Korean day, and joins anime metadata in batches of 200.
- Animenosub preserves its weekday containers; 애니24 uses weekday page props; 링크애니 follows each weekday list's pagination; Linkkf uses the seven existing weekday tags with limit 200.
- Failed source lookups use Anissia with an explicit label and retry. Native empty days remain empty. Successful responses cache for five minutes; concurrent requests share the cached result through a mutex.
- Source/day changes clear previous rows and discard stale callbacks. Calendar rollover is checked when returning to the foreground. Home refresh also refreshes the schedule.
- Fallback entries match known source titles before opening details, or open search when no match exists.

Validation: upstream schedule unit tests 10/10 passed. Added Kotlin fixture tests and Swift calendar boundary tests; GitHub Actions validation pending.

No playback, subtitle, autoskip, or filter behavior changed by this commit.
