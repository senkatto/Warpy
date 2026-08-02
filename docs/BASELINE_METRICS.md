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
