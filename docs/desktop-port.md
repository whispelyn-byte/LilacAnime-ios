# Desktop → iOS 이식 목록

기준: [LilacAnime-desktop 0.5.5](https://github.com/whispelyn-byte/LilacAnime-desktop/tree/4cf110427cf46de1f926554fbfdd0968d2821a61).
데스크탑 preload의 콘텐츠·자막·번역·다운로드·업데이트 작업을 아래 네이티브 기능으로 대응했습니다. Electron 창을 iOS 안에서 실행하는 구조는 아닙니다.

## 기존 기능·데이터 보존

기존 탭 UI가 기본입니다. 설정의 데스크탑 작업 공간은 선택 사항이며 iPad에선 NavigationSplitView를 사용합니다.
기존 library.json, 즐겨찾기, 시청 기록, 자막 싱크, API Keychain, 수동 모델 가져오기·Hugging Face 검색, mpv/ASS, Cast/PiP 기능을 유지합니다. 새 설정 필드는 optional로 추가해 이전 JSON을 읽습니다.
새 이름 인덱스·저장 자막·모델 메타데이터는 별도 저장하며, 설정 초기화나 보관함 자동 삭제를 하지 않습니다.
업데이트는 기존 앱을 지우지 않고 같은 Bundle ID/서명 계정으로 SideStore에 가져옵니다.

## 기능 대응

| 데스크탑 기능 | iOS 구현·조작 |
|---|---|
| 홈·검색·내 목록·설정 | 기존 탭 + 선택형 사이드바; 시청 기록·즐겨찾기·다운로드 별도 화면 |
| 여섯 영상 소스 | Linkkf, Ohli24, Linkani, Animenosub, ReAnime, Miruro |
| 검색·필터·목록 | 서버 필터, 소스별 장르/연도/형식/상태 등 지원 필터; 한국어 캐시 검색 + TMDB 원제 변형 검색 |
| 시즌·순위·방영표 | 소스별 시즌/인기·요일 목록; Linkkf PV/극장판/16+; 장애 시 Jikan 메타데이터를 별도 표기 |
| 전체 카탈로그 | 페이지 수집, 루프 방지, 파일 저장·재개, 정렬/연도/형식 필터, 인덱스 상태·중지/새로 수집/삭제 |
| 한국어 제목·별칭 | TMDB → AniList → Wikidata; Wikidata AniList 한국어 제목 일괄 인덱스 |
| 제목 언어·시즌 표기 | 원제/한국어/영어; 2기 등 시즌 보존; 한국어 제목·별칭 검색 |
| 한국어 줄거리 | 홈 추천·작품 상세의 TMDB 한국어 소개; 시즌 소개 우선, 원본 소개 대체 |
| 작품 상세·서버·관련 작품 | 회차·서버 선택 기억, 필러/총집편/재생 불가 표시, Linkkf 관련 시리즈·조회 통계 및 9초 후 조회 기록 |
| 제공 영상·자막 트랙 | ReAnime API와 기존 파서 대체, Miruro XOR/gzip API, 서버/언어/화질 및 헤더 |
| 영상 주소 해석 | 기존 WKWebView 탐지 + 직접 API 해석, 영상 스트림 검사 및 다운로드 화질 선택 |
| 한국어 자막 자동 준비 | 제공 한국어 → 선호 Kairan/Csora/Anissia; 일본어 Jimaku/제공 트랙; 회차별 저장 원본 재사용 |
| Anissia 제작자 | 제작자 목록, 사이트를 지정한 자막 검색, 블로그 게시물·첨부 |
| 블로그 자막 | Kairan·Csora 페이지 인덱스, Naver 블로그 목록/검색/PostView/첨부, Tistory RSS/검색, Blogger JSON/RSS |
| 시즌 누적 회차 | AniList PREQUEL 연결로 확인한 이전 시즌 회차 수; 명시된 다른 시즌을 제외 |
| Jimaku 파일 | AniList ID 검색·6시간 인덱스·시즌/회차/극장판 파일·ASS 품질·이전 릴리스 우선 선택·다운로드 및 자동 번역 원본 |
| 파일/압축 자막·폰트 | ASS/SSA/SRT/VTT/SMI 등 기존 형식, ZIP/7z/지원 RAR; CP949 파일명, 폰트 추출·등록 |
| 저장 자막 | 원본·번역 분리, 제공자/이름/회차 표시, 선택/삭제/파일 공유 |
| 자막 캐시 | 크기 표시, 보관함/저장 자막을 보호한 오래된 캐시 정리, 명시적 전체 삭제 |
| 자막 표시·싱크·스타일 | mpv ASS, 크기/색/테두리/굵기/높이/폰트/효과, 회차별 싱크와 자막 토글 |
| 번역 API | Gemini/OpenAI/DeepL/Qwen, 키·연결 테스트·지원 모델 목록·제공자별 모델 보존 |
| 모델 대체·재시도 | quota/404/busy 대응·모델 체인, 서버 오류 재시도, 등록 API 간 대체 및 로컬 대체 |
| 비용 관련 동작 | 자동 체인은 Flash/Lite, mini/nano, Qwen plus/flash/turbo 계열; OpenAI billing/insufficient_quota는 모델 전환 중단 |
| 재생 장면 우선 번역 | 중복 대사 캐시, 부분 결과 저장/즉시 표시, seek 우선순위, 클라우드 2개 묶음 + 탐색 우선 요청 |
| 다음 화·다운로드 번역 | 현재 화 이후 별도 우선순위 작업, 다음 회차 자막 준비/번역, 저장된 외국어 자막 순차 번역 |
| 다시 번역·사용자 용어 | 제공자를 지정한 캐시 초기화 재번역, 자동 등장인물 이름/화자 호칭·사용자 사전 우선 |
| 로컬 모델 설치/삭제 | Gemma 4/Aya/Hy-MT2 등 프리셋, background URLSession 받기·취소·이어받기·삭제, 토큰/수동 가져오기 |
| 로컬 추론 | llama.cpp b11490 소스 빌드, 실제 GGUF Jinja, Thinking, 모델별 프롬프트/샘플링, 공유 컨텍스트·취소·idle 해제 |
| 실행 위치·성능 | Metal/CPU 설정 및 실패 시 CPU 재시도; 마지막 backend·토큰·소요 시간 표시 |
| 플레이어 | mpv, 재생/탐색/음량/음소거/contain-cover-stretch, 잠금/자동 숨김/전체 화면 |
| 단축키·회차 이동 | Space/방향키/F/M/C/Z/X/S/0–9/배속/PageUp/PageDown/Esc, 이전/다음/이어보기 |
| OP/ED | AniSkip + 저장 영상 반복 오디오 분석; 자동 분석 설정, 수동 분석, 구간 스킵 및 캐시 삭제 |
| 단일/일괄 다운로드 | 서버 회차 주소 준비, 진행·속도·바이트, 제한된 동시 전송, 취소/이어받기/재시도/전체 삭제 |
| 오프라인 재생 | HLS manifest/segment/key 상대 경로 저장, 영상·원본/번역 자막·폰트, 작품별 목록·다음 화 |
| 다운로드 폴더 이전 | 완료한 파일과 index.json/OP·ED 정보 내보내기/가져오기; 헤더·Cookie·resume 파일 제외 |
| 로컬 영상·외부 링크 | 파일 앱 영상 선택 후 Documents 보존, 시스템 링크 열기; 수동 영상 URL 입력 UI는 재추가하지 않음 |
| 업데이트·릴리즈 노트 | 앱 실행/복귀 후 최대 6시간 간격 최신 확인, 빌드 번호 비교·노트·IPA 다운로드/공유·SideStore 소스 |
| 기존 iOS 연결 | PiP/AirPlay/Chromecast 및 LAN 중계 유지 |

## 운영체제에 따른 대응과 차이

- PC 창 제목줄/창 버튼/멀티 윈도우는 iOS 내비게이션·전체 화면·회전으로 대응합니다.
- CUDA/Vulkan AI 가속은 Metal/CPU로 대응합니다. 프리셋은 같지만 기기 메모리 때문에 모든 대형 모델의 실행을 보장하지 않습니다.
- PC의 항상 열려 있는 외장 다운로드 폴더는 파일 앱을 통한 폴더 내보내기/가져오기로 대응합니다. 가져온 회차는 앱 저장 공간에 복사해 재생합니다.
- iOS에서 앱의 자체 설치/재서명은 제공할 수 없어 IPA 받기와 SideStore 업데이트로 대응합니다.
- iOS가 앱을 중단하면 카탈로그·번역·분석은 앱을 다시 열어 이어갑니다. 영상·모델 전송에는 background URLSession을 사용합니다.
- API의 일시 소진 모델은 일정 시간 제외하며, 데스크탑의 Gemini 태평양 시간 자정 리셋을 완전히 동일하게 계산하는 방식은 아닙니다.
- ASS는 mpv에서 렌더링합니다. 시스템 PiP/AirPlay는 같은 외부 자막 표시를 제공하지 않으며, Cast는 VTT로 변환해 스타일 효과를 보존하지 않습니다.
- 암호화/분할 압축 자막과 모든 RAR 압축 변형을 약속하지 않습니다. 추출할 수 없는 파일은 오류를 표시합니다.
- 데스크탑과 iOS의 index.json 저장 모델이 달라, **iOS에서 내보낸 다운로드 폴더**를 다른 iOS 설치로 이전하는 형식입니다. 기존 PC 다운로드 인덱스를 그대로 가져오는 기능은 제공하지 않습니다.

## 검증

공통 JVM 테스트 33개: 파서·자막 타이밍/스타일·시즌/누적 회차·이름/호칭·모델 프롬프트·번역 모델 대체·OpenAI 과금 quota·캐시.
네이티브 테스트: 이전 설정 JSON, 시청 기록/회차 suffix, 실제 Gemma 4/ChatML Jinja, 실제 tiny GGUF CPU 추론·재사용·metrics, ZIP/7z/RAR/CP949·추출 경로, 폴더 이전의 HLS 자산 및 resume 제외, 기존 LAN/HLS 테스트.
Actions는 KMP iOS 테스트·iPhone 테스트·iPhone/iPad 예시 캡처와 unsigned arm64 IPA를 생성합니다. 최신 통과 실행 링크는 [KMP_IOS.md](../KMP_IOS.md)에 기록합니다.

대형 Gemma/Aya/Hy-MT2의 실기기 Metal 추론, 번역 품질·메모리, 외부 사이트의 실제 영상/첨부·로그인/캡차, SideStore 설치/폴더 앱 제공자/백그라운드 복귀, Chromecast 실물 동작은 아직 실기기 확인이 필요합니다. tiny GGUF는 런타임 동작 검증이며 대형 모델 성능 검증이 아닙니다.
