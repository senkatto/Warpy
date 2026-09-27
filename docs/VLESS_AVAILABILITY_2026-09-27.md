# SNKT VLESS availability check — 2026-09-27

Follow-up to the reported Android VLESS failure. Checks performed around
15:39–15:47 Europe/Moscow. The supplied screenshot says `НЕ ПРОВЕРЕН`
(not checked), which does not indicate a failed availability check.

## Findings

- Server `x-ui` was active. Public TCP port 2053 is redirected by the existing
  PREROUTING rule to Xray on port 8443. The localhost-only panel on port 2053
  does not conflict with this incoming public route.
- All 88 existing server-side protocol checks passed. These use local inbound
  connections and alone do not prove public accessibility.
- TCP sockets explicitly bound to the PC's physical Ethernet interface reached
  `89.125.86.185:2053` in 27 ms, port 8443 in 41 ms, and port 443 in 33 ms.
- An isolated authenticated loopback proxy using the saved SNKT VLESS settings
  and physical Ethernet interface passed 12 of 12 HTTPS checks. For both public
  ports 2053 and 8443, three gstatic requests and three Cloudflare requests each
  returned HTTP 204. Durations were 237–414 ms. Its temporary processes and
  credential-bearing configuration were removed after testing.
- Android's profile list reported SNKT available at 219 ms. Selecting SNKT
  connected successfully. At 15:45:58 the tunnel validation returned HTTP 204
  on its first attempt; the main screen showed 28 ms physical-server RTT.
- The profile list measures an HTTPS request through the proxy. Its duration
  includes the destination and, for Google, the Hurricane Electric route. This
  differs from the physical-server RTT on the connected screen.
- Windows currently skips profile-list probing while connected or connecting
  (`refreshProfileProbes` in `desktop-tauri/src/index.js`). An uncached profile
  can therefore remain labelled not checked during an active VPN connection.

An ordinary unbound PC TCP test initially timed out. That result is not a valid
direct-path measurement while the VPN is active; the explicit physical-interface
test and complete external VLESS requests above succeeded.

## Outcome and limits

The reported earlier Android failure was not reproduced. Its cause cannot be
established from the not-checked screenshot. No application or server settings
were changed during this diagnostic follow-up. Google/HE routing and the PC VPN
remained in place. The phone was left connected to the existing SNKT profile.

This is a point-in-time Wi-Fi check, not a guarantee against intermittent failures
or proof of cellular-network availability. Production artifacts and versions
remain those recorded in `VPN_CANCELLATION_AUDIT_2026-09-27.md`.
