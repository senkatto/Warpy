# VPN recovery verification, 2026-09-06

## Changes

- Windows service checks the selected tunnel every 30 seconds even without network,
  resume or unlock notifications. It uses the existing authenticated proxy probe
  and bounded recovery. Network event debouncing takes priority over the timer.
  Explicit tray exit still stops the VPN.
- Android rebuilds the core when a network event cancels an unfinished startup or
  recovery transaction. It no longer validates a potentially destroyed tunnel.
  Startup now also handles network changes, and the generated configuration uses
  the requested profile index rather than a possibly different saved index.

## Verification

- Windows: 64 Rust tests passed, one existing test ignored; 105 JavaScript tests
  passed. New regression tests cover periodic checks without events and timer
  interaction with network settling.
- Android: 185 production unit tests passed. New regression tests cover network
  arrival during startup and network return during a core restart, including
  rejection of stale completion events. Production lint passed after correcting
  the drive separator escaping in the local, untracked signing properties.
- Production APK installed over the existing app by wireless ADB on OnePlus 15.
  MainActivity launched; foreground VPN restarted automatically. Hysteria returned
  HTTP 204 through the authenticated tunnel at 00:28:20 and repeatedly afterwards
  while the screen was locked. No profile data was cleared or version incremented.
- Windows installer installed successfully. Installed binary differs from the
  build output only in the three-byte Tauri bundle marker (`NSS` versus `UNK`),
  and contains the new periodic trigger. The service and core remained running
  after the user reconnected following installation.
- Both production artifacts copied to `D:\Sync\VetomenAPK`.

## Transfer measurements and limits

Separate local proxy processes used the saved Hysteria2 and SNKT VLESS profiles,
bound to the physical Ethernet interface without changing the active TUN.
Three 8 MB HTTPS downloads and three 2 MB uploads per profile to Cloudflare:

| Profile | Download, Mbit/s | Upload, Mbit/s |
| --- | --- | --- |
| Hysteria2 | 107.1 / 137.9 / 122.6 | 48.4 / 66.4 / 7.2 |
| SNKT VLESS | 102.7 / 81.9 / 108.5 | 19.5 / 18.4 / 18.4 |

All requests succeeded. These are short HTTPS samples, not Telegram media
throughput measurements or proof that intermittent stalls are resolved.
Telegram's local log recorded eight FILE_REFERENCE_EXPIRED RPC errors between
00:29:31 and 00:29:35. These require client-side reference refresh according to
https://core.telegram.org/api/file-references; the log does not identify the
user's slow transfer or prove those errors caused it.

Wi-Fi-to-cellular handoff and mobile Hysteria reachability still need the requested
manual network switch. Automatic switching to a different saved profile has not
been enabled; that behavior was asked about separately. No live outage was
injected into the user's Windows session after installation.
