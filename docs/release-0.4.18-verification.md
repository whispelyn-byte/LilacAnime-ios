# iOS 0.4.18 release verification

- Release: https://github.com/whispelyn-byte/LilacAnime-ios/releases/tag/v0.4.18
- Tag commit: f7430b1c85a33cbb5755d3348d69a29eff676682; version 0.4.18, revision 30, build 60.
- Actions: https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/39006659395 (all jobs successful).
- Includes desktop weekly schedule, custom GGUF input settings and playback/subtitle changes through 12a5712ffc6e776fdaa320ce0299755c153e1b51.
- Kotlin JVM: 131 tests, zero failures; Kotlin Native verification passed. Swift unit tests: 178 passed; iPhone UI: 6 passed; iPad UI: 4 passed. Universal device build and IPA packaging passed.
- Published IPA: 26,097,961 bytes; SHA-256 87ef6db39972b3ffe6c31655f8ee01dd3e87fb5ff4d95d6da58cfb6737200bec (matches GitHub asset digest).
- Downloaded IPA Info.plist confirms version 0.4.18/build 60. SideStore source version, build, download URL and byte counts match. Seven Anime4K shaders, MIT license hashes and Flix discovery resource match the source.
- Local version/source metadata test suites: 6 + 6 passed with the temporary directory inside the workspace. Release-note selection and Anime4K verification passed.
