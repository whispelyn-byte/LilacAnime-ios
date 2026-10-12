# 화면 캡처

이 폴더의 화면은 실제 iOS 앱을 시뮬레이터에서 실행해 찍은 것입니다. `--ui-preview`의 예시 작품을 사용하므로 실제 사이트의 작품 이미지·영상이나 네트워크 응답을 보여 주지는 않습니다.

- 앱 커밋: [`bbf1b64`](https://github.com/whispelyn-byte/LilacAnime-ios/commit/bbf1b6491c1e35bad64e1736aa9eadca43774db5)
- 캡처 실행: [KMP and iOS #38227691199](https://github.com/whispelyn-byte/LilacAnime-ios/actions/runs/38227691199)
- 원본: 실행의 `ios-ui-preview` 아티팩트
- 가로 화면은 PNG의 픽셀 방향을 화면에 맞게 회전했습니다. 화면 내용은 수정하지 않았습니다.

| 파일 | 화면 |
|---|---|
| `home-dark.png`, `workspace-iphone-dark.png` | iPhone 상단 탐색 메뉴와 홈 |
| `home-light.png` | iPhone 라이트 모드 홈 |
| `workspace-ipad-dark.png` | iPad 상단 탐색 메뉴·검색창과 홈 |
| `detail-dark.png` | 작품 소개, 회차 목록과 다운로드 |
| `catalog-dark.png` | 전체 작품, 검색과 상세 필터 |
| `settings-dark.png` | 설정 분류와 일반 설정 |
| `models-dark.png` | 로컬 AI 모델 |
| `player-dark.png` | 가로 전체 화면 플레이어 |
| `player-settings-dark.png`, `player-settings-1-dark.png`, `player-settings-2-dark.png` | iPhone 재생·자막·자막 모양 설정 |
| `player-settings-ipad-dark.png`, `player-settings-1-ipad-dark.png`, `player-settings-2-ipad-dark.png` | iPad 재생·자막·자막 모양 설정 |
| `player-fit-*-ipad-dark.png` | iPad 맞춤·채움·늘림 |

다시 찍으려면 GitHub Actions의 **KMP and iOS → Run workflow → capture_previews**를 켭니다. Apple Silicon Mac에서는 `CAPTURE_PREVIEWS=1 sh iosApp/scripts/verify.sh`로 테스트·빌드·캡처를 함께 실행할 수 있습니다. `verify.sh`가 생성하는 iPhone `home-dark.png`를 `workspace-iphone-dark.png`에도 복사합니다.
