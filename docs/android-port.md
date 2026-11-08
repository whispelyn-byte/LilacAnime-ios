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
- [x] 다음 화 미리 준비 (영상 주소 · 자막), 탐색 바 미리보기, 2배속 Space 길게 누르기

### 3단계 — 자막 · 번역
- [x] 자막 찾기 · 고르기 · 저장 (`EpisodeSubtitleStore.swift`, `SubtitleFiles.swift`, `SubtitleArchive.swift` → ZIP(CP949) · commons-compress 7z · junrar RAR4, `SubtitleNames.swift`, `DesktopSubtitlePreparer.swift`, `DesktopSubtitlePolicy.swift`, `DesktopJimakuCatalog.swift`)
- [x] 플레이어 자막 탭 (소스 카드, 사이트 트랙, Anissia 제작자, Jimaku 파일, 한국어 자막 다시 찾기, 파일에서 열기, 한국어 자막 제안)
- [x] 자막 모양 · 타이밍 (`SubtitleAppearance.swift`, `SubtitleLineSpacing.swift`, 글꼴 가져오기, 시스템 한국어 글꼴 연결)
- [x] 클라우드 번역 (`TranslationCoordinator.swift`의 API 부분, `CloudSubtitleScheduler.swift`, `TranslationPromptSettings.swift`)
- [x] 설정 화면 (일반 · 재생 · 자막 · 한국어 제목 검색 · 자막 자동 번역 · 저장 자막·캐시), 키 저장 (Keychain → Android Keystore)
- [x] 다른 사이트 자막 트랙(`CrossSiteTracks.swift`), 음성 싱크 맞추기(`SubtitleAutoSync.swift`), Anissia WinPNG 게시물(`WinPNGReader.swift` → Android WebView)
- [x] 한국어 제목 카탈로그로 블로그 검색 제목 찾기

### 4단계 — 다운로드 · 카탈로그
- [x] 다운로드 (`DownloadStore.swift`, `HLSPlanBuilder` in `BackgroundDownloads.swift`, `DownloadsView`) — iOS의 백그라운드 URLSession 대신 데이터 동기화 포그라운드 서비스, 위장 조각은 받으면서 풀어 저장, 받은 회차는 어디서 열어도 파일로 재생, 자막도 함께 저장
- [x] 한국어 제목 카탈로그 · 인덱스 (`DesktopCatalog.swift`: 전체 목록 수집, Wikidata 일괄 → TMDB, 검색 · 다른 언어 제목, 작품 제목 표시 언어), 전체 목록을 인덱스로 보여 주기, 블로그 자막 검색 제목
- [x] 최근 업데이트 (`DesktopRecentUpdates.swift`: 홈 최근 업데이트 줄, 내 목록 회차 업데이트순)
- [x] 주간 편성표 (`WeeklyScheduleView.swift`)
- [x] 로컬 영상 열기
- [x] 타이틀 로고 · 배너 (`TitleArtwork.swift`)
- [x] 다운로드 폴더 내보내기·가져오기 (`DownloadTransfer.swift`; 이 앱 형식만, 데스크탑 downloads.json은 아직)
- [x] 재생 막대 미리보기 (숨긴 mpv `vo=image`)
- [x] 다운로드 자막 번역 (새 다운로드에 자동으로 저장하고 기존 다운로드에서도 실행 가능)

### 5단계 — 기기 안 AI · 영상 처리
- [x] 로컬 AI 번역 (`LocalModelsView.swift`, `LocalModelInput.swift`, `DesktopModelInstaller.swift`; iOS와 같은 llama.cpp b11490 + `iosApp/Native/Sources/LilacLocalAI`를 NDK로 빌드, JNI `androidApp/src/main/cpp`). CPU 전용 (GPU 백엔드 없음)
- [x] Anime4K (`Anime4K.swift`; mpv GLSL 셰이더 그대로)
- [x] 다른 사이트 자막 트랙 (`CrossSiteTracks.swift`의 RE:Anime · Miruro 트랙)
- [x] 음성 읽기: iOS NativeAudio(FFmpeg) 대신 숨긴 mpv가 `ao=pcm`으로 16 kHz/8 kHz 모노 PCM을 씀 (`audio/AudioExtractor.kt`)
- [x] Whisper 음성 자막 (`WhisperSubtitles.swift`, `WhisperLiveTranslation.swift`; iOS는 WhisperKit, Android는 whisper.cpp v1.7.4를 별도 모듈 `androidWhisper`로 — ggml이 llama.cpp와 겹치지 않게)
- [x] 음성 싱크 맞추기 (`SubtitleAutoSync.swift`; 정렬은 공통 `SubtitleSync`), 다운로드 OP/ED 분석 (`OfflineAnalyzer.swift`; 공통 `AudioFingerprint`)
- [x] 영상 정리 (`VideoCleanup.swift`: 영역 · 로고 학습(데스크탑 JS를 숨긴 WebView에서) · 마스크 가져오기), 애니24·링크애니·Linkkf 한국어 자막 파일 (`koreanSiteTracks`)
- [x] Hugging Face 모델 검색 (`HuggingFaceSearchView.swift` → 로컬 AI 모델 설정에서 저장소와 GGUF 파일 선택)

### 6단계 — 연결
- [x] 기기 동기화 (`DeviceSync.swift`)
- [x] Chromecast (`CastService.swift`, `LANMediaRelay.swift` → HLS 프록시를 Wi-Fi 주소로)
- [x] 설정의 변경 내역과 새 APK 확인 링크. 디버그 APK는 Android APK 워크플로의 아티팩트에서 확인합니다.

## iOS와 달라지는 점
- SF Symbols 대신 Material 아이콘을 씁니다 (같은 뜻의 아이콘).
- AirPlay는 Android에 없습니다 (Chromecast만).
- SideStore 관련 안내 · 업데이트는 Android에 해당하지 않습니다.
