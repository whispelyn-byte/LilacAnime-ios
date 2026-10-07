# LilacAnime KMP / iOS 이식

원본 Android 저장소를 보존하면서 shared KMP 모듈과 SwiftUI iOS 앱을 추가했습니다. 공통 로직은 Kotlin으로 옮겼고, Android 전용 UI·WebView·플레이어·저장소는 iOS API로 구현했습니다. 전체 기능 범위를 대상으로 작성했지만 iOS 실행 검증과 아래 제한이 남아 있어 완전한 동작 동일성을 보장하지 않습니다.

## 이식 범위

| 기능 | 구현 |
| --- | --- |
| 탐색 | Linkkf / ReAnime / Animenosub 목록, 검색, 상세, 회차·서버 선택, 관련 작품, ReAnime 순위·편성·필터 |
| 재생 | WKWebView 영상 URL·쿠키·헤더 탐색, mpv/VideoToolbox, ASS/libass, 트랙·속도·화질·탐색, 다음 회차 |
| HLS | 암호화 manifest·조각 처리, localhost 전용 프록시, 화질 선택 |
| 자막 | Kairan·Jimaku 검색, Google Drive 확인 페이지, ZIP 가져오기, ASS/SSA/SRT/VTT/SMI/SBV/SUB/MPL2/TTML, 폰트·싱크·회차별 선택 저장 |
| 번역 | OpenAI / Gemini / DeepL / Qwen, 구조·타이밍 보존, 부분 결과 반영, 캐시, Keychain 키 보관 |
| 로컬 AI | GGUF 가져오기·다운로드, Hugging Face 검색, llama.cpp Metal 추론·취소·문맥 설정 |
| 오프라인 | iOS background URLSession 다운로드·재개, HLS 자산 저장, 로컬 재생, FFmpeg 음성 추출·반복 구간 OP/ED 분석 |
| 편의 기능 | 즐겨찾기·시청 기록·이어보기, AniSkip, 자동 스킵·다음 회차, 백그라운드 오디오, 시스템 AVPlayer PiP/AirPlay 경로 |
| Cast | 공개 HTTPS 또는 Wi-Fi LAN 중계로 로컬·헤더 필요 영상·암호화 HLS 재생, 선택·번역 자막을 VTT로 전송 |
| 한국어 제목 | 한국어 메타데이터, TMDB 제목·별칭 조회, Namu 렌더링 결과 후보, 직접 입력 |

## Mac 실행 및 검증

Apple Silicon Mac, JDK 17, Xcode 및 XcodeGen이 필요합니다. 지원 iOS 최소 버전은 16입니다. 현재 시뮬레이터 타깃은 arm64입니다.

~~~sh
brew install xcodegen
sh ./gradlew :shared:jvmTest
sh iosApp/scripts/verify.sh
open iosApp/LilacAnime.xcodeproj
~~~

검증 스크립트는 KMP iOS 테스트, 프로젝트 생성, Swift 패키지 해석, iPhone 시뮬레이터 테스트, 서명 없는 실기기 빌드를 수행합니다. 결과는 iosApp/build 아래에 기록됩니다. 실제 설치는 Xcode에서 Team과 본인 bundle ID를 설정해야 합니다. iOS 프레임워크는 Xcode 빌드 단계에서 생성됩니다.

GitHub Actions에는 동일한 검증 절차를 추가했습니다. 비공개 저장소는 https://github.com/whispelyn-byte/LilacAnime-ios 입니다.

2026-10-08 검증: https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/37696263105 (커밋 9d781f0, Xcode 26.3). JVM 테스트, KMP iOS 테스트, 시뮬레이터 테스트 3개, 서명 없는 iOS arm64 빌드가 통과했습니다. Actions의 ios-app-builds 아티팩트에는 LilacAnime-simulator.zip과 LilacAnime-device-unsigned.zip이 들어 있습니다. 실기기 설치에는 별도 Apple 서명이 필요합니다.

## 확인 결과와 남은 제한

- Windows에서 shared JVM 컴파일 및 테스트 **16개 통과**. Swift 소스의 문법 파싱과 프로젝트 YAML 검사를 수행했습니다.
- GitHub macOS runner에서 iOS SDK 컴파일·네이티브 링크·시뮬레이터 테스트를 확인했습니다. 실제 외부 영상 재생, GPU 출력, 로컬 AI 추론 및 실기기 재생은 아직 검증하지 않았습니다.
- 실제 외부 서비스 응답과 로그인·캡차·만료 URL은 기기에서 검증해야 합니다. Linkkf 공개 API 연결 확인은 HTTP 522로 실패했습니다.
- PiP/AirPlay용 시스템 AVPlayer 경로는 mpv의 외부 ASS·번역 자막 표시를 그대로 제공하지 않습니다.
- 다운로드의 앱 종료·재실행·백그라운드 재개, FFmpeg HLS/MKV 디코딩, OP/ED 분석 정확도는 실기기 검증이 필요합니다.
- 로컬 추론은 llama.cpp b5046 XCFramework에 고정했습니다. 이 버전 이후 추가된 모델·양자화 형식은 로드할 수 없을 수 있습니다. Android 런타임 패키지를 iOS에 그대로 설치하는 기능은 제공하지 않습니다.
- TMDB·LAN 중계는 사용자의 명시적 승인 후 구현했습니다. TMDB 실제 API 키와 Chromecast 기기가 없어 실제 서비스·기기 연결 검증은 남아 있습니다.
- LAN 중계는 iPhone의 Wi-Fi IPv4 주소에만 바인딩합니다. 세션별 임시 토큰, 등록된 자산 목록, 다운로드 폴더 밖 경로·심볼릭 링크 차단, 동시 연결 8개 제한을 사용합니다. Cast 오류·연결 종료·회차 변경·화면 종료·중계 종료 버튼 또는 연결 없이 10분 경과 시 중계를 닫습니다.
- 중계 중에는 iOS 앱을 재생 화면의 전경에 유지해야 합니다. 앱이 백그라운드에서 중단되거나 Wi-Fi 주소가 바뀌면 Cast를 다시 시작하세요. 공개 HTTPS 직접 전송은 iPhone 중계를 사용하지 않을 수 있습니다.
- Cast 자막은 전송 시점의 선택/번역 자막을 VTT로 변환하고 싱크를 반영합니다. ASS 스타일 효과는 Cast 기본 수신기에서 재현하지 않으며, 전송 후 번역·싱크를 바꾸면 다시 Cast해야 합니다. 수신기가 지원하지 않는 영상 코덱을 변환하는 기능은 없습니다.
- HTTP 웹 플레이어 전체를 허용하는 광범위 ATS 예외도 포함하지 않았습니다.

의존성: Kotlin 2.3.21 / Ktor 3.1.3 / MPVKit 1.0.0 (LGPL) / ZIPFoundation 0.9.19 / Google Cast 4.8.6 / llama.cpp b5046. 배포 시 각 의존성과 원본 저장소의 라이선스 조건을 확인해야 합니다.

## TMDB·Cast 사용

설정의 한국어 제목 검색 · TMDB에서 API Key 또는 API Read Access Token을 저장하고 연결 테스트를 실행합니다. 빈 키를 저장하면 삭제됩니다. 키는 Keychain에 보관하며 고정 HTTPS 목적지 api.themoviedb.org에만 전송합니다. 제목 조회 요청은 리디렉션을 따라가지 않습니다. 요청 오류에는 키를 노출하지 않습니다. 키가 없거나 조회가 실패하면 기존 Namu/수동 제목 검색을 사용합니다.

같은 Wi-Fi의 Chromecast를 연결한 뒤 재생 화면에서 Cast 재생을 누릅니다. 선택한 로컬 자막/번역 자막도 함께 전송합니다. LAN 중계는 HTTP로 선택한 미디어를 전달하며, 임시 URL을 아는 같은 네트워크 사용자가 해당 자산에 접근할 수 있으므로 URL은 공유하지 마세요. 앱 폴더나 파일 목록을 열람하는 API는 제공하지 않습니다. Cast 중계 종료를 누르면 등록 주소를 폐기합니다.

## Android 원본

~~~sh
sh ./gradlew -PincludeAndroid=true :app:assembleDebug
~~~

Android SDK와 원본 네이티브 도구 체인이 필요합니다. 원본에는 app/src/main/cpp/CMakeLists.txt가 없고 Gradle의 ass/ass-kt 의존성이 누락되어 있습니다(module.toml에는 존재). 위 명령은 검증된 APK 생성 절차가 아닙니다. Android는 공통 모델을 shared typealias로 참조하며 기존 플랫폼 구현을 보존합니다. Desktop은 최상위 빌드에 포함하지 않았습니다.
