# 오류 중심 추가 코드 검토 — 2026-10-17

데스크탑 기능 대응 대조에 이어 iOS 및 공통 코드의 취소·상태 전환·파일 처리 경로를 검토했습니다. 전체 소스 목록에서 비동기 작업, continuation, 파일 삭제, 숫자 변환, 강제 언래핑 사용처를 검색하고 호출부를 따라 확인했습니다. 모든 소스 줄과 모든 실행 분기를 전수 검증했다는 의미는 아닙니다. 외부 의존성 전체는 이번 검토 대상이 아닙니다.

## 검토 영역

- 재생: PlaybackResolver, EpisodePlayerModel, MPVEngine, HLSProxy, Flix 탐색, 백그라운드 복귀, 서버 전환과 늦은 결과 처리.
- 다운로드: DownloadStore, BackgroundDownloads, DownloadTransfer, DesktopDownloads. 요청 준비·복원·중단·재시도·완료 후 쓰기·내보내기.
- 검색/목록: CatalogModel, DesktopCatalog 및 공통 IosServices. 세대별 결과 무시와 취소 완료 전달.
- 자막/번역/모델: WinPNGReader, SubtitleArchive, TranslationCoordinator, LocalModelFiles, DesktopModelInstaller, CloudTranslator. 제한, 취소, 파일 경로 및 결과 저장.
- 저장/동기화/외부 재생: LibraryStore, BackgroundWriter, DeviceSync, LANMediaRelay, DesktopUpdater. 원본 기록 보존, 응답 상태, 파일/범위 처리.
- 공통 Kotlin 소스: 강제 변환/빈 컬렉션 접근 사용처와 기존 파서·검색·번역 회귀 검사.

## 확인하여 수정한 문제

1. `IosServices`: 시작 직후 취소하면 코루틴 본문이 실행되지 않아 Swift continuation을 깨울 콜백이 누락될 수 있었습니다. 콜백 처리 범위에 먼저 진입하고 실제 작업을 작업 디스패처로 보냅니다. 즉시 취소·종료 2개 테스트를 추가했습니다.
2. `DownloadStore`: 주소 준비 중인 회차는 대기열에서 이미 빠졌지만 다운로드 항목에는 없어서 중지/중복 판정에서 빠졌습니다. 현재 준비 회차를 추적하고 개별·작품 중지 및 늦은 결과 무시를 적용했습니다. 준비를 테스트 continuation으로 보류한 후 중지하는 검사 2개를 추가했습니다.
3. `DownloadStore`: 앱의 다운로드 복원 대기 중 큐를 비우면 재개 후 빈 큐에서 removeFirst를 호출할 수 있었습니다. 대기 후 취소/빈 큐를 다시 확인합니다. 복원과 작품 중지 경합 검사 1개를 추가했습니다.
4. `DownloadTransfer`: 초 단위 내보내기 폴더 이름이 겹치면 이전 결과와 충돌하고 실패 정리에서 그 폴더를 지울 수 있었습니다. UUID를 추가해 각 작업이 자신의 폴더만 사용하도록 했습니다. 연속 내보내기에서 앞선 결과 보존 검사 1개를 추가했습니다.
5. `DeviceSync`: 연결 해제/서버 교체 도중 이전 연결·동기화 요청이 완료되면 다시 연결되거나 이전 서버 데이터가 적용될 수 있었습니다. 연결 세대를 확인해 늦은 응답과 이전 401 오류를 무시합니다. 연결 중 해제 후 응답을 전달하는 검사 1개를 추가했습니다.

## 검증 범위

새 테스트와 기존 JVM·Kotlin Native·Swift·iPhone/iPad UI 검사는 GitHub Actions에서 실행합니다. 최종 결과는 완료 후 기록합니다. 실제 제보 기기와 회차가 없는 영상 건너뛰기/무한 로딩은 자동 테스트 통과만으로 해결 확정하지 않습니다.
