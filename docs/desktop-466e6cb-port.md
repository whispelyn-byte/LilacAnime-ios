# 데스크탑 466e6cb 반영

비교 기준: 데스크탑 `bbbf8fd`(0.5.59) → `466e6cb`(휴대폰에서 본 Re:Anime 회차가 이 기기에서 재생됨), 릴리스 `d16a9a5`(0.5.60), iOS `77820c4`.

## 반영한 것

- **AniList id 없는 Re:Anime 회차**: Android 앱에서 동기화된 회차에는 AniList id가 없고, 지금 Re:Anime의 `/api/watch`는 JSON 대신 사이트 페이지를 돌려줘서, 이 기기에서 한 번도 연 적 없는 작품은 서버가 하나도 나오지 않았습니다. 이제 회차 주소의 slug로 `/api/v1/anime/{slug}`에서 `anilist_id`를 읽어 `/api/flix/{id}/{회차}`로 서버를 받습니다 (`DesktopSourceRepository.serverPages`).
- **주소가 있는 Android 기록도 작품 목록의 회차로**: Re:Anime · Miruro처럼 주소를 아는 Android 기록도 이어서 보기에서 열면 먼저 작품의 회차 목록에서 같은 회차를 찾아 재생하고, 그 회차의 기록이 Android 기록을 대신합니다(`WatchEntry.listedFirst`). 목록을 못 읽거나 회차가 없으면 기록의 주소로 재생합니다.
- 같은 커밋의 WireGuard .txt · UTF-16 읽기는 [254753c 문서](desktop-254753c-port.md)대로 iOS에 해당이 없습니다.

## 그 밖에

- iOS 공통 테스트의 경합 수정: `EpisodeThumbnailsTest`(Kitsu 페이지 5개)와 `ReanimeAudioTest`(`/api/watch` · `/api/flix`)는 MockEngine이 여러 스레드에서 응답하는 동안 잠금 없이 같은 목록에 기록했습니다. 77820c4의 iOS 시뮬레이터 테스트가 `EpisodeThumbnailsTest`에서 signal 11로 끝난 원인으로 보고 `Mutex`로 감쌌습니다.

## 검증

- 공통 테스트 `ReanimeAudioTest`에 데스크탑 tests/reanime-audio.test.cjs의 새 테스트(slug로 AniList id를 읽어 Flix에 묻기)를 옮겼습니다.
- XCTest `EpisodePickerTests`에 주소 있는 Android 기록의 `listedFirst`와 기록 교체를 추가했습니다.
- 빌드 · 테스트는 KMP and iOS 워크플로에서 확인합니다(이 작업 환경은 Windows라 Kotlin/Native · Swift를 빌드하지 못했습니다). 실제 Re:Anime 응답과 기기 재생은 확인하지 못했습니다.
- `docs/desktop-audit.json`을 d16a9a5(0.5.60) 기준으로 갱신했습니다.
