# KMP / iOS

Android 원본 앱과 기능은 보존하고, 공통 모델·네트워크·소스·자막 로직을 shared Kotlin Multiplatform 모듈로 분리했습니다.
iOS는 SwiftUI, MPVKit/libmpv, background URLSession, Keychain, llama.cpp 및 libarchive를 사용합니다.
데스크탑 0.5.5 기준 기능 대응과 운영체제 차이는 [desktop-port.md](docs/desktop-port.md)에 있습니다.

## 빌드

Xcode 26.3, JDK 17, XcodeGen이 필요합니다. Xcode 26.3은 macOS 15.6 이상을 요구합니다. 최소 iOS 16, device arm64 및 simulator arm64/x86_64를 빌드합니다. 자동 실행 테스트는 Apple Silicon에서 진행하고, Intel 시뮬레이터는 교차 컴파일과 아키텍처 검사를 수행합니다.

### Xcode에서 iPhone 화면 실행

Xcode와 iOS Simulator를 설치한 macOS에서 다음을 실행합니다. Intel Mac은 Universal 시뮬레이터 런타임이 필요합니다. VM에서의 시뮬레이터 부팅과 그래픽 동작은 별도 확인이 필요합니다.

~~~sh
git clone https://github.com/whispelyn-byte/LilacAnime-ios.git
cd LilacAnime-ios
brew install xcodegen cmake openjdk@17
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
sh iosApp/scripts/open-xcode.sh
~~~

스크립트는 로컬 AI 프레임워크와 Xcode 프로젝트를 준비해 엽니다. Xcode 상단에서 **LilacAnime → iPhone Simulator**를 선택하고 **Cmd+R**을 누릅니다. 실행 목적이 화면 확인이면 IPA나 Apple 개발자 서명은 필요하지 않습니다.

이미 빌드한 Intel 시뮬레이터 앱도 Actions의 ios-app-builds 아티팩트에서 `LilacAnime-simulator-intel.zip`으로 제공합니다. ZIP을 풀어 나온 `.app`을 실행 중인 iPhone Simulator에 드래그하거나 아래 명령으로 설치합니다.

~~~sh
xcrun simctl install booted /path/to/LilacAnime.app
xcrun simctl launch booted com.lilac.anime.ios
~~~

~~~sh
brew install xcodegen
sh ./gradlew :shared:jvmTest
sh iosApp/scripts/verify.sh
~~~

verify.sh는 KMP iOS 테스트, hash-pinned tiny GGUF test fixture, llama.cpp XCFramework 빌드/캐시, XcodeGen/SPM, iPhone 테스트, iPhone/iPad 예시 UI 캡처, unsigned iPhoneOS 앱·IPA 패키징을 수행합니다.
로컬 Xcode 실행은 Team과 인증서를 설정합니다. 배포 IPA는 SideStore에서 개인 계정으로 서명합니다.

| 의존성 | 버전 |
|---|---|
| Kotlin / Ktor | 2.3.21 / 3.1.3 |
| Gradle | 8.14.3 |
| MPVKit | 1.0.0 LGPL |
| Google Cast | 4.8.6 |
| ZIPFoundation | 0.9.19 |
| llama.cpp | b11490 / 9c2e0e491a822adae1f0b1c831adb4160057d24f |
| libarchive iOS package | b84d41ea6ecd1ab0b0cec6d60baa806808589cd5 |

[네이티브 소스/라이선스 설명](iosApp/Native/README.md).

## 버전과 배포

Android app/module.toml의 versionName **0.4.0**을 유지합니다. iOS build = Android versionCode **30** + iosApp/revision.txt **3**, 즉 **33**이며 태그는 **v0.4.0-ios.3**입니다.
모든 검사에 통과한 Actions의 실기기 앱 아티팩트를 Package SideStore IPA 워크플로로 다시 패키징하여 Release에 올립니다. APK 생성은 이번 iOS 검증 절차에 포함하지 않습니다.

- [공개 저장소](https://github.com/whispelyn-byte/LilacAnime-ios)
- [최신 IPA](https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/LilacAnime-SideStore.ipa)
- [SideStore source.json](https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/source.json)

source.json은 IPA 실제 Info.plist의 버전·빌드·최소 iOS·권한·파일 크기로 생성합니다. 태그/앱 버전 불일치 시 게시를 중단합니다.
SideStore의 인증 갱신과 새 버전 설치는 별개입니다. 기존 앱을 삭제하지 않고 같은 Bundle ID/서명 계정으로 업데이트합니다.

## 검증 기록

이전 안정 빌드: [37733189254](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/37733189254), 34db062, 앱 0.4.0/build 31; JVM 24개·KMP iOS·네이티브 8개·device arm64·IPA.
전체 데스크탑 기능 이식 빌드: [37751485817](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/37751485817), eae4aad, 앱 **0.4.0/build 32**. JVM **33개**, KMP iOS 테스트, Swift/C++ 네이티브 **19개**가 통과했고 simulator/device arm64 앱 및 unsigned IPA를 생성했습니다.

네이티브 검증에는 실제 tiny GGUF CPU 추론 2회와 실행 정보, 실제 Gemma 4 및 ChatML Jinja, 한글 ZIP/7z/RAR·CP949 파일명과 추출 경로, 이전 설정 JSON·4a 회차 순서·HLS 내보내기·LAN 검사가 포함됩니다.
생성된 IPA의 Info.plist·arm64 Mach-O·iPhoneOS 플랫폼·모델 프리셋 10개·라이선스 고지 및 테스트용 모델 미포함을 확인했습니다. 예시 UI 캡처는 실제 영상 서비스 재생 검증과 구분합니다.

이미 빌드한 simulator 앱만 다시 실행하는 Capture verified iOS app 워크플로도 제공합니다. iPhone/iPad 작업 공간을 실행 후 5/15/30초에 캡처하고 앱 로그를 저장하며, 앱을 다시 컴파일하지 않습니다.
[작업 공간 재캡처 37756362510](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/37756362510)에서 iPad 사이드바/본문과 iPhone 메뉴 복귀 버튼을 확인했습니다. README에는 앱 첫 실행을 기다린 30초 캡처를 사용합니다.

[릴리즈 패키징 37757654615](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/37757654615)도 성공했습니다. 공개 [v0.4.0-ios.2](https://github.com/whispelyn-byte/LilacAnime-ios/releases/tag/v0.4.0-ios.2) IPA를 다시 내려받아 재패키징한 Info.plist 외 앱 파일 **445개**의 내용이 검증한 device 아티팩트와 동일함을 확인했습니다. IPA와 SideStore source.json의 버전·빌드·크기·최소 iOS가 일치하며 공개 다운로드는 HTTP 200입니다.
릴리즈 IPA: **28,393,416 bytes**, SHA-256 **0f73d5ce8ce8d20920037a4c7dfdb2fdb3e4d2f1a255c68a3ae140ac2da7998d**.

## 실제 기기 확인이 남은 부분

- tiny GGUF CPU와 실제 템플릿 검증은 대형 모델의 Metal 추론·메모리·번역 품질을 입증하지 않습니다.
- 외부 영상/자막 서비스, 로그인·캡차·만료 주소 및 실제 기기 SideStore 설치·업데이트를 확인해야 합니다.
- iOS 백그라운드 재개, 폴더 제공자, 대형 파일 저장, 오프라인 분석 정확도는 기기 조건에 영향을 받습니다.
- PiP/AirPlay는 mpv의 외부 ASS를 그대로 표시하지 않습니다. Cast는 자막을 VTT로 변환하며 스타일 효과·미지원 코덱 변환을 제공하지 않습니다.
- LAN 중계는 Wi-Fi IPv4와 세션 토큰/자산 목록·경로 검증을 사용합니다. 앱을 재생 화면의 전경에 유지하고 Wi-Fi가 바뀌면 다시 연결합니다.
- Android 원본 Gradle/CMake 환경은 별도로 준비해야 하며 includeAndroid=true assembleDebug가 검증된 APK 빌드 절차는 아닙니다.
