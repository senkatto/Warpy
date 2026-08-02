# Warpy Core Architecture

## Configuration contract

- `shared/core-contract.json` is the source of truth for cross-platform routing, DNS, tags,
  supported protocols and transport defaults.
- Run `npm run contract:generate --prefix desktop-tauri` after changing the contract.
- Do not edit generated files directly:
  - `desktop-tauri/src/generated/core-contract.js`
  - `app/src/main/java/com/warpy/app/vpn/generated/CoreContract.kt`
- `vpn-config.js` and `SingBoxConfigBuilder.kt` are platform adapters. Platform-only TUN and
  local DNS details may differ, but shared policy must come from the contract.
- Explicit profile values always win over defaults. In particular, VLESS and Hysteria2 `sni`
  must reach sing-box as `tls.server_name` unchanged. Hysteria server certificates are managed
  by the server deployment and are not replaced by the clients.

## Connection state ownership

### Android

- `VpnCommandCoordinator` is the only UI-side owner of start, stop and debounced restart commands.
- `WarpyService` and its session reducer own the actual VPN lifecycle.
- `MainActivity` requests commands and renders state. It must not predict connected/stopped state.
- `MainViewModel.applyService*` methods are the only UI state reducers for service status events.

### Windows

- The native VPN service owns the actual lifecycle and exposes `status` plus `desiredRunning`.
- The renderer may expose `commandPending` and `commandError`, but it must not invent a final VPN
  status.
- `applyServiceConnectionSnapshot` is the only assignment point for canonical renderer status.

## Recovery

- Recovery requests from process failure and connectivity changes use the same native path.
- `vpn_recovery.rs` owns attempt limits, backoff and trigger coalescing.
- Repeated network events must not reset an already pending recovery cycle.
- Android recovery effects are serialized by the session runtime; UI code must not start a second
  recovery loop.

## Regression checks

- `npm test --prefix desktop-tauri` checks the generated contract, configuration parity and state
  ownership.
- `cargo test --manifest-path desktop-tauri/src-tauri/Cargo.toml` checks native lifecycle and
  recovery behavior.
- Android production tests cover parser and sing-box output, including explicit VLESS/Hysteria2
  SNI preservation.
