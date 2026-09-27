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
- At 15:46:33, a background check recorded read timeouts for both HTTPS targets,
  followed by a successful HTTP fallback (logged as code 200 by the validator).
  The next check at 15:47:04 returned HTTPS 204 on its first attempt. The tunnel
  did not restart in response to these timeouts. Their underlying cause was not
  established, and the successful earlier checks do not rule out intermittent
  request failures. Before this fix, profile-list checks used only gstatic, so a failed
  single-destination test is not proof that the VLESS server itself is down.
- The profile list measures an HTTPS request through the proxy. Its duration
  includes the destination and, for Google, the Hurricane Electric route. This
  differs from the physical-server RTT on the connected screen.
- Windows currently skips profile-list probing while connected or connecting
  (`refreshProfileProbes` in `desktop-tauri/src/index.js`). An uncached profile
  can therefore remain labelled not checked during an active VPN connection.

An ordinary unbound PC TCP test initially timed out. That result is not a valid
direct-path measurement while the VPN is active; the explicit physical-interface
test and complete external VLESS requests above succeeded.

## Android correction

Profile-list checking now tries Cloudflare through the same profile if its Google
request fails or returns no positive delay. A successful primary check does not
send the fallback request. Both destinations must fail before publishing an
unavailable result. The batch watchdog budget now allows both bounded requests;
coroutine cancellation is propagated rather than published as a failed profile.

This addresses the confirmed dependency on a single control destination. It does
not establish that this caused the user's earlier connection failure, and it does
not change the server's Google/HE routing.

Validation: all 199 Android JVM tests passed, including four new controller HTTP
regressions covering primary success, fallback success, both destinations failing,
and a zero-delay response. Text checks passed; lint reported 0 errors and the same
34 warnings as the previous build. The production-signed APK was copied to
`D:\Sync\VetomenAPK\Warpy.apk`, installed with ADB, and launched successfully.
At 15:52:33 the reconnected SNKT session returned HTTP 204 on its first validation
attempt. The connected screen showed 32 ms, and the new profile-list check showed
SNKT at 223 ms (Hysteria2 at 213 ms and Naive at 404 ms).

APK SHA-256: `F43D99C94F7E2D321B5D4E4310C39E01A1C696D02B76B87165125961809948AD`.

## Outcome and limits

The reported earlier Android failure was not reproduced. Its cause cannot be
established from the not-checked screenshot. No server settings were changed.
Google/HE routing and the PC VPN remained in place throughout this follow-up.
The phone was left connected to the existing SNKT profile after installation.

This is a point-in-time Wi-Fi check, not a guarantee against intermittent failures
or proof of cellular-network availability. The Windows artifact remains the one
recorded in `VPN_CANCELLATION_AUDIT_2026-09-27.md`; the Android public version
remains 1.0.7.
