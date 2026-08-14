# Android VPN core

- File: `hiddify-core.aar`
- Distribution: locally reproducible Hiddify Core Android build
- Release: `v4.1.0`
- Source: `https://github.com/hiddify/hiddify-core/tree/c9d6f0f00b2eda34e4fb71863e4e0a62b3e931a0`
- Hiddify sing-box revision: `0a02b7729f6a211436bb8bdcd8696c283eb27767`
- Go: `1.25.6`
- gomobile/gobind: `v0.1.11`
- Android NDK: `28.2.13676358`
- Target: `android/arm64`, API 23
- AAR SHA-256: `85033049DBED46BB5528A4A258BABA861FCDB51CD4724427A82DDF7922D9ED0C`

The build uses the upstream `with_naive_outbound` feature tag. API 23 is
required by the Naive-enabled Android variant; the upstream API 21 compatibility
variant intentionally excludes Naive. Gradle verifies the checked-in AAR before
every application build and rejects a checksum mismatch.
