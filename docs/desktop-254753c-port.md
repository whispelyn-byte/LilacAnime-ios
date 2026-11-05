# 데스크탑 254753c · 466e6cb(WireGuard 부분) — iOS에는 반영하지 않음

비교 기준: 데스크탑 `d8f7978` → `254753c`(설정 > 연결: 이 앱만 WireGuard 터널로), `466e6cb`(.txt 설정 파일 · UTF-16 읽기), 릴리스 `bbbf8fd`(0.5.59) · `d16a9a5`(0.5.60).

## 데스크탑이 한 것

- WireGuard 설정 파일(.conf, 0.5.60부터 .txt도)을 불러오면 wireproxy(사용자 공간 WireGuard)를 자식 프로세스로 띄워 127.0.0.1에 HTTP 프록시를 열고, Chromium 세션 · Node fetch · ffmpeg 같은 자식 프로세스를 모두 그 프록시로 보냅니다. 켜 둔 동안 터널이 끊기면 통신을 막습니다.
- 새 파일 `electron/wireguard-proxy.cjs` · `electron/app-routing.cjs`, preload API `wireguardStatus` · `wireguardChoose` · `wireguardEnable` · `wireguardRemove` · `onWireguardState`.

## iOS에서 반영하지 않은 이유

- iOS 앱은 wireproxy 같은 실행 파일을 받아 자식 프로세스로 띄울 수 없습니다.
- 시스템 VPN 방식(Network Extension의 Packet Tunnel)은 SideStore로 설치하는 무료 개발자 계정 서명에서 쓸 수 없는 권한이 필요합니다.
- 남는 방법은 사용자 공간 WireGuard(wireguard-go + 네트워크 스택)를 앱 안에 라이브러리로 넣고 로컬 프록시를 여는 것입니다. 이 경우 URLSession, WKWebView(iOS 17부터 프록시 설정 가능), mpv/FFmpeg는 프록시로 보낼 수 있지만 AVPlayer(시스템 재생 · PiP)는 보낼 수 없어, 데스크탑처럼 "켜 두면 앱 통신이 VPN 밖으로 새지 않는다"를 지킬 수 없습니다. Go 빌드를 iOS 빌드에 더하는 일도 따로 검증해야 합니다.
- 그래서 이번 릴리스에서는 반영하지 않고, 앱 전체를 VPN으로 보내야 하면 App Store의 WireGuard 앱(시스템 VPN)을 쓰도록 안내합니다.

## 대응표

- `docs/desktop-audit.json`에서 위 두 파일과 다섯 API는 이 문서로 연결했습니다(반영하지 않음).
