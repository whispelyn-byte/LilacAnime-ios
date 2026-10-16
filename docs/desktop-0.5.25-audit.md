# 데스크탑 0.5.25 대조

2026-10-16 [aadf40a1](https://github.com/whispelyn-byte/LilacAnime-desktop/commit/aadf40a12f511c334d4edc242b2d60518473e56d)을 0.5.24 이후 변경과 비교했다.

- Windows Videos Known Folder 및 외장 다운로드 폴더가 없거나 쓰기 불가능할 때 대체 폴더를 선택한다.
- Electron 초기화/IPC 등록이 끝나기 전에는 두 번째 실행이나 트레이에서 창을 열지 않는다.
- 치명적 시작 오류는 로그와 대화상자로 알리고 종료한다.

iOS는 Electron IPC·트레이·Windows Known Folder 및 외장 다운로드 루트 선택을 사용하지 않는다. `DownloadStore.directory`는 앱 Application Support의 Downloads이고, 생성자는 해당 폴더를 만들다가 실패하는 경로가 없다. 목록은 `try? Data(contentsOf:)`와 디코딩 실패 시 빈 목록으로 복구하며, 저장 시 폴더 생성/쓰기 오류는 `persist()`의 catch 또는 `BackgroundWriter` 실패 콜백으로 전달한다. `LilacAnimeApp`의 초기 복원 순서도 창 재생성과 IPC 등록 경쟁에 해당하지 않는다.

따라서 이번 커밋에서 iOS에 추가로 이식할 기능 코드는 없으며, 진행 중인 v0.4.14 릴리즈 빌드를 변경하지 않았다. 다운로드 저장소를 실제로 읽을 수 없는 상태에서 새 다운로드가 성공한다는 의미는 아니다.
