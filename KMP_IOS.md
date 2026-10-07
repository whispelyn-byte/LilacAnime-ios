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
| Cast | 공개 HTTPS 영상과 원격 VTT를 기본 수신기로 전송 |
| 한국어 제목 | 한국어 메타데이터, Namu 렌더링 결과 후보, 직접 입력 |

## Mac 실행 및 검증

Apple Silicon Mac, JDK 17, Xcode 및 XcodeGen이 필요합니다. 지원 iOS 최소 버전은 16입니다. 현재 시뮬레이터 타깃은 arm64입니다.

~~~sh
brew install xcodegen
sh ./gradlew :shared:jvmTest
sh iosApp/scripts/verify.sh
open iosApp/LilacAnime.xcodeproj
~~~

검증 스크립트는 KMP iOS 테스트, 프로젝트 생성, Swift 패키지 해석, iPhone 시뮬레이터 테스트, 서명 없는 실기기 빌드를 수행합니다. 결과는 iosApp/build 아래에 기록됩니다. 실제 설치는 Xcode에서 Team과 본인 bundle ID를 설정해야 합니다. iOS 프레임워크는 Xcode 빌드 단계에서 생성됩니다.

GitHub Actions에는 동일한 검증 절차를 추가했습니다. 이 작업에서는 저장소에 push하거나 원격 CI를 실행하지 않았습니다.

## 확인 결과와 남은 제한

- Windows에서 shared JVM 컴파일 및 테스트 **13개 통과**. Swift 소스의 문법 파싱과 프로젝트 YAML 검사를 수행했습니다.
- iOS SDK 컴파일·시뮬레이터 테스트·실기기 재생은 **미실행**입니다. Swift 문법 검사만으로 SDK API 타입 호환성, 네이티브 링크, GPU 재생을 확인할 수 없습니다.
- 실제 외부 서비스 응답과 로그인·캡차·만료 URL은 기기에서 검증해야 합니다. Linkkf 공개 API 연결 확인은 HTTP 522로 실패했습니다.
- PiP/AirPlay용 시스템 AVPlayer 경로는 mpv의 외부 ASS·번역 자막 표시를 그대로 제공하지 않습니다.
- 다운로드의 앱 종료·재실행·백그라운드 재개, FFmpeg HLS/MKV 디코딩, OP/ED 분석 정확도는 실기기 검증이 필요합니다.
- 로컬 추론은 llama.cpp b5046 XCFramework에 고정했습니다. 이 버전 이후 추가된 모델·양자화 형식은 로드할 수 없을 수 있습니다. Android 런타임 패키지를 iOS에 그대로 설치하는 기능은 제공하지 않습니다.
- Cast는 로컬 파일, 암호화 HLS, 사용자 헤더가 필요한 영상을 중계하지 않습니다.
- TMDB 키를 외부 API로 보내는 통합과 LAN 다운로드/자막 중계 서버는 자동 승인 검토에서 각각 키 전송 목적지 승인 부족과 로컬 콘텐츠 네트워크 노출 사유로 거절되어 포함하지 않았습니다.
- HTTP 웹 플레이어 전체를 허용하는 광범위 ATS 예외도 포함하지 않았습니다.

의존성: Kotlin 2.3.21 / Ktor 3.1.3 / MPVKit 1.0.0 (LGPL) / ZIPFoundation 0.9.19 / Google Cast 4.8.6 / llama.cpp b5046. 배포 시 각 의존성과 원본 저장소의 라이선스 조건을 확인해야 합니다.

## Android 원본

~~~sh
sh ./gradlew -PincludeAndroid=true :app:assembleDebug
~~~

Android SDK와 원본 네이티브 도구 체인이 필요합니다. 원본에는 app/src/main/cpp/CMakeLists.txt가 없고 Gradle의 ass/ass-kt 의존성이 누락되어 있습니다(module.toml에는 존재). 위 명령은 검증된 APK 생성 절차가 아닙니다. Android는 공통 모델을 shared typealias로 참조하며 기존 플랫폼 구현을 보존합니다. Desktop은 최상위 빌드에 포함하지 않았습니다.
