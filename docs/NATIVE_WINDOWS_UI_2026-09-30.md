# Native Windows shell — local build, 2026-09-30

Warpy 1.0.7 has an optional Windows shell drawn with Win32, Direct2D and
DirectWrite. The native feature creates no WebView windows and its installer
does not require installing WebView2. Tauri remains responsible for the tray,
notifications, updater and application resources. The existing Windows VPN
service, authenticated IPC, sing-box configuration and profile controller are
retained.

QuickJS runs the existing JavaScript controller without a browser. A small
retained element tree supplies controller values and events; explicit drawing
coordinates reproduce the existing 420 × 720 interface. Original SVG icons,
flags, particle animation and QR generator are reused. Closing or minimizing
the window releases its drawing resources, pauses visual/metric timers and
reduces status polling to once every ten seconds. VPN work continues in the
existing service.

## Build

From `desktop-tauri`, use PowerShell 7:

```powershell
npm ci
npm run build:native
```

The release script checks the dependency lock, regenerates the controller
bundle, runs JavaScript tests, and builds the updater-signed NSIS installer
using `tauri.native.conf.json` and `tauri.updater.conf.json`. Signing uses the
existing local protected updater key. No public version was changed and no
release was published. The legacy shell remains buildable without the
`native-ui` feature.

Installer output:
`desktop-tauri/src-tauri/target/release/bundle/nsis/Warpy_1.0.7_x64-setup.exe`.
The final installer is also copied to `D:\Sync\VetomenAPK\Warpy-setup.exe`.
The previous installer was preserved locally at
`.artifacts/native-ui-preview/Warpy-setup-previous.exe`.

## Verification and limits

- JavaScript: 122 tests pass, including native initialization, original dialog
  actions, profile sharing/QR, settings changes, keyboard editing, received
  network byte counts, connection cancellation, running-app selection, modal
  hit blocking and hidden timers.
- Rust native feature: 69 tests pass, 1 ignored. Tests exercise the actual
  QuickJS engine and the Direct2D renderer, alongside existing service/IPC and
  connection cancellation checks. A separate process test checks TLS client
  initialization without updater startup. The legacy `cargo check` also passes.
- Main, profile, settings, tunneling, import and share screens were rendered
  to local PNGs. Native positions were compared with the original HTML/CSS in
  a headless browser with mocked backend and documentation-only profile data.
  Rendering uses Windows text rasterization; blur/shadow details and animated
  transitions are not pixel-identical to Chromium.
- Preview mode (`--native-preview`) does not attach to the VPN service, uses
  a simulated connection state, reads protected settings without saving them,
  and blocks connection/settings/update mutations. Use
  `--autostart --native-preview` to start this isolated preview hidden for
  resource measurements (the entry point examines the first launch option).
- The new installer was not installed on the active PC. Actual connection,
  cancellation during a stalled start, reconnect, speed measurement, routing,
  boot launch and update installation must be exercised after the user installs
  the build. Automated checks and preview do not establish live VPN behavior.
- The custom native renderer does not currently expose individual controls
  through Windows UI Automation. Keyboard input is supported; screen-reader
  parity needs a separate implementation.

The production UI, service and sing-box processes were kept running throughout
this work. No server configuration or network route was changed for this task.

## Final artifact and resource sample

The final EXE remained running during a hidden preview after a 30-second warmup.
Its working set was 33.61 MiB, private memory 10.55 MiB, and processor time was
0.141 seconds over a ten-second sample. It spawned no child processes, including
WebView2 processes. These figures describe the isolated UI preview; they exclude
the VPN service and sing-box. The protected settings file hash stayed unchanged.

The copied installer is 20.96 MiB. Its SHA-256, identical to the build output, is:

```text
750A6B1DEFE9ACC8687293730B49DB065CAA72FBBE8A5DEC4BB358519B87E545
```

The corresponding updater signature is present beside the NSIS build output.
