# Bundled VPN core

- Binary: `sing-box-x86_64-pc-windows-msvc.exe`
- Distribution: Hiddify sing-box fork
- Revision: `d75557751415634cff6a8b101360d219404a60af`
- Build environment: `go1.25.6 windows/amd64`, CGO disabled
- Size: `65593344` bytes
- SHA-256: `47FE53E73E99F219DE4495731E348EAE5FF0CFB831E31157B70B95A4BEF0D5B3`

The binary is pinned because its embedded version string is `unknown`. Update the hash in
`src/main.rs` and this file only when intentionally replacing and retesting the core.
