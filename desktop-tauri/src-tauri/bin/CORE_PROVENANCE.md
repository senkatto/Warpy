# Bundled VPN core

- Binary: `sing-box-x86_64-pc-windows-msvc.exe`
- Distribution: Hiddify sing-box fork
- Revision: `d75557751415634cff6a8b101360d219404a60af`
- Build environment: `go1.25.6 windows/amd64`, CGO disabled
- Size: `65593344` bytes
- SHA-256: `47FE53E73E99F219DE4495731E348EAE5FF0CFB831E31157B70B95A4BEF0D5B3`

The binary is pinned because its embedded version string is `unknown`. Update the hash in
`src/main.rs` and this file only when intentionally replacing and retesting the core.

## Cronet runtime for NaiveProxy

- Binary: `libcronet.dll`
- Distribution: Hiddify Core `v4.1.0`, Windows amd64 archive
- Source: `https://github.com/hiddify/hiddify-core/releases/download/v4.1.0/hiddify-lib-windows-amd64.tar.gz`
- Size: `8596992` bytes
- SHA-256: `8EF1F8BBDE77F954AF1AE47BEE1819AC8DC2354BB0E1D4BABA3DAD9E58D7A6F7`
