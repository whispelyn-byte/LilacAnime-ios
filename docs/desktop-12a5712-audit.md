# Desktop 12a5712 port

Baseline: 0640d05 (desktop 0.5.27). Upstream: 12a5712.

- Miruro API requests use an eight-second per-mirror deadline and compatible .to/.tv/.cx/.bz origins. Permanent query errors stop immediately; connection, server and retryable client failures can try the next mirror.
- Player and download server selection verifies actual HLS media. It follows playlist redirects, chooses the highest-resolution variant, samples two segments, compares the minimum measured speed against 300 KB/s and 1.5 times the media's required bitrate, and races at most three probes per preference group. Manual servers are validated first; original audio and English dub remain separate; excluded failures are skipped; if all playable servers are slow, the fastest is used. Broken streams are never selected merely as a default.
- Miruro buffering with no progress for 15 seconds tries another compatible server, retaining exact seconds and subtitle styling. At most two failed-server exclusions are accumulated. Pause, seek, background/system/offline playback and actual progress reset or suppress monitoring. Recovery rechecks generation, seek, pause and progress after asynchronous probes and retains configured quality through the HLS proxy.
- Automatic translation source order is site Japanese, Jimaku Japanese, site English. Dialogue/full tracks precede AI transcripts; signs/songs/forced-only tracks and English Jimaku files are excluded.
- Korean subtitles suppress automatic alongside translation in playback and downloads. Next-episode preparation checks Korean sources before translation. Manual translation remains available.
- The weekly schedule adopts transparent rows, underlined weekday selection and smaller cover art.
- Windows download-path fallback changes have no direct iOS equivalent: downloads stay in the app sandbox. New downloads now check storage availability; startup and existing offline playback do not depend on creating an alternative download directory.

Validation: desktop media/recovery/subtitle/download suites 31/31 passed with local HTTP and temporary-file access enabled (sandboxed execution blocked fixture networking/file operations). Regenerated 124 desktop oracle cases for the new subtitle policy. Added seven Swift selection/probe/deadline/stall/subtitle tests and two Kotlin mirror tests. Native iOS verification pending.
