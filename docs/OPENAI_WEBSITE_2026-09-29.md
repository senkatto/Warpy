# OpenAI website connection closure — 29 September 2026

The user's Chrome tab for
`https://openai.com/ru-RU/index/introducing-gpt-6-1-sol/` displayed
`ERR_CONNECTION_CLOSED`. A Windows curl request also failed during the TLS
handshake, before receiving an HTTP response.

At the time of the check, `openai.com` had IPv4 DNS records and no AAAA record.
Xray's broad OpenAI domain rule selected `google-ai-ipv6`, bound to the Hurricane
Electric IPv6 source address. Direct server IPv4 reached an HTTP response; the
IPv6-only request could not resolve a usable address. The root website therefore
needed an exception from that IPv6 route.

Added rule `warpy-openai-web-ipv4` for the exact TCP hosts `openai.com` and
`www.openai.com`, using the existing IPv4 `direct` outbound. The www alias currently
has AAAA records but shares the website's IPv4 path under this exception. Saved
the rule in the x-ui template and generated config and applied it through the
running RoutingService. No service restart was necessary.

Verification:

- Xray accepted the candidate configuration before application.
- Runtime API contains the rule; persisted and generated routing match.
- Removing this one rule from the resulting config reproduces the entire prior
  configuration exactly. ChatGPT, API and Gemini routes remain unchanged.
- Reloading the existing Chrome tab displayed the requested article, its heading
  and body instead of the connection error. Curl now receives HTTP 403 from the
  website rather than a TLS failure; browser rendering is the success criterion.
- The OpenAI API still returned its expected unauthenticated HTTP 401 from the PC.
- Windows VPN remained running, and x-ui's active-since timestamp did not change.
- Hysteria already sends these hosts through its final `direct(all)` rule and
  required no change or restart. No client build or APK update was needed.

Protected backup: `/root/warpy-openai-web-20260929T185808Z`.
Script source is committed in the local operations repository at
`.backups/vps-vpn-repair-2026-09-05/fix-openai-web-ipv4-20260929.py`.

Rollback:

```sh
python3 /root/warpy-fix-openai-web-ipv4-20260929.py rollback /root/warpy-openai-web-20260929T185808Z
```
