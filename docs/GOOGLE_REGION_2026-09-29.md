# Gemini and Antigravity regional block — 29 September 2026

## Evidence and correction

The authenticated desktop Gemini page displayed the unsupported-country notice.
Server access logs confirmed that Gemini and Google AI backends used
`google-ai-ipv6`, bound to Hurricane Electric address `2001:470:1f14:4ec::2`.
The tunnel itself was reachable. Cloudflare identified this address as NL, while
an anonymous Google home-page request through it returned Russian localization
and a Google.ru link. These providers disagreed about the effective region.

A temporary test using another address on the same HE tunnel did not remove
Gemini's visible country block. After routing only Gemini over the VPS's existing
IPv4 exit, the same signed-in browser opened `/app`, showed the chat input and
history, and explicitly displayed **Estonia — based on IP address**. No account
or cookie changes were needed. The exact cause of Google's changed treatment of
the HE address was not established; the successful route comparison is stronger
evidence than the HTTP 200 checks used by earlier audits.

Added a TCP exception for 28 Gemini/Antigravity domains, including their chat,
mobile, authentication, entitlement and Cloud Code backends. They now use the
existing `direct` IPv4 outbound in Xray and Hysteria. The exact domain list lives
in the operations script. Existing HE outbounds, Flow rules, unrelated services,
credentials, private-address rejection and UDP/443 restrictions remain intact.
This is a specific routing exception, not an automatic exit failover mechanism.

## Deployment and verification

- Saved the Xray change in the x-ui database template and generated config, and
  applied it to the running core through RoutingService. The x-ui service was
  not restarted; its active-since timestamp remained 26 September 21:59:33 UTC.
- Validated the Xray candidate and an isolated Hysteria candidate before applying.
  Hysteria's saved ACL was updated and its container restarted successfully.
- VLESS, Trojan, both XHTTP entries and Naive share the Xray rule. Hysteria has
  the matching domain list. Server access logs confirmed `direct` for Gemini
  and Cloud Code through the Xray-backed protocols.
- All 88 existing protocol, reachability and private-address checks passed.
  Temporary test clients were stopped. These checks are transport checks; they
  do not by themselves validate signed-in chat access.
- The existing signed-in Chrome session visibly opened Gemini chats. The user
  then confirmed that both Gemini and Antigravity worked after being asked to
  refresh Gemini on the phone and restart Antigravity.
- Removed the temporary HE address and test outbound. The final runtime contains
  the named rule `warpy-google-chats-ipv4`; saved and generated routing match.
- Windows VPN remained running. No client code, APK, installer or public version
  changed. Existing installations receive the fix through the server.

## Recovery

Protected server backup:
`/root/warpy-google-region-20260929T172120Z`.

Operational script on the VPS:
`/root/warpy-fix-google-region-20260929.py`.

Its source is committed in the local operations repository
`.backups/vps-vpn-repair-2026-09-05/`, which has no publication step. Full server
configuration and credentials remain in the protected server backup.

Rollback (restores the earlier HE route and may restore the regional block):

```sh
python3 /root/warpy-fix-google-region-20260929.py rollback /root/warpy-google-region-20260929T172120Z
```
