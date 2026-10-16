# 데스크탑 전체 기능 대조 — 2026-10-16

기준: 데스크탑 **0.5.25**, `aadf40a12f511c334d4edc242b2d60518473e56d`. 별도 읽기용 체크아웃으로 고정했습니다. 이전 전체 대응 목록(0.5.15) 이후 변경된 파일을 모두 추려 기존 iOS 반영분과 재대조했습니다.

## 범위와 근거

[전체 파일/API 대응표](desktop-audit.json)는 소스·빌드·검사 파일 **77개**, preload API **100개**를 포함합니다. 31개 runtime 항목, 16개 네이티브 화면 대응, 1개 에셋, 3개 플랫폼 빌드, 26개 검증 항목입니다. 파일 대응 수는 분기 커버리지나 동작 일치율이 아닙니다. 같은 코드가 바뀌지 않은 부분은 이전 대조 기록과 최신 함수 실행 비교를 함께 사용했습니다.

| 영역 | 대조한 데스크탑 동작 | iOS 대응 및 결과 |
| --- | --- | --- |
| 홈·목록·검색·필터 | `catalog-browser`, `catalog-updates`, `app`, `ott`: 소스 분류, 정렬, 서버 필터, 커서/페이지, 한국어 색인, 내 목록 수 | `SourceRepository`, `DesktopCatalogTaxonomy`, `DesktopCatalogUpdates`, `DesktopCatalog`, `DesktopWorkspace`, `HomeView`: 최근 상세 필터와 TMDB 우선순위 반영 확인. 원본 필터 값과 사용자 요청 스크롤 유지 보존 |
| 작품 상세·회차 | `main`, `anime-metadata`: 방영일/상영일, 특전, 제목 속 숫자, 특별편, 다음 회차 | `DesktopSources`, `DesktopAnimeMetadata`, `PlaybackItem`: 회차 파싱 기존 반영 확인. 기록에서 12.5/4a/특별편 표시가 없어지는 추가 오류 수정 |
| 재생 주소·서버 | `resolveStreamPage`, `firstAnswering`, `flix-proxy` | `PlaybackResolver`, `DesktopFlixReadiness`, `HLSProxy`: 6초 서버 시도 간격, 30초 주소 탐색, 최대 10초 수집+15초 키 대기, 1.5초 안정화. 사전 미디어 검사는 제거. master 접미사 판정과 수집 종료 후 목록 고정도 일치시킴 |
| 웹 플레이어 | 재생 버튼 실행, 차단 감지, 탐색 창 종료, 별도 웹 재생 | `flix-discovery.js`: 버튼 클릭과 차단 감지 추가. 선택 후 탐색·숨은 미디어 재생 중지. 웹 플레이어 전환 때 탐색용 강제 음소거 해제, 실패 후 빈 웹뷰는 원본 페이지 로드 |
| 자막 선택·보관·검색 | `parseFlixSubtitleTracks`, `subtitle-store`, `community-matching`, `main`의 Jimaku/Anissia | 페이지 내 자막 목록 파서를 데스크탑에서 복사. 괄호 포함 이름·이스케이프 URL·형식·중복 처리 일치. 사이트/AI 자막 분리, 게시물·파일·시즌·묶음 선택 비교 사례 재생성 결과 동일 |
| 번역·모델 | `subtitle-translator`, `local-ai`, `anime-glossary`: 요청 형태, 취소/재시도, 모델 목록, 프롬프트, 용어 | `CloudTranslator`, `TranslationCoordinator`, `CloudSubtitleScheduler`, Native llama: 최신 키 검증·Qwen 지역·취소 반영 확인. 실제 데스크탑 함수의 프롬프트/정리/샘플링 결과와 비교 |
| 다운로드·오프라인 | `download-manager`, `download-transfer`, `flix-proxy`: 동시 2건, 조각 병렬 6건, 이어받기/취소, 폰트/자막/이식 | iOS의 기존 전송/복원 구현과 대조. 별도 영상·음성 목록이 서로의 선행 다운로드 순서를 덮던 오류 수정. Windows 폴더/트레이 시작 수정은 iOS 비대상 |
| 시청 기록·동기화 | `device-sync`: 긴 ID, URL 괄호, 변경 병합, 재생률 | `DeviceSync` 기존 긴 ID/괄호/로컬 파일 제외 반영 확인. 12.5/4a/OVA 표시를 Codable 기록·재개·송수신에서 보존하고 데스크탑의 특별편 필드 전달 |
| 플레이어 설정·입력·Anime4K | `player`, `anime4k`, `anime4k-entry`, ASS 렌더러: 맞춤/배율, 자막 스타일, 키 입력, 셰이더 | 네이티브 대응과 기존 검사 확인. 4개 Anime4K 프로필·순서 동일. 자동 스킵은 사용자가 지정한 재생 시간 누적/정지·버퍼링 제외 규칙을 우선 유지 |
| 업데이트·창·파일·배포 | `updater`, `desktop-state`, `preload`, 빌드 스크립트 | iOS 버전 비교·IPA/SideStore·파일 가져오기·회전/전체화면으로 대응. Electron 창/트레이/GPU 런타임 설치는 적용하지 않음 |

## 수정한 오류

1. FlixCloud 차단 화면에서도 주소 탐색 제한까지 대기하던 경로: 차단 감지 후 다음 서버 시도.
2. 클릭해야 시작하는 플레이어와 HTML 내 자막 목록 누락: 데스크탑의 클릭/파싱 동작 적용.
3. native 재생 중 탐색 웹뷰가 계속 재생을 시도하거나 웹 플레이어가 강제로 음소거되던 경로: 탐색 종료와 웹 전환 상태 분리.
4. 영상·음성 재생목록을 번갈아 읽으면 한쪽의 선행 다운로드 순서가 사라지던 경로: URL별 원래 목록 위치 보존.
5. master 주소의 접미사 판정 및 키를 기다리는 중 뒤늦게 발견한 주소로 선택이 바뀌던 차이: 데스크탑의 선택 시점/목록 고정 적용.
6. 기록/동기화에서 소수·문자 접미사·특별편 회차가 정수로 바뀌던 경로: 별도 표시 값을 보존.

## 검증

- 최신 데스크탑 단위 테스트 **174개 통과**, 실패/건너뜀 0. 최초 샌드박스의 로컬 HTTP 연결 차단은 허용된 로컬 실행에서 재검증했습니다.
- 데스크탑 실제 함수에서 비교 사례 **124개**, Jimaku 회차 점수 **56개**와 순서 **8개** 재생성. 기존 iOS/Kotlin 기준 결과와 동일했습니다.
- Flix discovery: 늦은 키, 별도 영상/음성 주소, 1회 재생 버튼 클릭, HTML 자막, 차단, 탐색 종료, 사전 요청 없음 검사 통과.
- 기존 애니24 광고/본편 구분 및 캡처 검사 통과.
- 추가 Swift 검사: 수집 종료 뒤 늦은 키, master 접미사, 영상/음성 선행 순서, 회차 표시 저장/동기화.
- 전체 JVM·Swift·iPhone/iPad UI·기기 빌드 결과는 최종 Actions 완료 후 기록합니다.

## 남는 차이와 확인 범위

- UIKit/SwiftUI·WKWebView·mpv/Metal과 Electron·Chromium·hls.js는 다른 엔진입니다. 공통 기능 규칙을 맞췄으며 모든 OS 동작이나 화면 픽셀이 동일하다는 판정은 아닙니다.
- PC MP4 병합 대신 iOS는 오프라인 HLS와 별도 자막/폰트를 보관합니다. iOS 로컬 추론은 단일 context, PC는 llama-server 슬롯/PC GPU를 사용합니다.
- iOS는 기존 시청 기록 최대 500건을 보존합니다(데스크탑 300건). 이번 대조를 이유로 사용자의 기존 기록을 줄이지 않았습니다.
- Anime4K의 GLSL 확대 조건, PiP/외부 플레이어, 시스템 파일·설치 처리 차이는 기존 `anime4k-ios.md`, `desktop-port.md`에 기록되어 있습니다.
- 외부 제공처의 모든 작품·계정·회차와 모든 실제 iPhone에서 전수 재현한 것은 아닙니다. 제보된 2배속 복귀와 무한 로딩의 특정 회차는 제공되지 않아 실기기 재현 확인은 남습니다.
