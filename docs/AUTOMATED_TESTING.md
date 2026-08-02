# Warpy automated testing

Warpy uses three test layers. No real VPN profile, server credential, or signing
secret belongs in the repository.

## JVM and CI checks

Run the deterministic checks before every commit:

```powershell
.\gradlew.bat :app:checkMojibakeText :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

The same command runs in `.github/workflows/verify.yml` for pushes and pull
requests. Release tags additionally run the signed production workflow.

## UI Automator smoke tests

The tests in `app/src/androidTest` verify navigation, Activity backgrounding,
and foreground-service continuity. They never import or store a profile. The
connected-service test uses the profile already configured on the device and is
skipped when Warpy is disconnected.

Load the production signing variables and build both APKs:

```powershell
.\gradlew.bat `
  -PwarpyTestBuildType=deviceTest `
  :app:assembleDeviceTest `
  :app:assembleDeviceTestAndroidTest `
  --no-daemon `
  --no-parallel

.\tools\adb\Run-WarpyUiTests.ps1
```

The `deviceTest` build uses production code, package name, and signing key, but
keeps R8 disabled so AndroidJUnitRunner dependencies remain available. The
signature lets the script update the installed app without deleting its
encrypted settings. The script removes the instrumentation package and restores
the production APK in `finally`, including after a failed test. Do not run any
`connected*AndroidTest` Gradle task on a personal device: Gradle uninstalls the
target package during cleanup. Those tasks are suitable only for a disposable
emulator.

## Device lifecycle and Doze

Run a short smoke test over USB or wireless ADB:

```powershell
.\tools\adb\Test-WarpyDevice.ps1 -Cycles 3 -ForceDoze
```

Start the script while the phone is unlocked so it can exercise the Warpy UI.
After a lock cycle, vendor keyguards may remain visible; the script verifies the
foreground service and validated Android VPN network directly instead of trying
to bypass a secure lock screen.

For an overnight locked/Doze run:

```powershell
.\tools\adb\Test-WarpyDevice.ps1 `
  -Cycles 1 `
  -LockSeconds 28800 `
  -ForceDoze `
  -DozeSeconds 28800
```

Reports, screenshots, service dumps, connectivity dumps, and logcat are written
under `build/device-tests/`, which is ignored by Git.

## Network handoff

Wi-Fi disablement disconnects wireless ADB, so this test intentionally refuses
to run without a USB serial:

```powershell
.\tools\adb\Test-WarpyNetworkHandoff.ps1 -Serial <usb-serial>
```

The script verifies Wi-Fi to cellular, cellular to Wi-Fi, and airplane-mode
recovery. It restores Wi-Fi and airplane mode in `finally` even after failure.

## Release acceptance

A release candidate is acceptable only when:

- JVM, lint, build, and UI Automator tests pass.
- The short device suite passes for 100 cycles.
- USB network handoff passes.
- One overnight locked/Doze run passes.
- `logcat.txt` has no crash, ANR, or local port collision.
