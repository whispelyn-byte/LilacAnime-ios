# KMP / iOS

Android 원본 앱과 기능은 보존하고, 공통 모델·네트워크·소스·자막 로직을 shared Kotlin Multiplatform 모듈로 분리했습니다.
iOS는 SwiftUI, MPVKit/libmpv, background URLSession, Keychain, llama.cpp 및 libarchive를 사용합니다.
데스크탑 0.5.5 기준 기능 대응과 운영체제 차이는 [desktop-port.md](docs/desktop-port.md)에 있습니다.

## 빌드

Apple Silicon Mac, Xcode, JDK 17, XcodeGen이 필요합니다. 최소 iOS 16, simulator/device arm64를 빌드합니다.

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

Android app/module.toml의 versionName **0.4.0**을 유지합니다. iOS build = Android versionCode **30** + iosApp/revision.txt **2**, 즉 **32**이며 태그는 **v0.4.0-ios.2**입니다.
모든 검사에 통과한 Actions의 실기기 앱 아티팩트를 Package SideStore IPA 워크플로로 다시 패키징하여 Release에 올립니다. APK 생성은 이번 iOS 검증 절차에 포함하지 않습니다.

- [공개 저장소](https://github.com/whispelyn-byte/LilacAnime-ios)
- [최신 IPA](https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/LilacAnime-SideStore.ipa)
- [SideStore source.json](https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/source.json)

source.json은 IPA 실제 Info.plist의 버전·빌드·최소 iOS·권한·파일 크기로 생성합니다. 태그/앱 버전 불일치 시 게시를 중단합니다.
SideStore의 인증 갱신과 새 버전 설치는 별개입니다. 기존 앱을 삭제하지 않고 같은 Bundle ID/서명 계정으로 업데이트합니다.

## 검증 기록

이전 안정 빌드: [37733189254](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/37733189254), 34db062, 앱 0.4.0/build 31; JVM 24개·KMP iOS·네이티브 8개·device arm64·IPA.
전체 데스크탑 기능 이식 빌드는 현재 Actions에서 검증 중입니다. 최종 통과 실행과 IPA 검증 결과를 이 항목에 기록합니다.

## 실제 기기 확인이 남은 부분

- tiny GGUF CPU와 실제 템플릿 검증은 대형 모델의 Metal 추론·메모리·번역 품질을 입증하지 않습니다.
- 외부 영상/자막 서비스, 로그인·캡차·만료 주소 및 실제 기기 SideStore 설치·업데이트를 확인해야 합니다.
- iOS 백그라운드 재개, 폴더 제공자, 대형 파일 저장, 오프라인 분석 정확도는 기기 조건에 영향을 받습니다.
- PiP/AirPlay는 mpv의 외부 ASS를 그대로 표시하지 않습니다. Cast는 자막을 VTT로 변환하며 스타일 효과·미지원 코덱 변환을 제공하지 않습니다.
- LAN 중계는 Wi-Fi IPv4와 세션 토큰/자산 목록·경로 검증을 사용합니다. 앱을 재생 화면의 전경에 유지하고 Wi-Fi가 바뀌면 다시 연결합니다.
- Android 원본 Gradle/CMake 환경은 별도로 준비해야 하며 includeAndroid=true assembleDebug가 검증된 APK 빌드 절차는 아닙니다.
