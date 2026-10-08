Android와 같은 앱 버전 **0.4.0**, iOS 빌드 **32** (v0.4.0-ios.2)입니다.

LilacAnime-desktop 0.5.5의 전체 기능군을 iOS 네이티브 구조로 이식했습니다.

- 기존 탭 UI·보관함·설정·기록·자막 싱크 보존; 선택형 데스크탑 사이드바와 iPad 화면.
- 전체 카탈로그·한국어/영어 제목·별칭 검색, TMDB/AniList/Wikidata, 한국어 소개·등장인물 표기.
- 소스별 방영/추천/필터, 관련 작품·조회 통계, 서버·화질 선택 보존.
- 제작자·Naver/Tistory/Blogger 첨부·Jimaku, 시즌 누적 회차, ZIP/7z/지원 RAR 자막과 폰트.
- 회차별 원본/번역 자막 보관·공유, 재생 장면 우선·다음 화·다운로드 자막 번역, 캐시 재개/재번역, 등장인물/호칭/용어집.
- 번역 API 연결·모델 목록·제공자별 모델, 오류 재시도/대체.
- 데스크탑 GGUF 모델 프리셋 받기·취소·이어받기, llama.cpp b11490·실제 Jinja·Thinking·Metal/CPU·실행 정보.
- 다운로드 전체 관리·오프라인 OP/ED 분석·영상/자막/폰트/분석 정보를 폴더로 내보내기·가져오기.
- 플레이어 음량/음소거/화면 맞춤·외부 키보드 단축키, 릴리즈 자동 확인·IPA 다운로드/공유.

**설정 → 데스크탑 작업 공간**에서 새 사이드바를 켭니다. 기존 탭 화면은 끄면 돌아옵니다.
LilacAnime-SideStore.ipa는 압축을 풀지 않고 SideStore로 가져옵니다. **기존 앱을 삭제하지 말고** 같은 앱으로 업데이트하세요.

SideStore 소스:
https://github.com/whispelyn-byte/LilacAnime-ios/releases/latest/download/source.json

PC 창/설치/가속/외장 폴더 동작은 iOS의 회전·SideStore·Metal/CPU·파일 앱 폴더 이전으로 대응합니다. 데스크탑 index.json과 iOS 내보내기 형식은 다릅니다.
실제 외부 영상 재생·SideStore 기기 설치·Cast·대형 모델 Metal 추론/품질은 실기기 확인이 필요합니다.
