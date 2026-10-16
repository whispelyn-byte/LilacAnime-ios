# iOS Anime4K

플레이어 설정 > 재생 > Anime4K 영상 보정에서 끄기·가볍게·A(선명하게)·B(부드럽게)·C(노이즈 제거)를 선택한다. 기본은 끄기이며 기존 설정 파일에 해당 항목이 없어도 끄기로 연다. 선택은 앱 설정에 저장된다.

데스크탑 `scripts/anime4k-entry.mjs`의 네 프로필과 같은 모델·순서를 사용한다. 데스크탑은 Anime4K.js 1.1.3의 WebGL 포트, iOS는 그 원본인 Anime4K 4.0.1의 공식 mpv GLSL을 사용한다.

| 모드 | Clamp 이후 처리 |
| --- | --- |
| 가볍게 | Restore CNN S → Upscale CNN x2 S |
| A | Restore CNN M → Upscale CNN x2 M |
| B | Restore CNN Soft M → Upscale CNN x2 M |
| C | Upscale Denoise CNN x2 M |

mpv `gpu-next`의 `glsl-shaders`에 연결하므로 스트리밍·다운로드·로컬 영상의 기본 플레이어에 적용된다. 자막은 기존 mpv 자막 출력 경로를 사용한다. 셰이더 파일 누락, 설정 거부 또는 GPU 셰이더 오류가 나면 셰이더 목록을 비우고 원본 영상을 계속 재생한다. 오류 안내를 표시하고, 다른 모드를 선택하기 전에는 반복해서 적용하지 않는다.

공식 mpv 셰이더의 확대 조건을 수정하지 않았다. 예를 들어 CNN 업스케일은 출력 화면의 가로·세로 확대율이 모두 약 1.2배를 넘을 때 실행된다. 화면 크기에 관계없이 2배 처리하는 데스크탑 WebGL 구현과 정확히 같은 픽셀 출력이나 처리 비용을 보장하지 않는다. 원본보다 작은 화면에서는 업스케일 단계가 생략될 수 있다.

PiP는 기존 소프트웨어 출력, 웹·시스템·외부 플레이어는 별도 엔진을 쓰므로 Anime4K가 적용되지 않는다. 기존 스크린샷은 원본 해상도의 소프트웨어 캡처를 유지한다. 선택한 모드는 기본 플레이어로 돌아오면 유지된다.

셰이더와 MIT 라이선스를 앱 번들에 함께 넣는다. 원본 커밋·파일별 SHA-256은 `Resources/Anime4K/upstream.json`에 고정했다. CI에서 원본 해시를 검사하고, XCTest에서 번들 리소스 접근·기존 설정 호환·오류 판별을 확인한다. 실제 iPhone/iPad GPU에서의 화질·프레임 속도·발열은 별도 확인이 필요하다.

자료: [Anime4K 4.0.1](https://github.com/bloc97/Anime4K/tree/4029bf701ecaa15f163cdc49cffe5501c1acf410), [mpv GLSL hooks](https://mpv.io/manual/stable/#options-glsl-shaders).

## 검증 결과

[개발 브랜치 Actions 38884331629](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/38884331629) 성공: Swift 검사 140개(Anime4K 검사 3개 포함), iPhone UI 검사 6개, iPad UI 검사 4개 통과. 공통 검사·셰이더 원본 해시 검사 및 iOS 설치 파일 빌드도 성공했다.

생성된 시험 IPA에서 셰이더 7개와 MIT LICENSE의 SHA-256이 원본 기록과 일치하며 중복 리소스가 없음을 확인했다. 이 개발 빌드는 기준 버전인 0.4.13/빌드 55를 사용한다. 공개된 0.4.13 릴리즈 파일은 변경하지 않았으며 Anime4K 변경은 `codex/ios-anime4k` 브랜치에 있다. 실제 기기 GPU의 보정 출력·프레임 속도·발열은 검증하지 못했다.
