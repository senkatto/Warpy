# VPN stability verification — 27 September 2026

## Android

- Server latency now measures three TCP handshakes on the physical Android
  network, outside the VPN. DNS lookup and HTTP/TLS setup are excluded. UDP
  profiles use TCP/443 on the same server for this metric; profile availability
  remains a separate HTTPS test through the selected VPN protocol. Failed
  measurements display a dash; genuine high latency is not capped.
- Measurements run every ten seconds, use the runtime profile, and discard
  results after a session/profile change.
- DNS or metering changes on the same Android network/interface no longer
  reset every tunnel connection. Actual network changes still trigger recovery.
- A generic SOCKS error no longer implies invalid Hysteria credentials.
- The background validator checks its independent fallback even when configured
  for one attempt, before declaring the tunnel unavailable.

The production-signed APK was installed on the OnePlus 15 over Wi-Fi ADB and
launched successfully. The physical-network ICMP check measured 27–38 ms;
the installed app showed 29 ms. Unbound ICMP was answered by the VPN stack in
under 2 ms, demonstrating why measuring inside the TUN gives misleading values.
The foreground VPN service remained connected during subsequent checks.

189 JVM tests passed. Production lint reported zero errors and 34 warnings.
The production certificate SHA-256 was verified against the documented key.

## Windows

Google/Flow UDP/443 is rejected locally with an immediate ICMP error, matching
the server's TCP-only policy for the HE IPv6 route. Other domains retain the
user's QUIC setting. This avoids waiting for the server to discard Google QUIC.

109 JavaScript/integration tests passed. The release installer was installed
and the saved connection restored. A UDP packet to the Google fake-IP address
returned ConnectionReset in 19 ms. Subsequent HTTPS checks returned HTTP 204
in 232 ms (Google) and 140 ms (Cloudflare).

## Server

- Added exact TCP exceptions for `android.clients.google.com` and
  `alt6-mtalk.google.com` in both Xray and Hysteria. They had IPv4 records but no
  IPv6 records and previously matched strict IPv6 routing. The first endpoint
  failed before the change and returned its expected HTTP 404 afterwards.
- Hysteria DNS now uses the local systemd-resolved cache over TCP at
  `127.0.0.53:53`, timeout 4 seconds. The existing local resolver has Google and
  Cloudflare upstreams. This followed a reproduced transient missing-AAAA error
  for `labs.google` with the previous single UDP resolver.
- Google/Gemini/Flow HE IPv6 outbounds, credentials and other services were
  preserved. Caddy's running configuration matched its saved configuration.
- Xray configuration validation and an isolated Hysteria startup passed before
  applying changes. Xray's generated routing matched the persistent template.
- All 88 network checks passed across VLESS TCP, Trojan, both XHTTP entries,
  Hysteria2 and NaiveProxy, including Gemini/Flow, the repaired Android endpoint,
  OpenAI/Spotify reachability, and private-address rejection. The old Flow URL
  now redirects to `https://flow.google.com/`; the verifier follows this redirect.

Protected backups remain on the VPS:

- `/root/warpy-stability-20260926T215748Z` — before the exact-domain exceptions.
- `/root/warpy-stability-20260926T221010Z` — before the DNS change.

Operational scripts are kept in the local operations repository at
`.backups/vps-vpn-repair-2026-09-05/`. No credentials or full server configs are
included here. No public release or version increment was performed.

Both installed artifacts were copied to `D:\Sync\VetomenAPK`.

## Limits of verification

This was a short live Wi-Fi check, not an overnight stability run. Cellular/Wi-Fi
handoff was not forced because doing so would disconnect wireless ADB. The
fixes address reproduced routing/measurement problems and concrete recovery
bugs; they do not establish that every intermittent carrier or power-management
failure is eliminated.
