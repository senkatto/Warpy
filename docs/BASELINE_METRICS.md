# Warpy Baseline Performance Metrics

These metrics represent the performance characteristics of the Warpy Android VPN client at **Stage 0** (before implementing the unified state machine and optimization roadmaps).

## Device Context
- **Test Device**: OnePlus (CPH2747)
- **OS**: Android 16 (BP2A.250605.015)
- **Architecture**: arm64-v8a

## Baseline Metrics

### 1. Build and Binary Size
- **Release APK Size**: `19,366,609` bytes (~`18.47` MB)
- **libbox.so size (native code)**: `52,519,032` bytes (~`50.08` MB)
- **Proguard/R8**: Enabled for release builds

### 2. Startup Metrics
*Note: Measured via ADB `am start -W` when the device is connected.*
- **Cold Start (MainActivity Launch)**: ~450ms
- **Warm Start**: ~180ms

### 3. Memory Profile (Typical VPN Session)
- **Idle (No Connection)**: ~32 MB
- **Connected (Active VPN with Hysteria2)**: ~48 MB - 60 MB (depending on data throughput and routing rules)
- **Command Server Overhead**: ~4 MB - 6 MB

### 4. Connection Handshake SLA
- **Hysteria 2 UDP Connection Time**: ~1.2s - 2.5s (depending on network latency)
- **VLESS Reality TCP/TLS Connection Time**: ~0.8s - 1.5s
- **Trojan TCP/TLS Connection Time**: ~0.9s - 1.8s

## Background battery audit — 2026-10-02

OnePlus CPH2747, production build `1.0.7` / `24`, connected Hysteria2 profile.
CPU time was sampled from `/proc/<pid>/stat` and the main thread's corresponding
stat file, using the device's `CLK_TCK=100`.

| Measurement | Before | After |
| --- | ---: | ---: |
| Observation duration | 60.66 s | 302.56 s |
| Whole-process CPU time | 3.25 s | 6.32 s |
| Main-thread CPU time | 0.89 s | 0.01 s |
| Mean CPU use, relative to one core | 5.36% | 2.09% |

These are observations, not a controlled battery-discharge comparison. The initial
snapshot had five retained MainActivity instances and the phone was charging with
its screen off. APK replacement restarted the process; during the later sample
the phone was unplugged and its screen was turned on. Traffic was not controlled.
Do not extrapolate these values into battery savings or battery life.

The fix removes the recurring UI statistics timer, server latency measurements,
and automatic update checks while their Activity is stopped. The native statistics
subscription is also disconnected while the app is in the background. Opening
the app restores its one-second counters without restarting the tunnel.

With a healthy tunnel and a noninteractive screen, the watchdog interval is five
minutes instead of thirty seconds. An initial failed check switches back to thirty
seconds for confirmation and bounded recovery. Physical-network callbacks and
wake validation remain active, so the longer interval does not delay those events.

Verification:

- Production-signed APK installed over the existing app without clearing settings;
  cold launch completed in 624 ms, and the saved VPN session restored successfully.
- All 201 production unit tests passed, including screen-off watchdog timing and
  prompt retry after a failed probe. `lintProduction` passed with no errors and 35
  warnings; the source encoding check passed.
- Three foreground/background round trips kept the same process and connected
  foreground service. Uptime advanced from `08:01` to `08:19`; each trip produced
  one statistics-resumed event and one statistics-paused event. Wake validation
  returned HTTP 204. No crash was observed in the captured verification logs.
- The production APK was copied to `D:\Sync\VetomenAPK\Warpy.apk`, retaining the
  existing production certificate and public version. SHA-256:
  `7BBCCD45BC9F33793C9AF5A3FA37A82E5E4F967032E3BB63159AAD59AB399E04`.
