# GitHub release procedure

Android and Windows share one public version, one `v<version>` tag, and one GitHub Release.
Routine local changes do not increment the version and are not published. Create a release only
after both clients have been tested and the user has explicitly approved publication.

## Local production key

Keep the production keystore outside the project, for example at:

```text
<private-path>\warpy-release.jks
```

Keep the local passwords and alias in a private file outside the repository.
They must never be committed to GitHub, sent in chat, or placed in `gradle.properties` inside the
repository.

The certificate fingerprint is:

```text
E7:CD:2B:CF:81:AB:CD:9E:94:45:2F:F9:70:89:54:28:AA:A5:09:6C:05:A4:9B:FE:F5:60:E8:19:06:98:18:5A
```

## GitHub repository secrets

Create these Actions secrets in the repository settings:

```text
WARPY_KEYSTORE_BASE64
RELEASE_STORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
TAURI_SIGNING_PRIVATE_KEY
TAURI_SIGNING_PRIVATE_KEY_PASSWORD
```

To copy the keystore as Base64 on Windows:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes('<private-path>\warpy-release.jks')
) | Set-Clipboard
```

Paste the clipboard value into `WARPY_KEYSTORE_BASE64`. The other three values are in the local
secret file. Do not print them in a build log.

## Publishing a release

1. Update Android `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Set the same public version in `desktop-tauri/package.json`,
   `desktop-tauri/src-tauri/Cargo.toml`, and `desktop-tauri/src-tauri/tauri.conf.json`.
3. Build and test both clients locally. Do not publish yet.
4. After explicit approval, commit and push the reviewed source.
5. Create one tag equal to the shared version, for example `v1.0.1`, and push it:

```powershell
git tag v1.0.1
git push origin v1.0.1
```

The workflow rejects the tag unless all Android and Windows version files match it. It then builds
and verifies both clients before publishing one release containing `Warpy-Android.apk`,
`Warpy-Windows.exe`, and the signed Windows updater metadata. If either build fails, the release is
not published.

## Local production build

For a local check, load the values from the private secret file into the current PowerShell
session, then run:

```powershell
.\gradlew.bat :app:assembleProduction --no-daemon
```

The output is `app/build/outputs/apk/production/app-production.apk`.
