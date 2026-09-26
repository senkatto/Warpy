# Android stability and connection cancellation — 27 September 2026

This follow-up addresses intermittent Android connections and the inability to
cancel a pending connection on Android and Windows. Public versions remain 1.0.7.

## Confirmed defects and fixes

- Android DNS receive buffers truncated responses at 512 bytes. A local UDP
  regression test reproduces loss of a 4096-byte response. The complete response
  is now retained. DNS sockets accept packets from their selected endpoint and
  bind to the selected physical Android network. The hard-coded home-router
  fallback was removed. See [EDNS payload sizes](https://www.rfc-editor.org/rfc/rfc6891#section-6.2.3)
  and [Android Network.bindSocket](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.DatagramSocket)).
- Cancellation closes an outstanding raw DNS socket and prevents fallback to
  further endpoints. Android resolver cancellation also releases its waiting
  latch, rather than waiting out a DNS timeout.
- Closing the Android session controller could leave a queued command awaiting
  a result forever. Pending results now share the controller's lifetime and its
  channel closes when the actor ends. A regression test reproduces the old hang.
- An exception during recovery previously entered a waiting state without any
  event scheduled to resume it. Recovery exceptions now use the existing restart
  or terminal-error path. Validation I/O errors enter recovery rather than
  turning off the desired VPN state. Both failures were reproduced in tests.
- Android runtime creation is synchronized. Start/stop commands enter the event
  queue in arrival order, and stop cancels a pending session even before its
  native resources exist. The UI tracks a pending start immediately, allowing a
  second tap to cancel before the service's first status broadcast.
- Network callback updates and monitor shutdown now share a lock. Late callbacks
  from a stopped monitor are ignored, preventing concurrent candidate-map access
  and stale notifications. Registration failure is surfaced after cleanup.
- An old probe failure no longer replaces an unrelated startup exception with a
  misleading Hysteria password/SNI error.
- Windows used the last service status to interpret a click even while displaying
  a pending start. A second click could enqueue another start. Cancellation now
  recognizes the pending command, invalidates queued starts, and suppresses
  repeated clicks while stopping. A tray toggle can cancel an in-flight command.
  Three tests exercise the actual renderer control functions and failed before
  these changes.
- Windows now owns the core process during startup validation. Cancellation can
  terminate it without waiting for the lifecycle lock or blocked probe I/O.
  Startup/recovery failures clean up the owned process, and observation does not
  race lifecycle operations. A native test holds the lifecycle lock and verifies
  that cancellation still terminates an owned test process.

## Verification

- Android: 195 JVM tests passed; production lint: 0 errors, 34 existing warnings.
  Production APK assembled, signature verified, copied to
  `D:\Sync\VetomenAPK\Warpy.apk`, installed on the OnePlus 15 and launched.
- Device: five baseline Hysteria2 starts produced successful HTTP 204 tunnel
  probes. VLESS also connected successfully. A temporary Hysteria profile using
  reserved, unreachable address `192.0.2.1:2443` exercised cancellation during
  startup and during a pending tunnel probe. The first service check after a
  500 ms wait following cancellation found no service; a later check also found
  none. Three additional start/stop pairs, 300 ms apart, all left no service after
  a one-second wait. The temporary profile and QR file were removed.
- The final phone state is connected using the original Hysteria2 profile, with
  a successful tunnel probe and 28 ms physical-server RTT. One observed gstatic
  probe timed out during the audit; the Cloudflare fallback succeeded without a
  tunnel reset.
- Windows: 112 JavaScript tests passed; 65 Rust tests passed, 1 ignored. The release
  installer was copied to `D:\Sync\VetomenAPK\Warpy-setup.exe` and installed.
  Installed executable matches the build except for Tauri's expected three-byte
  NSIS bundle marker (`UNK` to `NSS`). The installed service and tunnel are running;
  a final HTTPS control request returned 204 in 0.231 seconds.
- Google/Gemini routing through Hurricane Electric was preserved. No additional
  server configuration changes were made in this follow-up.

APK SHA-256: `43475CCAF2150380B3A041B803AACAA2C04B818DA0ABA8D6D783452397B1B3C2`

Installer SHA-256: `D6F937C90321066C202C25D02820B58BD25441D196946F583766E522CE10E8D4`

Detailed logs are local under `build/vpn-diagnostics/` and are not committed.

## Limits

This is not proof that every Android defect is eliminated. Spontaneous connection
failure was not reproduced on the current Wi-Fi during the baseline starts.
Overnight operation and Wi-Fi/cellular handoff remain unverified; only wireless
ADB was available. Windows cancellation was verified in renderer and native
tests, not by interrupting the final live desktop tunnel, which is required for
the assistant's connectivity. Profile-list latency is an HTTPS round trip through
the proxy and Google/HE route; it is not the physical-server RTT shown while
connected.
