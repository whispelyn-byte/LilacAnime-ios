# Desktop 0ac2590 parity

Reference: LilacAnime-desktop 0ac2590e1bbc3a5380cfc4a8b5ab5e511a0d4ad4.

RE:Anime now filters requested audio before HD ranking and URL deduplication. Data type dub/dubbed and server-name DUB/Dubbed are recognized case-insensitively; untyped legacy servers remain original audio. A watch response containing only dub servers triggers the Flix fallback, and dub-only responses stay unavailable for original audio. The iOS player currently requests original audio; parser tests also cover explicit dub selection.

The other desktop change uses Electron macOS window-button visibility; it has no iOS equivalent.

Regression coverage: ReanimeAudioTest covers DUB-first/shared URLs, HD order, legacy links, named/type dub recognition, explicit dub, wrong-audio watch fallback and dub-only unavailability. Release 0.4.21 remains on hold.
