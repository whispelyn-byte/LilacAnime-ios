# Android 포팅 계획

iOS 앱(`iosApp/`)과 같은 기능 · 같은 화면을 Android에서 쓰기 위한 작업입니다. 릴리즈에는 올리지 않고, GitHub Actions의 **Android APK** 워크플로가 만드는 디버그 APK(워크플로 아티팩트)로만 받습니다.

## 구조

| 경로 | 내용 |
|---|---|
| `shared/` | iOS와 같은 Kotlin Multiplatform 코드. Android도 `IosServices` · `AnimeSnapshot` 등을 그대로 씁니다 (`androidMain`에 플랫폼 구현). |
| `androidApp/` | 새 Android 앱 (Jetpack Compose). iOS SwiftUI 화면을 하나씩 옮깁니다. 패키지 `com.lilac.anime.android`라 원본 Android 앱(`com.lilac.anime`)과 함께 설치됩니다. |
| `app/` | 원본 Android 앱의 옛 코드. 건드리지 않습니다 (`iosApp/scripts/android-version.py`가 `app/module.toml`의 versionCode를 읽음). `-PincludeLegacyAndroid=true`일 때만 Gradle에 들어갑니다. |

빌드: `sh ./gradlew -PincludeAndroid=true :androidApp:assembleDebug` (JDK 17, Android SDK 필요).

## 단계

iOS 파일 이름을 기준으로 옮길 범위를 나눴습니다. 체크된 항목이 `androidApp/`에 들어가 있습니다.

### 1단계 — 기반 · 둘러보기 화면
- [x] 모듈 · 워크플로 (`.github/workflows/android.yml`)
- [x] 테마 (`UIComponents.swift` LilacStyle: 다크/라이트 팔레트, 버튼 · 칩 · 섹션 제목 · 포스터 카드 · 빈 화면)
- [x] 상단 헤더 · 이동 (`DesktopShell.swift` DesktopWorkspace, `PlaybackRouter.swift` DesktopNavigation)
- [x] 홈 (`HomeView.swift`, `HomeSpotlight.swift`, 계속 시청하기 · 내 목록 줄 · 소스별 줄)
- [x] 전체 (`DesktopWorkspace.swift` DesktopFullCatalog: 정렬 칩 · 상세 필터)
- [x] 검색 (`CatalogView.swift` CatalogView · CatalogModel)
- [x] 작품 상세 (`CatalogView.swift` DetailView: 회차 · 작품 정보 · 관련 작품 탭, 서버 · 정렬 · 페이지)
- [x] 시청기록 · 내 목록 (`DesktopShell.swift`)
- [x] 보관함 저장 (`LibraryStore.swift`: 내 목록 · 시청 기록 · 설정을 `library.json`으로)

### 2단계 — 재생
- [x] mpv 플레이어 (`MPVEngine.swift` → `dev.jdtech.mpv:libmpv`), 플레이어 화면 (`EpisodePlayerView.swift`의 재생 부분, `PlayerSettingsPanel.swift` 재생 탭, `PlayerInteraction.swift`, `PlayerSeekHint.swift`, `EpisodePicker.swift` 회차 패널)
- [x] 재생 주소 찾기 (`PlaybackResolver.swift` → Android WebView + 문서 시작 스크립트, `MiruroPlayback.swift` · `MiruroBrowser.swift`, `OhliPlayback.swift`, `HLSProxy.swift` → OkHttp + 로컬 소켓, `StaggeredResolver.swift`, `DesktopStreamPolicy.swift`, `DesktopFlixReadiness.swift`)
- [x] 이어보기 · 다음 화 자동 재생 · OP/ED 건너뛰기 (AniSkip 구간; 기기 안 오디오 분석 `OfflineAnalyzer.swift`는 5단계)
- [x] 백그라운드 재생(미디어 포그라운드 서비스) · PiP(Android PiP) · 스크린샷(사진 > LilacAnime) · 화면 켜두기 · 외부 키보드
- [ ] 다음 화 미리 준비, 탐색 바 미리보기, 2배속 Space 길게 누르기 — 3단계 이후

### 3단계 — 자막 · 번역
- [ ] 자막 찾기 · 고르기 · 저장 (`EpisodeSubtitleStore.swift`, `SubtitleFiles.swift`, `SubtitleArchive.swift`, `SubtitleNames.swift`, `DesktopSubtitlePreparer.swift`, `DesktopSubtitlePolicy.swift`, `DesktopJimakuCatalog.swift`, 다른 사이트 자막 트랙)
- [ ] 자막 모양 · 싱크 (`SubtitleAppearance.swift`, `SubtitleLineSpacing.swift`, `SubtitleAutoSync.swift`)
- [ ] 클라우드 번역 (`TranslationCoordinator.swift`, `CloudSubtitleScheduler.swift`, `TranslationPromptSettings.swift`)
- [ ] 설정 화면 전체 (`SettingsView.swift`, `SettingsComponents.swift`), 키 저장 (Keychain → Android Keystore)

### 4단계 — 다운로드 · 카탈로그
- [ ] 다운로드 (`DownloadStore.swift`, `DownloadTransfer.swift`, `BackgroundDownloads.swift`, `DesktopDownloads.swift`, `DownloadStreamSelector.swift`) — 백그라운드는 포그라운드 서비스로
- [ ] 한국어 제목 카탈로그 · 인덱스 (`DesktopCatalog.swift`, `TitleLookup.swift`, `DesktopRecentUpdates.swift`), 타이틀 로고 (`TitleArtwork.swift`)
- [ ] 주간 편성표 (`WeeklyScheduleView.swift`)
- [ ] 로컬 영상 열기

### 5단계 — 기기 안 AI · 영상 처리
- [ ] 로컬 AI 번역 (`LocalModelsView.swift`, `LocalModelInput.swift`, `HuggingFaceSearchView.swift`, `DesktopModelInstaller.swift`; llama.cpp를 Android NDK로)
- [ ] Whisper 음성 자막 (`WhisperSubtitles.swift`, `WhisperLiveTranslation.swift`; iOS는 WhisperKit, Android는 whisper.cpp)
- [ ] Anime4K (`Anime4K.swift`; mpv GLSL 셰이더 그대로), 영상 정리 (`VideoCleanup.swift`, `WinPNGReader.swift`)

### 6단계 — 연결
- [ ] 기기 동기화 (`DeviceSync.swift`)
- [ ] Chromecast (`CastService.swift`, `LANMediaRelay.swift`)
- [ ] 업데이트 확인 · 변경 내역 (`DesktopUpdater.swift`) — Android는 릴리즈가 없으므로 생략 가능

## iOS와 달라지는 점
- SF Symbols 대신 Material 아이콘을 씁니다 (같은 뜻의 아이콘).
- AirPlay는 Android에 없습니다 (Chromecast만).
- SideStore 관련 안내 · 업데이트는 Android에 해당하지 않습니다.
