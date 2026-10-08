<p align="center"><img src="docs/icon.png" width="96" alt="LilacAnime"></p>

<h1 align="center">LilacAnime iOS</h1>

<p align="center">
  <a href="https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest"><img src="https://img.shields.io/github/v/release/whispelyn-byte/LilacAnime-ios?label=Latest&color=b98fd6" alt="최신 버전"></a>
  <img src="https://img.shields.io/badge/iOS-16%2B-555555?logo=apple" alt="iOS 16 이상">
  <img src="https://img.shields.io/badge/SwiftUI-Kotlin%20Multiplatform-b98fd6" alt="SwiftUI / KMP">
</p>

<p align="center">
  <a href="https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/LilacAnime-SideStore.ipa"><img src="https://img.shields.io/badge/Download-SideStore%20IPA-c8a2c8" alt="SideStore IPA 다운로드"></a>
  <a href="https://github.com/whispelyn-byte/LilacAnime-desktop">Windows · macOS 버전</a>
</p>

<p align="center">
  <a href="https://github.com/dream150/LilacAnime">LilacAnime</a> Android 앱을 Kotlin Multiplatform과 SwiftUI로 옮긴 iOS 포트입니다.<br>
  작품 탐색부터 회차 재생, 자막 검색·번역, 이어보기와 오프라인 감상까지 제공합니다.
</p>

현재 Android 원본과 같은 **0.4.0**을 기준으로 iOS 빌드 **31**을 사용합니다.

## 주요 기능

- **작품 탐색:** Linkkf · Ohli24 · Linkani · Animenosub · ReAnime · Miruro 검색, ReAnime 인기 목록·방영표.
- **홈·작품 상세:** 추천 카드, 최신 작품 가로 목록, 이어보기, 즐겨찾기, 회차·작품 정보·관련 작품 탭.
- **플레이어:** mpv 기반 재생, 이전/다음 회차, 탐색 버튼·더블탭 이동, 조작 버튼 자동 숨김, 화면 잠금, 전체 화면, OP/ED 건너뛰기.
- **자막:** Kairan · Csora · Anissia · Jimaku 검색, 제공 한국어 자막 우선 자동 선택, 파일·ZIP 가져오기, 자막 싱크·트랙·폰트 설정.
- **자막 번역:** 클라우드 번역 및 GGUF 로컬 AI, 재생 위치 우선 번역, 중단 후 재개, 클라우드 실패 시 로컬 전환, 사용자 용어집. 설정의 제공자와 API 키를 사용합니다.
- **저장·연결:** 회차·서버 전체 다운로드, 제공 자막 오프라인 저장, 시청 기록과 이어보기, 시스템 플레이어 PiP/AirPlay, Chromecast 전송.

> 데스크톱 버전과 지원 소스·기능·조작 방식이 다릅니다. 외부 영상 서비스와 실기기 재생·Cast·로컬 AI는 기기와 서비스 상태에 따라 확인이 필요합니다.

## 화면

<p align="center">
  <img src="docs/screenshots/home-dark.png" width="230" alt="홈">
  <img src="docs/screenshots/detail-dark.png" width="230" alt="작품 상세">
  <img src="docs/screenshots/player-dark.png" width="230" alt="플레이어">
</p>

캡처는 시뮬레이터에서 예시 데이터를 사용했습니다. 실제 작품 이미지나 영상 재생 캡처가 아닙니다.

## 설치

iOS 16 이상 기기와 [SideStore](https://docs.sidestore.io/)가 필요합니다.

1. **[최신 IPA 다운로드](https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/LilacAnime-SideStore.ipa)**를 누릅니다.
2. SideStore의 **My Apps → +**에서 다운로드한 IPA를 선택합니다.
3. SideStore가 Apple 계정으로 서명하고 설치합니다.

릴리스의 IPA는 **압축을 풀지 않습니다.** Actions 아티팩트 ZIP으로 받았다면 바깥 ZIP만 한 번 풀면 됩니다. 앱을 업데이트할 때 기존 앱을 삭제하지 않고 새 IPA를 가져옵니다.

## SideStore에서 업데이트

SideStore의 **Sources → +**에 아래 주소를 추가합니다.

~~~text
https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/source.json
~~~

소스에서 **LilacAnime iOS**를 설치하면 이후 새 버전을 SideStore에서 확인하고 업데이트할 수 있습니다. 이미 IPA로 설치했다면 소스를 추가하고 앱을 확인하세요. 소스와 기존 앱 연결이 되지 않으면 기존 앱을 삭제하지 말고 소스에서 설치를 진행합니다.

**인증 갱신(Refresh)**과 **새 버전 업데이트**는 별개입니다. 소스 등록은 완전한 무인 자동 설치를 보장하지 않으며, SideStore에서 업데이트 버튼을 눌러 설치합니다. 기기에서의 소스 등록·업데이트 동작은 아직 확인하지 않았습니다. [SideStore 공식 FAQ](https://docs.sidestore.io/docs/faq)

## 처음 실행할 때

1. 홈 상단 또는 탐색 탭에서 영상 소스를 선택합니다.
2. 작품을 선택하고 첫 회차 또는 이어보기를 누릅니다.
3. 재생 화면의 자막 메뉴에서 검색하거나 파일을 가져옵니다.
4. **설정 → 한국어 제목 검색 · TMDB**에 API Key 또는 Read Access Token을 넣으면 한국어 자막 검색 제목을 찾는 데 사용합니다.
5. 자막 번역을 사용하려면 설정에서 번역 제공자·키 또는 지원 GGUF 모델을 준비합니다.

## 플레이어 사용

| 조작 | 동작 |
|---|---|
| 영상 한 번 탭 | 조작 버튼 표시·숨김 |
| 영상 왼쪽/오른쪽 더블탭 | 설정한 시간만큼 뒤로/앞으로 이동 |
| 중앙 더블탭 또는 재생 버튼 | 재생·일시정지 |
| 이전/다음 회차 버튼 | 이 재생 세션에서 시청한 이전 회차·다음 회차 이동 |
| 잠금 버튼 | 터치 조작 잠금, 잠금 해제 버튼으로 복귀 |
| 톱니바퀴 | 자막·재생 트랙·속도 선택 |
| 전체 화면 버튼 | 가로 전체 화면 전환 |
| 더보기 메뉴 | 다운로드·Cast·시스템 재생/PiP·웹 플레이어 |

시스템 AVPlayer 및 웹 플레이어는 자체 조작 UI를 사용합니다. PiP/AirPlay 경로는 mpv의 외부 ASS·번역 자막을 그대로 표시하지 않습니다. Cast 자막은 VTT로 변환되어 ASS 스타일 효과가 유지되지 않습니다. iPhone LAN 중계가 필요한 Cast는 앱을 재생 화면의 전경에 유지합니다.

## 문제 해결

- **작품이나 영상이 열리지 않음:** 외부 서비스 장애·로그인·캡차·주소 만료일 수 있습니다. 다시 시도하거나 다른 소스를 선택하세요.
- **자막을 찾지 못함:** 검색 제목을 수정하거나 TMDB 키를 설정하고, 자막 파일을 직접 가져옵니다.
- **번역이 실패함:** 제공자·API 키·사용량 및 GGUF 모델 호환성을 확인하세요.
- **SideStore 설치 실패:** [공식 문제 해결 안내](https://docs.sidestore.io/docs/troubleshooting)를 확인하세요. IPA는 SideStore에서 서명해야 합니다.

## 개발·릴리스

공통 코드 테스트는 JDK 17로 실행합니다.

~~~sh
sh ./gradlew :shared:jvmTest
~~~

iOS 빌드는 Apple Silicon Mac, Xcode, JDK 17, XcodeGen이 필요합니다.

~~~sh
brew install xcodegen
sh iosApp/scripts/verify.sh
~~~

GitHub Actions는 공통 테스트, KMP iOS 테스트, 네이티브 iOS 테스트, 시뮬레이터 캡처, unsigned arm64 IPA 패키징을 실행합니다. **App Store 배포용 서명 빌드가 아닙니다.**

앱 버전은 app/module.toml의 Android versionName을 사용합니다. iOS 빌드 번호는 Android versionCode에 iosApp/revision.txt의 값을 더합니다. 배포 태그는 `python3 iosApp/scripts/android-version.py --tag`로 확인합니다(현재 **v0.4.0-ios.1**). 해당 태그를 푸시하면 모든 테스트·빌드가 성공한 뒤 IPA와 실제 메타데이터로 생성한 SideStore source.json을 릴리스에 게시합니다. 최신 소스 주소는 그대로 유지됩니다.

| 경로 | 내용 |
|---|---|
| shared/ | Kotlin Multiplatform 모델·소스·자막·공통 로직 |
| iosApp/LilacAnime/ | SwiftUI UI·mpv·다운로드·Cast·번역 연결 |
| iosApp/scripts/ | 검증·IPA 패키징·소스 생성 |
| .github/workflows/kmp.yml | 테스트·빌드·태그 릴리스 |
| [docs/desktop-port.md](docs/desktop-port.md) | 데스크탑 이식 범위·차이 |
| [KMP_IOS.md](KMP_IOS.md) | 이식 상세·검증 결과·남은 제한 |

## 크레딧

- 원본 Android: [dream150/LilacAnime](https://github.com/dream150/LilacAnime)
- 데스크톱 포트 및 README 구성 참고: [whispelyn-byte/LilacAnime-desktop](https://github.com/whispelyn-byte/LilacAnime-desktop)
- 재생: [mpv](https://github.com/mpv-player/mpv) · [MPVKit](https://github.com/mpvkit/MPVKit)
- 자막: Kairan · Csora · Anissia · [Jimaku](https://jimaku.cc)
- 로컬 AI: [llama.cpp](https://github.com/ggml-org/llama.cpp)
- OP/ED 타임스탬프: [AniSkip](https://aniskip.com)
- This product uses the TMDB API but is not endorsed or certified by TMDB.

원본과 각 의존성의 라이선스 조건을 따릅니다.
