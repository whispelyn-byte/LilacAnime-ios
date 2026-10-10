# 데스크탑 ↔ iOS 세부 코드 대조 — 2026-10-10

최신 비교 기준은 데스크탑 **0.5.10 / f387639**입니다. 리비전 8의 번역 응답 회복·수동 자막·메뉴·소개 정리·아이콘·다운로드 수정은 [테스터 제보 및 최신 대조](tester-fixes.md)에 따로 기록했습니다. 아래 리비전 7 결과는 당시 검증 기록입니다.

## 전체 목록 재대조 · iOS 리비전 7

기준은 데스크탑 `4550d61725a8b57e6e890e97bd0f25244a8cf164`와 2026-10-10 로컬 작업 폴더입니다. 대조 중 로컬 폴더에 다운로드 이어받기·그룹 제어·트레이 기능이 추가됐습니다. [대응 목록](desktop-audit.json)의 파일 해시·미커밋 표시로 비교한 버전을 구분합니다. 데스크탑 작업 파일은 수정하지 않았습니다.

파일 목록은 **62개**, preload API는 **95개**로 갱신했습니다. 런타임 소스·화면·CSS·에셋·빌드·테스트를 구분해 구현 위치를 연결했습니다. 파일/API 대응 수는 분기 커버리지나 동작 일치율이 아닙니다. 현재 확인한 동작 차이를 다음과 같이 수정했습니다.

| 경로 | 이번에 수정한 차이 | 검증 |
| --- | --- | --- |
| 전체 목록 | 최신순 미방영/미래 작품 제외, 평점 동률의 인기순·원본 순서, RE:Anime 최신순의 전체 목록 필터; 기존 목록 항목도 새 메타데이터로 교체하고 하루마다 갱신 | Kotlin 정렬·동률·미방영 검사, 네이티브 목록 순서·중복·교체 검사 |
| 제목 캐시 | 일반 조회 7일/일괄 조회 30일; 레거시 TMDB·나무위키 제목은 유효 시각이 없으면 재조회 | 두 TTL 경계·키 변경·레거시 캐시 검사 |
| 목록 분류 | 분류 캐시 10분, 애니노서브/링크애니 업데이트순에 페이지 분할 전 서버 필터 적용 | MockEngine URL/쿼리 검사 |
| 소스 메타데이터 | RE:Anime/Miruro 평점 100점→10점 환산, 방영 상태·시작일·분기·스튜디오, 마지막 커서 종료; Svelte/상세 대체 파서에도 전달 | 데스크탑 실제 RE:Anime 함수 비교·파서 회귀 검사 |
| 한국어 소스 | 회차 날짜, 업로드/총화수·평점·스튜디오, 애니24 목록 없는 영화의 자기 페이지 재생 | 상세 파서 회귀 검사 |
| 자막 파일 선택 | 임의의 13화 시작 보정 제거, 확인한 PREQUEL 회차만 보정; CRC·해상도·제목 숫자 오인 방지, 시즌·특전·묶음·ASS 우선 선택; 상위 5개 게시물을 별도로 시도; 수동 선택도 같은 게시물의 strict/bundle/회차 전달 | 데스크탑 실제 파일 선택 함수 17사례와 링크 선택 비교 |
| 게시물 목록/재시도 | 동시 조회 공유, 추가 페이지 최대 3개 동시 처리, 누락 시 기존 캐시 보존·1분 재시도; 실패한 첨부는 목록 갱신 후 바뀐 링크만 다시 시도; 중복 회차 링크·범위/다중 회차 묶음 유지, 12.5화 전달 | 공통 Blogger 전체/누락/최초 실패 3개 검사, 실제 링크 함수 11사례, 네이티브 첨부 재시도 검사 |
| Anissia | 다른 시즌의 연결 게시물은 자막·WinPNG 모두 차단; 제작자의 상위 게시물과 바뀐 링크를 대체 시도, 전체 Blogger 목록 공유, 제작자 별칭의 회차 제목 연결; 최근 갱신된 제작자 우선 순서 보존 | 다른 시즌 차단·여러 게시물/별칭·제작자 갱신 순서 공통 검사; 데스크탑 자막 검색 22개 검사 통과 |
| 이전 시즌 조회 | GraphQL 실패는 빈 회차 수로 캐시하지 않음, 성공은 10분/128개 보관; iOS 준비기의 실패한 문맥도 다시 조회 | 실패→성공→캐시 MockEngine 검사 |
| OP/ED ID | 원제·연도에 맞는 AniList/MAL ID, 429 한 번 대기, 실패 결과는 재시도 가능 | 정확한 원제/연도와 실패 후 재조회 검사 |
| Gemini 요청 | 배열 스키마, Thinking 옵션, 400/빈 응답 시 단순 요청과 성공 형태 기억, thought 제외 | 실제 요청 본문·400·후속 요청 MockEngine 검사 |
| Gemini 제한 | RetryInfo/메시지 대기, 네 번 요청 한도, PerDay/120초 초과는 다음 모델 | 제한 응답 검사·기존 모델 전환 검사 |
| 모델 규칙 | Gemini 목록/Flash 체인, OpenAI mini 기본, Qwen 별칭→날짜 순서·기본 목록 대체 | 기본값·목록·체인 회귀 검사 |
| 클라우드 오류 | OpenAI 429 billing만 모델 전환 차단, 5xx/404는 대체 가능; 모델 목록 20초·번역 180초 | 상태별 회귀 검사 |
| 번역 부분 결과 | 두 번 누락된 줄 때문에 나머지 줄을 중단하지 않음; 최종 미번역은 원문으로 사용, 미완료 캐시 분리 | 결과 집계 네이티브 검사, 완료/미완료 파일 경로 코드 대조 |
| 번역 캐시 | 공급자별 모델·키 해시·클라우드 대체 옵션을 설정 식별에 포함 | 코드 대조; 원본 키를 파일에 저장하지 않음 |
| HLS 저장 | 영상/오디오가 공유하는 키·초기화 파일 이름을 유지 | 영상+오디오+공유 AES 키의 재작성 목록 네이티브 검사 |
| 다운로드 | 실패/이전 시도 진행률 무시, 재시도 시 완료 조각 바이트 재계산, 동시 전송 속도 합산, 요청 180초 | 이전 시도/늦은 완료 콜백 검사·코드 대조 |
| 작업 복원 | 사용자가 중단한 작업은 앱 재시작 후 복구된 세션으로 다시 시작하지 않음 | 저장 상태와 세션 복원 경로 대조 |
| 자막 문자 | BOM UTF-16→UTF-8→EUC-KR 순서, 임의의 한국어 바이트를 UTF-16으로 읽지 않음 | EUC-KR/UTF-16/UTF-8 BOM 네이티브 검사 |
| 모델 파일 | URL에서 직접 받은 파일도 GGUF 헤더 검사 후 사용 | HTML 위장/정상 헤더/확장자 네이티브 검사 |
| iPad 사이드바 | 자동 열 접힘이 숨긴 버튼을 접근성 목록에 남기는 문제를 명시적인 사이드바 표시/제거로 수정; 홈·전체 목록 이동은 실제 표시 및 터치 가능 여부 확인 | iPad UI 3개 통과, 사이드바 캡처 확인 |

로컬 JVM **83개**, 데스크탑 실제 함수로 생성한 **124개 비교 사례**, Node 애니24 광고/본편 캡처 회귀 검사와 Swift 구문 검사가 통과했습니다. 데스크탑의 실제 자막 파일 선택·검색 테스트 **22개**도 통과했습니다.

### 리비전 7 최종 Xcode 검증

[최종 Actions](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/38014854096)는 런타임 커밋 `bfb3ede1776b84b2e7ef6cf5a46ee68befe07d91`으로 성공했습니다. 공통 테스트 **83개가 JVM과 iOS Simulator Arm64 양쪽에서 통과**했고, iOS 공통 결과는 실패·건너뜀 모두 0개입니다. Swift 단위 **67개**, iPhone UI **5개**, iPad UI **3개**도 실패 없이 통과했습니다.

기기용 앱·SideStore IPA와 Arm64/Intel 시뮬레이터 빌드가 성공했습니다. iPhone/iPad 캡처 **13개**를 생성했고, 새 iPad 사이드바가 홈 화면에 표시되는 것을 확인했습니다. 소스 버전은 **0.4.0 / 빌드 37**입니다. 산출물은 해당 Actions에서 받을 수 있으며, 이 기록은 새 GitHub 릴리즈를 발행했다는 뜻은 아닙니다.

### 의도적으로 남는 플랫폼 차이

- Electron 트레이·창 위치/크기·Windows DLL/GPU 런타임 설치는 iOS 시스템 창/앱 수명과 내장 Metal/CPU로 대응합니다. 트레이용 가짜 설정을 추가하지 않습니다.
- PC의 FFmpeg MP4 병합 대신 iOS는 네이티브 재생 가능한 오프라인 HLS·별도 자막/폰트를 보관합니다. 네이티브 URLSession이 HTTP Range/validator 이어받기와 백그라운드 전송을 담당합니다. 파일 형식·내부 캐시 키·오류 문구까지 동일하다는 뜻은 아닙니다.
- 로컬 추론은 iOS의 한 llama context에서 직렬 실행합니다. PC llama-server의 4개 슬롯, CUDA/ROCm/SYCL/Vulkan과 메모리·속도는 같지 않습니다. 같은 모델 계열의 지시문·정리·재시도 규칙은 직접 생성 비교 사례로 검사합니다.
- 앱 자체 설치는 SideStore 재서명이 필요합니다. 터치/길게 누르기·전체 화면·파일 앱·캐스팅의 운영체제 연결은 네이티브 방식입니다.
- 외부 서비스의 모든 작품/회차, 실제 계정 API 제한, 모든 GGUF·기기·네트워크 실패 조합을 전수 실행하지 않았습니다. 확인한 코드 차이를 수정했다는 결과이며 **모든 코드와 모든 동작이 100% 동일하다는 판정은 아닙니다.**

## 이전 리비전 6 기록

**수정 상태: 최초 대조에서 확인한 11건과 데스크탑 작업 폴더의 TMDB 후속 변경을 iOS 코드에 반영했습니다.** 아래 기존 대조 내용은 수정 전 기준(`2705534`)의 기록입니다. 모든 코드·기기 조건이 동일하다는 보증은 아닙니다.

## 이번 수정과 검증

| 최초 차이 | 반영한 동작 | 회귀 검증 |
| --- | --- | --- |
| 1 수동 로컬 → API 전환 | 요청의 `localOnly`를 설정 선택·실행·캐시에 전달, 수동 로컬 실패는 외부 API로 전환하지 않음 | PortRegressionTests의 초기 선택/실패 후 대체 경계 |
| 2 로컬 프롬프트 | 모델 이름/실제 GGUF chat template 분류, 원본 시스템·사용자 문구, 고유명사/화자 성별, 이전 미번역 고유 대사 2줄 | 실제 데스크탑 함수에서 생성한 프롬프트 14·분류 6·용어/등장인물 6 사례 |
| 3 로컬 정리/재시도 | 원문 줄 수만큼 마지막 줄, 래퍼/펜스/종료 토큰 정리, 빈 결과/가나 최대 3회, ja-ko 재시도 온도 0.5, 길이에 따른 최대 토큰, 120초 제한 | 데스크탑 정리 함수 6 사례·토큰/재시도 규칙; 실제 대형 모델 품질 비교는 별도 |
| 4 탐색 우선 요청 | MPV의 명시적 seek revision으로 짧은 탐색·자동 OP/ED/재개 탐색도 감지, 자연 재생 20초 경과와 구분 | seek/skip 이벤트 회귀 검사 |
| 5 링크애니 내장 자막 | 스트림별 burnedKorean 전달, 자체 외부 자막이 있는 영상만 해제, 재생/다운로드/오프라인/다음 화 준비에서 사용 | JVM 링크애니 파서 + 네이티브 상태/레거시 검사 |
| 6 다운로드 실패 횟수 | 실행 ID와 상태로 실패를 회차당 1번 집계, 형제 조각 취소, 늦은 성공/취소 무시, 작업 종료 후 30/120초 재시도 | 조각 6개 실패·늦은 성공/취소 검사 |
| 7 HLS 재개 | 토큰 제외한 선택 목록·조각 순서/타이밍의 식별자와 안정된 파일명, 새 요청 주소/헤더 사용, 이전 완료 조각 이관 | 토큰 갱신 전후 계획/파일명 및 화질·호스트 구분 |
| 8 자막 검색 실패 캐시 | 진행 중/성공만 재사용, nil은 제거해 같은 회차 재시도 가능 | 중복 요청 1회·실패 후 회복·성공 재사용 |
| 9 HLS 패딩 | TS 동기 바이트 검색 64KiB, 마지막 유효 오프셋 포함; PNG 패딩은 복호화 전에 검색, 다운로드·Cast에서도 적용; 일반 암호화 조각은 보존 | 1/4096/60000/65535바이트·PNG 패딩·암호화 조각 보존·기존 Ohli 검사 |
| 10 폰트 범위 | 압축/첨부에서 나온 폰트를 자막별 sidecar에 연결, 원본→번역/저장 시 유지, 선택한 자막 폰트만 회차 복사 | 서로 다른 자막의 폰트 연결 분리·보존 |
| 11 저장 20개 한도 | 새 항목을 반드시 포함하고 기존 앞 19개 보관, 동일 엔진 번역 교체 규칙 유지 | 21번째 백그라운드 자막 저장/재시작 검사 |
| TMDB 후속 변경 | 모든 호출이 150ms 간격·429 대기를 공유, Retry-After 숫자/날짜, 일시 오류 최대 3회; 인증 30분/기타 최소 1분·서버 대기 재시도 | 공통 JVM 동시 요청·일시 오류·인증·긴 대기·민감정보 제거 4개 테스트 |

기존 67개 + 새 로컬 32개로 **99개 데스크탑 직접 생성 비교 사례**를 유지합니다. 공통 테스트 **66개가 JVM과 iOS Simulator Arm64 양쪽에서 통과**했고, Node Ohli 캡처 회귀 테스트도 통과했습니다.

[최종 Actions #78](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/37941643897)는 런타임 커밋 `5c799b67c5146e15fc017152a701d0de0247cdab`으로 성공했습니다. XCTest 네이티브 **58개**, iPhone UI **5개**, iPad UI **3개**가 실패 없이 통과했고, 기기용 앱·SideStore IPA와 Arm64/Intel 시뮬레이터 빌드가 모두 성공했습니다. iPhone/iPad 캡처 13개를 생성해 iPad 메뉴·플레이어 설정 화면도 확인했습니다.

생성된 IPA는 **0.4.0/build 36, iphoneos/arm64, 최소 iOS 16.0**이며, 압축 무결성과 검증된 기기용 앱 파일 480개의 SHA-256 일치를 확인했습니다. 배포용 `source.json` 생성·버전/크기/개인정보 안내 필드 일치도 로컬에서 검사했습니다. 이 검증은 Actions 산출물 기준이며 새 GitHub 릴리즈를 발행한 기록은 아닙니다.

Xcode 26.3에서 iOS 18.5 시뮬레이터의 `libswiftWebKit.dylib`를 찾지 못한 실행 실패도 조사했습니다. [WebKit에 기록된 문제](https://bugs.webkit.org/show_bug.cgi?id=293831)와 같은 경로여서 WinPNG의 비동기 JavaScript 호출을 Objective-C로 연결했습니다. 기기 최소 지원 버전 iOS 16.0을 유지했고, 실제 변환 검사 `testWinPngUsesPostsConverterAndReadsConvertedBlob`도 최종 Actions에서 통과했습니다.

기존 사용자 프롬프트·샘플링 설정은 그대로 보존합니다(`modelSampling == false`). 새 기본 모델 모드에 데스크탑 규칙을 적용합니다. iOS 로컬 추론은 하나의 llama context를 직렬로 사용합니다. 데스크탑 llama-server의 4개 병렬 슬롯과 실행 자원/속도까지 같다는 의미는 아닙니다. 실제 기기·외부 사이트의 모든 회차·대형 GGUF 품질 비교는 완료하지 않았습니다.

## 비교 기준과 검증 범위

- 데스크탑: `4550d61725a8b57e6e890e97bd0f25244a8cf164`, 0.5.9. 로컬 작업 폴더의 미커밋 TMDB 변경은 아래에 별도로 기록합니다.
- iOS: `2705534887e3e8b2343a3a7218f9dfab25a15465`, 0.4.0/build 36 소스.
- 자막 준비·저장·수동 선택, 로컬/클라우드 번역, HLS·다운로드·재개, 제목 조회, 최근 업데이트, OP/ED, 플레이어 기본값과 업데이트의 주요 호출 경로를 읽어 대조했습니다. 모든 화면/CSS와 모든 입력·네트워크·기기 조건을 전수 검증한 것은 아닙니다.
- 기존 `desktop-audit.json`의 51개 파일/92개 API 대응은 구현 위치 목록입니다. 동작 동일성이나 분기 커버리지 수치로 해석하면 안 됩니다.

## 최초 대조에서 확인한 차이 (아래는 수정 전 기록)

### 1. 수동으로 고른 로컬 번역도 클라우드로 넘어갈 수 있음 — 우선 수정

데스크탑 [app.js:700](D:/proj/lilacanimedesktop/src/app.js:700)은 수동 로컬 요청에 `only: true`를 전달하고, [subtitle-translator.cjs:478](D:/proj/lilacanimedesktop/electron/subtitle-translator.cjs:478)은 API 대체를 제외합니다. iOS [EpisodePlayerView.swift:585](D:/proj/lilacanimeios/iosApp/LilacAnime/EpisodePlayerView.swift:585)의 로컬 버튼은 이 구분을 번역기에 전달하지 않고, [TranslationCoordinator.swift:140](D:/proj/lilacanimeios/iosApp/LilacAnime/TranslationCoordinator.swift:140)은 기본으로 켜진 `translationFallback`에 따라 API 키가 있는 클라우드를 선택합니다.

로컬 실행이 실패했을 때 데스크탑과 달리 자막이 외부 API로 전달되고 API 사용량이 소비될 수 있습니다. 자동 작업의 대체와 수동 로컬 요청의 대체를 구분해야 합니다.

### 2. 기본 로컬 모델 프롬프트가 원문 그대로 이식되지 않음

데스크탑 [local-ai.cjs:101](D:/proj/lilacanimedesktop/electron/local-ai.cjs:101)의 Gemma/general 프롬프트에는 반말·존댓말, 괄호 속 화자/효과음, 줄바꿈 유지, 일본어 이름의 한국어 표기, 문맥을 번역하지 말라는 지시가 있습니다. iOS [DesktopTranslationPrompt.kt:17](D:/proj/lilacanimeios/shared/src/commonMain/kotlin/com/lilac/anime/shared/DesktopTranslationPrompt.kt:17)은 축약된 별도 문구입니다. 데스크탑은 이전 2줄을 쓰고, iOS [TranslationCoordinator.swift:90](D:/proj/lilacanimeios/iosApp/LilacAnime/TranslationCoordinator.swift:90)은 기본 `contextCues = 6`을 씁니다.

샘플링 값 일부는 같아도 같은 입력을 모델에 전달하지 않습니다. 로컬 프롬프트·용어·등장인물 구성은 별도 이식이 남아 있습니다. 아래의 클라우드 기본 프롬프트 일치와 구분해야 합니다.

### 3. 로컬 결과 정리·일본어 잔존 재시도·출력 길이 제한 차이

데스크탑 [local-ai.cjs:504](D:/proj/lilacanimedesktop/electron/local-ai.cjs:504)은 코드 펜스·`target`·모델 종료 표식을 정리하고 원문 줄 수만큼 마지막 줄을 남깁니다. [local-ai.cjs:546](D:/proj/lilacanimedesktop/electron/local-ai.cjs:546)은 빈 결과나 가나가 남은 결과에 최대 3회 요청하며 ja-ko-vn 재시도 온도를 바꿉니다. [local-ai.cjs:529](D:/proj/lilacanimedesktop/electron/local-ai.cjs:529)의 최대 출력은 `min(512, 48 + 원문 길이 × 3)`입니다.

iOS [TranslationCoordinator.swift:101](D:/proj/lilacanimeios/iosApp/LilacAnime/TranslationCoordinator.swift:101)은 한 번 생성하고 빈 문자열만 검사합니다. [LocalTranslationPrompt.kt:12](D:/proj/lilacanimeios/shared/src/commonMain/kotlin/com/lilac/anime/shared/LocalTranslationPrompt.kt:12)의 정리 함수에는 위의 원문 줄 수 처리와 모든 래퍼 처리가 없고, [LocalModelsView.swift:70](D:/proj/lilacanimeios/iosApp/LilacAnime/LocalModelsView.swift:70)은 설정의 고정 최대 토큰 수를 사용합니다. 문맥까지 번역된 결과나 일본어가 남은 결과를 저장할 수 있습니다.

### 4. 클라우드 탐색 우선 요청을 만드는 계기가 다름

데스크탑 [subtitle-translator.cjs:406](D:/proj/lilacanimedesktop/electron/subtitle-translator.cjs:406)은 탐색 이벤트에서 해당 위치의 미번역 줄이 큰 요청에 묶여 있는지 확인해 작은 요청을 보냅니다. iOS [CloudSubtitleScheduler.swift:54](D:/proj/lilacanimeios/iosApp/LilacAnime/CloudSubtitleScheduler.swift:54)은 250ms 간격으로 위치를 관찰하고 마지막 기준 위치와 20초 이상 달라졌을 때만 추가 요청을 검토합니다.

10초 탐색은 즉시 추가 요청을 만들지 않을 수 있고, 자연 재생으로 20초가 경과해도 탐색처럼 평가할 수 있습니다. `position - 5초` 정렬과 40줄 우선 묶음이 같다는 사실만으로 전체 탐색 동작이 같다고 볼 수 없습니다.

### 5. 링크애니의 영상 내장 한국어 자막 상태가 전달되지 않음 — 우선 수정

데스크탑 [main.cjs:744](D:/proj/lilacanimedesktop/electron/main.cjs:744)은 링크애니 영상을 기본 `burnedKorean: true`로 취급하고, 영상 ID에 맞는 외부 자막이 있을 때만 이를 해제합니다. iOS의 `DesktopPlaybackStream`/`ResolvedStream`에는 이 상태가 없고, [DesktopSubtitlePolicy.swift:38](D:/proj/lilacanimeios/iosApp/LilacAnime/DesktopSubtitlePolicy.swift:38)은 애니24만 영상 내장 한국어로 분류합니다.

링크애니에서 영상에 한국어가 이미 들어 있고 외부 트랙이 없는 경우, iOS는 커뮤니티 자막을 추가 검색·적용할 수 있습니다. 자막 중복과 불필요한 번역을 막으려면 스트림별 상태를 보존해야 합니다. 링크애니 전체를 무조건 내장 자막으로 고정하는 수정도 외부 자막 영상에는 맞지 않습니다.

### 6. 다운로드 재시도 횟수가 작업 단위가 아닌 실패 조각 수로 늘어남

데스크탑 [download-manager.cjs:153](D:/proj/lilacanimedesktop/electron/download-manager.cjs:153)은 한 회차 실행이 실패할 때 한 번 증가시킵니다. iOS [DownloadStore.swift:375](D:/proj/lilacanimeios/iosApp/LilacAnime/DownloadStore.swift:375)은 각 `backgroundFailed`에서 같은 회차의 카운터를 증가시킵니다.

동시에 받던 6개 조각이 연결 끊김으로 함께 실패하면 실제 재시도 전 카운터가 여러 번 증가할 수 있습니다. 첫 30초 예약이 두 번째 실패로 120초 예약으로 바뀌고, 이후 자동 재시도 예산도 일찍 소진될 수 있습니다. 2회차×6조각 및 30초/120초 상수는 같지만 실패 상태 전이는 다릅니다.

### 7. 토큰만 바뀐 HLS 주소에서 기존 조각을 재사용하는 기준 차이

데스크탑 [download-manager.cjs:217](D:/proj/lilacanimedesktop/electron/download-manager.cjs:217)은 선택한 목록의 origin/path로 서버·화질의 동일성을 확인해 요청 토큰이 바뀌어도 기존 조각을 재사용합니다. iOS [DownloadStore.swift:152](D:/proj/lilacanimeios/iosApp/LilacAnime/DownloadStore.swift:152)은 전체 URL 변경으로 계획을 초기화하고, [BackgroundDownloads.swift:68](D:/proj/lilacanimeios/iosApp/LilacAnime/BackgroundDownloads.swift:68)은 조각의 쿼리까지 포함한 전체 URL을 파일 이름에 사용합니다.

재해석한 조각 주소의 쿼리 토큰이 바뀌면 같은 영상도 다른 파일로 저장되어 다시 받을 수 있습니다. 서버/화질이 바뀌었을 때 섞이지 않도록 하는 검증과, 토큰만 갱신됐을 때의 재사용을 함께 맞춰야 합니다.

### 8. 커뮤니티 자막 검색 실패 Task를 계속 재사용함

데스크탑 [app.js:517](D:/proj/lilacanimedesktop/src/app.js:517)은 모든 제공자가 실패하면 검색 캐시를 비웁니다. iOS [DesktopSubtitlePreparer.swift:61](D:/proj/lilacanimeios/iosApp/LilacAnime/DesktopSubtitlePreparer.swift:61)은 제공자별 Task를 재사용하지만 `nil` 결과를 제거하지 않습니다. 캐시는 [DesktopSubtitlePreparer.swift:151](D:/proj/lilacanimeios/iosApp/LilacAnime/DesktopSubtitlePreparer.swift:151)의 `cancel()`에서 비워집니다.

같은 준비기·회차에서 서버 장애가 회복되어도 자동 준비/한국어 자막 제안은 이전 실패 결과를 다시 사용할 수 있습니다. 별도의 수동 `search()` 경로는 직접 API를 호출하므로, 수동 검색까지 모두 막힌다는 뜻은 아닙니다.

### 9. HLS 영상 조각 앞의 임의 패딩을 처리하는 범위가 다름

데스크탑 [download-manager.cjs:409](D:/proj/lilacanimedesktop/electron/download-manager.cjs:409)은 TS 동기 바이트 3개를 처음 65,536바이트 범위에서 찾습니다. iOS [HLSProxy.swift:70](D:/proj/lilacanimeios/iosApp/LilacAnime/HLSProxy.swift:70)의 검증 검색 범위는 4,096바이트이며, 일반 FlixCloud 다운로드의 [DownloadStore.swift:260](D:/proj/lilacanimeios/iosApp/LilacAnime/DownloadStore.swift:260)은 그 검증 함수 대신 알려진 PNG/WebP 헤더의 `fragment()`만 사용합니다.

기본 PNG/WebP 형태와 애니24 HTML 조각은 처리하지만, 임의 패딩의 길이/형태가 달라지면 재생과 다운로드의 결과가 달라질 수 있습니다. 모든 이미지 위장 조각이 동일하게 처리된다는 판정은 불가합니다.

### 10. 다운로드에 복사하는 폰트의 범위 차이

데스크탑 [download-manager.cjs:267](D:/proj/lilacanimedesktop/electron/download-manager.cjs:267)은 해당 자막에 딸린 `found.fonts`를 작품 폴더에 보관합니다. iOS [DownloadStore.swift:437](D:/proj/lilacanimeios/iosApp/LilacAnime/DownloadStore.swift:437)은 공용 폰트 폴더의 모든 TTF/OTF/TTC를 각 회차에 복사합니다.

다른 작품의 폰트까지 회차마다 중복 저장하므로 저장 공간·내보내기 크기가 커집니다. 현재 자막과 함께 준비한 폰트의 출처를 추적하는 처리가 필요합니다.

### 11. 저장 자막이 20개 찼을 때 새 백그라운드 자막 처리 차이

데스크탑 [subtitle-store.cjs:40](D:/proj/lilacanimedesktop/electron/subtitle-store.cjs:40)은 기존 앞 19개 뒤에 새 백그라운드 자막을 넣어 새 항목을 보존합니다. iOS [EpisodeSubtitleStore.swift:26](D:/proj/lilacanimeios/iosApp/LilacAnime/EpisodeSubtitleStore.swift:26)은 백그라운드 항목을 오래된 순으로 정렬한 뒤 [49행](D:/proj/lilacanimeios/iosApp/LilacAnime/EpisodeSubtitleStore.swift:49)에서 앞 20개만 남깁니다.

예를 들어 서로 다른 제공자/파일의 기존 백그라운드 원본 20개 뒤에 새 원본을 저장하면, iOS에서는 새 항목이 바로 제외될 수 있습니다. 보관 한도는 같아도 어느 항목을 버리는지가 다릅니다.

## 최초 대조 당시의 데스크탑 미커밋 후속 변경

대조 시점에 데스크탑 `electron/main.cjs`, `src/app.js`, `tests/catalog-title-retry.test.cjs`가 수정되어 있었고 `electron/tmdb-client.cjs`, `tests/tmdb-client.test.cjs`는 미추적 파일이었습니다. 데스크탑 파일은 변경하지 않았습니다.

새 [tmdb-client.cjs:15](D:/proj/lilacanimedesktop/electron/tmdb-client.cjs:15)은 요청 간격 150ms, 모든 호출의 공통 429 대기, `Retry-After`, 일시 오류 최대 3회 요청을 구현합니다. 제목 수집도 인증 오류는 30분, 그 외는 최소 1분/서버 지정 시간에 재시도하고 구체적인 상태를 표시합니다. 당시 iOS `TmdbTitleResolver.kt`에는 이 공통 요청 제어가 없었고, `DesktopCatalog.swift`는 실패 시 고정 30분 재시도였습니다. **최초 대조 당시 미반영이었으며, 위 표의 이번 수정에 포함했습니다.**

## 최초 대조 당시 일치를 확인한 범위와 실행 증거

- 데스크탑 소스로 비교 입력/기대값을 다시 생성하되 파일 쓰기를 가로챘습니다. 기존 67개 사례의 JSON/Kotlin fixture, WinPNG 스크립트, 클라우드 기본 프롬프트가 현재 파일과 일치했습니다. 결과는 로컬 `.tools/audit-oracle-check.json`에 있습니다. 이 67개가 실제 네이티브에서 이번에 모두 실행됐다는 뜻은 아닙니다.
- `DesktopParityTest` JVM 테스트 **13개 통과, 실패/오류/건너뜀 0**. 제목 정규화·시즌/OVA 표기·TMDB 검색어/시리즈 별칭·커뮤니티 링크/묶음/폰트·Svelte 표·공개 회차·FFT/반복 구간·부분 번역 JSON ID 등을 검사했습니다. 로컬 로그는 `.tools/review-parity-jvm.log`, JUnit은 `shared/build/test-results/jvmTest/TEST-com.lilac.anime.shared.DesktopParityTest.xml`입니다.
- 호출 경로에서도 자동 스킵 기본 꺼짐/버튼 켜짐, 2.5초 스킵 지연, 한국어 자막 제안 20초, 제공자 검색 동시 시작 후 선호순 소비, 다운로드 2회차×6조각 상한, 최근 업데이트 5분 캐시·즐겨찾기 최대 8페이지의 대응을 확인했습니다. 위의 실패/한도 조건 차이는 이 정상 경로 확인과 별개입니다.
- OP/ED는 **데스크탑도 온라인 재생에는 AniSkip만 사용**합니다(`main.cjs:2193`). 온라인 오디오 분석이 iOS에 빠졌다는 항목은 차이로 채택하지 않았습니다. 오프라인 분석의 FFT·반복 구간·합의 기준은 기존 비교 테스트 범위에 포함됩니다.
- Windows에서 실행한 이번 JVM 검사는 Swift/Metal·실제 iPhone·외부 사이트·SideStore·대형 GGUF 추론을 검증하지 않습니다. 위 차이는 정적 코드/호출 경로 대조 결과이며 실제 기기에서 모두 재현한 버그 목록은 아닙니다.

## 후속 확인

최종 네이티브 빌드·테스트 결과는 문서 위에 기록했습니다. 실제 기기·모든 외부 서버 조합·대형 GGUF 품질 비교는 별도 확인 대상이며, 이 문서의 수정 완료 항목은 이 조건의 전수 검증 판정을 뜻하지 않습니다.
