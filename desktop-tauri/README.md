# Warpy Desktop

Minimal Windows x64 client for VLESS, Trojan, and Hysteria2 profiles. The desktop app uses Tauri 2 for the UI and a bundled sing-box core for the tunnel.

## Requirements

- Windows 10 or newer (x64)
- Node.js 20 or newer
- Rust stable with the MSVC toolchain
- WebView2 Runtime

Wintun requires elevation when the application starts.

## Development

```powershell
npm ci
npm test
npm run dev
```

## Release build

```powershell
npm ci
npm test
cargo fmt --manifest-path src-tauri/Cargo.toml -- --check
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml --all-targets
./scripts/verify-core-provenance.ps1
npm run build:updater
```

The signed updater installer and its `.sig` file are created in
`src-tauri/target/release/bundle/nsis`. The local build reads the updater key from
`../warpy-keys`; this directory is ignored by Git and must be backed up separately.

Android and Windows use the same public version and the same `v<version>` tag. The unified
GitHub Actions workflow verifies both clients, builds the production APK and signed Windows
updater, and publishes both installers in one release. A tag is created only after the
accumulated local changes have been tested and explicitly approved for release.

The Tauri updater signature protects update integrity and is free. It does not replace an
optional Authenticode certificate, which is the mechanism Windows uses to establish publisher
reputation for a freshly downloaded installer.

## Security notes

- VPN profiles are encrypted for the current Windows user with DPAPI.
- A validated encrypted backup is restored automatically if the primary settings file is damaged.
- The bundled sing-box executable is verified with SHA-256 before first use in each app process.
- The temporary runtime configuration is removed after sing-box starts.
- Warpy manages only the sing-box process that it started.
- The renderer capability allowlist exposes only the window, event, and notification commands used by the UI.

Core provenance is documented in `src-tauri/bin/CORE_PROVENANCE.md`.
