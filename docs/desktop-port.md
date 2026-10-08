# Desktop → iOS

기준: whispelyn-byte/LilacAnime-desktop, 4cf110427cf46de1f926554fbfdd0968d2821a61.

| 데스크탑 동작 | iOS 적용 |
|---|---|
| 여섯 영상 소스 | Linkkf, Ohli24, Linkani, Animenosub, ReAnime, Miruro |
| Miruro API | XOR/gzip 카탈로그, 회차 종류·방영일 필터, 트랙·서버·Referer/Origin |
| 한국어 자막 우선 | 제공 한국어 트랙 → Kairan/Csora/Anissia, 선호 제공자 우선 |
| 커뮤니티 자막 | Anissia 제작자 목록, Blogger/Tistory RSS·게시물·첨부, 직접 게시물 Naver 첨부 |
| 일괄 저장 | 선택 서버의 회차별 주소 해석, 영상·제공 자막 저장, 취소·재시도 |
| 재생 위치 번역 | 탐색 후 우선순위 재계산, 중복 대사 재사용, 부분 캐시·재개 |
| 클라우드 장애 대체 | GGUF가 선택된 경우 로컬 AI로 이어서 번역 |
| 용어 표기 | 고정 인사말·사용자 이름/용어 사전 |

SwiftUI 조작과 iOS 저장·백그라운드 제약에 맞게 구현했습니다. Electron 화면을 그대로 실행하지 않습니다.
데스크탑의 전체 한국어 제목 인덱스, AniList 등장인물 자동 표기·화자별 호칭, Naver 전체 블로그 검색, PC 폴더 지정 기능은 이 변경에 포함하지 않았습니다.
외부 서비스 로그인·캡차·다운로드 주소 유효성 및 실기기 재생은 별도 확인이 필요합니다.
