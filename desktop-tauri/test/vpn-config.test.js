import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import {
  buildRuntimeSingBoxConfig,
  buildSelectableSingBoxConfig,
  buildSingBoxConfig,
  parseProfileLink,
  profileShareLink,
} from '../src/vpn-config.js';

test('serializes structured profiles for every advertised protocol', () => {
  const vmessPayload = Buffer.from(JSON.stringify({
    v: '2', ps: 'VMess', add: 'vmess.example.com', port: '443',
    id: '00000000-0000-4000-8000-000000000123', aid: '0', scy: 'auto', net: 'ws',
    host: 'cdn.example.com', path: '/ws', tls: 'tls', sni: 'cdn.example.com',
  })).toString('base64');
  const fixtures = [
    'vless://00000000-0000-4000-8000-000000000001@vless.example.com:443?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=xhttp&mode=stream-up&path=%2Fvpn#VLESS',
    'trojan://secret@trojan.example.com:443?security=tls&sni=trojan.example.com&type=grpc&serviceName=vpn#Trojan',
    'hysteria2://secret@hy2.example.com:2443?sni=hy2.example.com&obfs=salamander&obfs-password=pepper&up_mbps=50&down_mbps=100#HY2',
    `vmess://${vmessPayload}`,
    'ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388?plugin=v2ray-plugin%3Btls%3Bhost%3Dcdn.example.com#SS',
    'socks5://user:pass@socks.example.com:1080#SOCKS',
    'wg://wg.example.com:51820?pk=private&peer_pk=public&pre_shared_key=shared&local_address=10.0.0.2%2F32&mtu=1380#WG',
    'tuic://00000000-0000-4000-8000-000000000123:secret@tuic.example.com:443?sni=tuic.example.com&congestion_control=bbr#TUIC',
    'hysteria://hy.example.com:443?auth=secret&peer=hy.example.com&upmbps=50&downmbps=100#Hysteria',
    'naive+https://alice:s%40cret@naive.example.com:443?sni=front.example.com&alpn=h2%2Chttp%2F1.1#Naive',
  ];

  for (const fixture of fixtures) {
    const parsed = parseProfileLink(fixture);
    assert.ok(parsed, fixture);
    const structured = { ...parsed, raw: '' };
    const reparsed = parseProfileLink(profileShareLink(structured));
    assert.equal(reparsed?.protocol, parsed.protocol, fixture);
    assert.equal(reparsed?.host, parsed.host, fixture);
    assert.equal(reparsed?.port, parsed.port, fixture);
    assert.equal(reparsed?.uuid, parsed.uuid, fixture);
  }
});

test('parses Hysteria2 obfuscation and preserves credentials', () => {
  const profile = parseProfileLink('hysteria2://secret@example.com:2443/?insecure=1&sni=cdn.example.com&obfs=salamander&obfs-password=pepper#Fast');
  assert.equal(profile.protocol, 'hysteria2');
  assert.equal(profile.uuid, 'secret');
  assert.equal(profile.obfsType, 'salamander');
  assert.equal(profile.obfsPassword, 'pepper');
  const tls = buildSingBoxConfig(profile).outbounds[0].tls;
  assert.equal(tls.server_name, 'cdn.example.com');
  assert.equal(tls.insecure, true);
});

test('preserves colons in opaque Trojan and Hysteria2 passwords', () => {
  const trojan = parseProfileLink('trojan://secret:part@trojan.example.com:443#Trojan');
  const hysteria2 = parseProfileLink('hysteria2://secret:part@hy2.example.com:443#HY2');

  assert.equal(trojan.uuid, 'secret:part');
  assert.equal(buildSingBoxConfig(trojan).outbounds[0].password, 'secret:part');
  assert.equal(hysteria2.uuid, 'secret:part');
  assert.equal(buildSingBoxConfig(hysteria2).outbounds[0].password, 'secret:part');
});

test('auto-detects and builds common profile link protocols', () => {
  const vmessPayload = Buffer.from(JSON.stringify({
    v: '2', ps: 'VMess', add: 'vmess.example.com', port: '443',
    id: '00000000-0000-4000-8000-000000000123', aid: '0', scy: 'auto', net: 'ws',
    host: 'cdn.example.com', path: '/ws', tls: 'tls', sni: 'cdn.example.com',
  })).toString('base64');
  const fixtures = [
    [`vmess://${vmessPayload}`, 'vmess'],
    ['ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388#SS', 'shadowsocks'],
    ['socks5://user:pass@socks.example.com:1080#SOCKS', 'socks'],
    ['wg://wg.example.com:51820?pk=private&peer_pk=public&local_address=10.0.0.2%2F32#WG', 'wireguard'],
    ['tuic://00000000-0000-4000-8000-000000000123:secret@tuic.example.com:443?sni=tuic.example.com#TUIC', 'tuic'],
    ['hysteria://hy.example.com:443?auth=secret&peer=hy.example.com&upmbps=50&downmbps=100#Hysteria', 'hysteria'],
    ['naive+https://alice:s%40cret@naive.example.com:443?sni=front.example.com&alpn=h2%2Chttp%2F1.1#Naive', 'naive'],
  ];

  for (const [link, protocol] of fixtures) {
    const profile = parseProfileLink(link);
    assert.equal(profile?.protocol, protocol);
    const config = buildSingBoxConfig(profile);
    const generatedType = protocol === 'wireguard' ? config.endpoints?.[0]?.type : config.outbounds[0].type;
    assert.equal(generatedType, protocol);
  }
});

test('builds Naive HTTPS and QUIC outbounds without inventing TLS values', () => {
  const httpsProfile = parseProfileLink(
    'https://alice:s%40cret@naive.example.com:443?sni=front.example.com&alpn=h2%2Chttp%2F1.1#Naive',
  );
  const httpsOutbound = buildSingBoxConfig(httpsProfile).outbounds[0];

  assert.equal(httpsProfile.protocol, 'naive');
  assert.equal(httpsOutbound.type, 'naive');
  assert.equal(httpsOutbound.username, 'alice');
  assert.equal(httpsOutbound.password, 's@cret');
  assert.equal(httpsOutbound.quic, false);
  assert.equal(httpsOutbound.tls.server_name, 'front.example.com');
  assert.equal(httpsOutbound.tls.alpn, undefined);
  assert.equal(httpsOutbound.tls.utls, undefined);
  assert.equal(
    buildSingBoxConfig(httpsProfile).route.rules.some(
      rule => rule.network === 'udp' && rule.port === 443 && rule.action === 'reject',
    ),
    true,
  );

  const quicProfile = parseProfileLink('naive+quic://alice:secret@naive.example.com#Naive QUIC');
  assert.equal(buildSingBoxConfig(quicProfile).outbounds[0].quic, true);

  const defaultHttpsProfile = parseProfileLink(
    'naive+https://alice:secret@naive.example.com#Naive HTTPS',
  );
  const defaultHttpsOutbound = buildSingBoxConfig(defaultHttpsProfile).outbounds[0];
  assert.equal(defaultHttpsOutbound.quic, false);
  assert.equal(defaultHttpsOutbound.tls.alpn, undefined);
});

test('preserves explicit VLESS SNI in the generated TLS config', () => {
  const profile = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@origin.example.com:443'
      + '?security=reality&sni=front.example.net&pbk=public-key&sid=0123abcd#VLESS',
  );

  assert.equal(profile.sni, 'front.example.net');
  assert.equal(buildSingBoxConfig(profile).outbounds[0].tls.server_name, 'front.example.net');
});

test('stores IPv6 server addresses without URI brackets', () => {
  const profile = parseProfileLink('vless://00000000-0000-4000-8000-000000000000@[2001:db8::10]:443#IPv6');
  assert.equal(profile.host, '2001:db8::10');
  assert.equal(buildSingBoxConfig(profile).outbounds[0].server, '2001:db8::10');
});

test('builds Trojan Reality without disabling UDP globally', () => {
  const profile = parseProfileLink('trojan://password@203.0.113.40:8444?security=reality&sni=www.apple.com&pbk=public-key&sid=b5d9&type=tcp#Trojan');
  const config = buildSingBoxConfig(profile);
  const outbound = config.outbounds[0];
  assert.equal(outbound.type, 'trojan');
  assert.equal(outbound.network, undefined);
  assert.equal(outbound.tls.reality.public_key, 'public-key');
});

test('builds VLESS WebSocket transport', () => {
  const profile = parseProfileLink('vless://00000000-0000-4000-8000-000000000000@example.com:443?security=tls&type=ws&path=%2Fvpn&host=edge.example.com#WS');
  const config = buildSingBoxConfig(profile);
  assert.deepEqual(config.outbounds[0].transport, {
    type: 'ws',
    path: '/vpn',
    headers: { Host: 'edge.example.com' },
  });
});

test('builds VLESS XHTTP with an explicit streaming mode', () => {
  const profile = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@example.com:443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd'
      + '&type=xhttp&mode=stream-up&path=%2Fxhttp&host=www.example.com&alpn=h2#XHTTP',
  );
  const outbound = buildSingBoxConfig(profile).outbounds[0];

  assert.deepEqual(outbound.transport, {
    type: 'xhttp',
    mode: 'stream-up',
    path: '/xhttp',
    host: 'www.example.com',
  });
  assert.deepEqual(outbound.tls.alpn, ['h2']);
});

test('uses protocol-specific default ports without inventing a Shadowsocks port', () => {
  assert.equal(parseProfileLink('socks5://user:pass@socks.example.com#SOCKS').port, 1080);
  assert.equal(
    parseProfileLink(
      'wg://wg.example.com?pk=private&peer_pk=public&local_address=10.0.0.2%2F32#WG',
    ).port,
    51820,
  );
  assert.equal(parseProfileLink('ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com#SS'), null);
});

test('preserves supported Shadowsocks SIP002 plugin options', () => {
  const profile = parseProfileLink(
    'ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388'
      + '?plugin=v2ray-plugin%3Btls%3Bhost%3Dcdn.example.com%3Bpath%3D%2Fws#SS',
  );
  const outbound = buildSingBoxConfig(profile).outbounds[0];

  assert.equal(profile.plugin, 'v2ray-plugin');
  assert.equal(profile.pluginOptions, 'tls;host=cdn.example.com;path=/ws');
  assert.equal(outbound.plugin, 'v2ray-plugin');
  assert.equal(outbound.plugin_opts, 'tls;host=cdn.example.com;path=/ws');
  assert.equal(
    parseProfileLink('ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388?plugin=unknown#SS'),
    null,
  );
});

test('preserves HTTPUpgrade instead of silently changing it to TCP', () => {
  const profile = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@example.com:443'
      + '?security=tls&type=httpupgrade&path=%2Ftunnel&host=cdn.example.com#HTTPUpgrade',
  );

  assert.deepEqual(buildSingBoxConfig(profile).outbounds[0].transport, {
    type: 'httpupgrade',
    path: '/tunnel',
    host: 'cdn.example.com',
  });
});

test('rejects QUIC only when compatibility mode is enabled', () => {
  const vless = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@example.com:443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#VLESS',
  );
  const hysteria = parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2');

  assert.equal(
    buildSingBoxConfig(vless).route.rules.some(rule => rule.network === 'udp' && rule.port === 443),
    false,
  );
  assert.equal(
    buildSingBoxConfig(hysteria).route.rules.some(rule => rule.network === 'udp' && rule.port === 443),
    false,
  );

  const vlessConfig = buildSingBoxConfig(vless, { quic: true });
  const vlessQuicRule = vlessConfig.route.rules.find(
    rule => rule.network === 'udp' && rule.port === 443 && !rule.process_name,
  );
  assert.deepEqual(vlessQuicRule, {
    network: 'udp',
    port: 443,
    action: 'reject',
    method: 'default',
    no_drop: true,
  });
  const vlessSniffIndex = vlessConfig.route.rules.findIndex(rule => rule.action === 'sniff');
  const vlessQuicIndex = vlessConfig.route.rules.indexOf(vlessQuicRule);
  const browserQuicIndex = vlessConfig.route.rules.findIndex(
    rule => rule.network === 'udp' && rule.port === 443 && rule.process_name?.includes('comet.exe'),
  );
  assert.ok(browserQuicIndex >= 0 && browserQuicIndex < vlessSniffIndex);
  assert.ok(vlessSniffIndex < vlessQuicIndex);
  const hysteriaQuicRule = buildSingBoxConfig(hysteria, { quic: true }).route.rules.find(
    rule => rule.network === 'udp' && rule.port === 443,
  );
  assert.equal(hysteriaQuicRule.method, 'default');
  assert.equal(hysteriaQuicRule.no_drop, true);
});

test('uses the mixed TCP/IP stack on Windows', () => {
  const profile = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@example.com:443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#VLESS',
  );

  assert.equal(buildSingBoxConfig(profile).inbounds[0].stack, 'mixed');
});

test('selectable config preserves sniffing and replaces only the active server route', () => {
  const first = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000001@first.example.com:443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#First',
  );
  const second = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000002@203.0.113.20:8443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#Second',
  );
  const config = buildSelectableSingBoxConfig([first, second], 0);
  const sniffRules = config.route.rules.filter(rule => rule.action === 'sniff');
  const serverRules = config.route.rules.filter(
    rule => rule.outbound === 'direct'
      && (rule.domain?.includes('first.example.com') || rule.ip_cidr?.includes('203.0.113.20/32')),
  );

  assert.equal(sniffRules.length, 1);
  assert.equal(serverRules.length, 2);
});

test('does not hold new connections for a one-second protocol sniff', () => {
  const profile = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@example.com:443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#VLESS',
  );
  const sniff = buildSingBoxConfig(profile).route.rules.find(rule => rule.action === 'sniff');

  assert.equal(sniff.timeout, '300ms');
});

test('hijacks DNS before split rules and forces health checks through VPN', () => {
  const profile = parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2');
  const config = buildSingBoxConfig(profile, {
    appsMode: 'only',
    appsList: ['telegram.exe'],
  });
  const dnsIndex = config.route.rules.findIndex(rule => rule.action === 'hijack-dns');
  const appIndex = config.route.rules.findIndex(rule => rule.process_name);
  const healthRule = config.route.rules.find(rule => rule.domain_suffix?.includes('speed.cloudflare.com'));
  assert.ok(dnsIndex >= 0 && dnsIndex < appIndex);
  assert.equal(healthRule.outbound, 'proxy');
  assert.equal(config.route.rules.some(rule => rule.port?.includes(53) && rule.outbound === 'direct'), false);
});

test('uses proxy-detoured DNS over HTTPS and bounds slow proxy dials', () => {
  const profile = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@example.com:443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#VLESS',
  );
  const config = buildSingBoxConfig(profile, {});
  const proxy = config.outbounds.find(outbound => outbound.tag === 'proxy');
  const remoteDns = config.dns.servers.find(server => server.tag === 'remote');

  assert.equal(proxy.connect_timeout, '10s');
  assert.equal(proxy.tcp_keep_alive, '30s');
  assert.equal(proxy.tcp_keep_alive_interval, '15s');
  assert.deepEqual(remoteDns, {
    type: 'https',
    tag: 'remote',
    server: '1.1.1.1',
    server_port: 443,
    path: '/dns-query',
    detour: 'proxy',
    tls: { enabled: true, server_name: 'cloudflare-dns.com' },
  });
});

test('uses encrypted remote DNS with a direct resolver for bypassed domains', () => {
  const profile = parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2');
  const config = buildSingBoxConfig(profile);
  const remote = config.dns.servers.find(server => server.tag === 'remote');

  assert.deepEqual(remote, {
    type: 'https',
    tag: 'remote',
    server: '1.1.1.1',
    server_port: 443,
    path: '/dns-query',
    detour: 'proxy',
    tls: { enabled: true, server_name: 'cloudflare-dns.com' },
  });
  assert.deepEqual(config.dns.servers.find(server => server.tag === 'local-dns'), {
    type: 'udp',
    tag: 'local-dns',
    server: '1.1.1.1',
    server_port: 53,
  });
  assert.equal(config.log.level, 'warn');
});

test('builds bypass rules without changing the default VPN route', () => {
  const profile = parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2');
  const config = buildSingBoxConfig(profile, {
    lan: true,
    appsMode: 'bypass',
    appsList: ['browser.exe'],
    sitesMode: 'bypass',
    sitesList: ['example.com'],
  });

  assert.equal(config.route.final, 'proxy');
  assert.ok(config.route.rules.some(rule => rule.process_name?.includes('browser.exe') && rule.outbound === 'direct'));
  assert.ok(config.route.rules.some(rule => rule.domain_suffix?.includes('example.com') && rule.outbound === 'direct'));
  assert.ok(config.route.rules.some(rule => rule.ip_is_private === true && rule.outbound === 'direct'));
  assert.ok(config.dns.rules.some(rule => rule.domain_suffix?.includes('example.com') && rule.server === 'local-dns'));
});

test('keeps www host exclusions scoped away from sibling Google services', () => {
  const profile = parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2');
  const config = buildSingBoxConfig(profile, {
    sitesMode: 'bypass',
    sitesList: ['https://www.google.com/search?q=warpy'],
  });
  const siteRule = config.route.rules.find(
    rule => rule.outbound === 'direct' && rule.domain_suffix?.includes('www.google.com'),
  );

  assert.ok(siteRule);
  assert.equal(siteRule.domain_suffix.includes('google.com'), false);
  assert.equal(siteRule.domain_suffix.includes('gemini.google.com'), false);
});

test('routes Russian domain zones outside VLESS and Hysteria2 tunnels', () => {
  const profiles = [
    parseProfileLink(
      'vless://00000000-0000-4000-8000-000000000000@example.com:443'
        + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#VLESS',
    ),
    parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2'),
  ];

  for (const profile of profiles) {
    const config = buildSingBoxConfig(profile, { adblock: true, quic: true });
    const expected = ['.ru', '.xn--p1ai', '.su', 'ozonusercontent.com'];
    const directIndex = config.route.rules.findIndex(
      rule => rule.outbound === 'direct' && expected.every(suffix => rule.domain_suffix?.includes(suffix)),
    );
    const adIndex = config.route.rules.findIndex(
      rule => rule.action === 'reject' && rule.domain_suffix?.includes('doubleclick.net'),
    );
    const quicIndex = config.route.rules.findIndex(rule => rule.network === 'udp' && rule.port === 443);
    const dnsRule = config.dns.rules.find(
      rule => rule.server === 'local-dns' && expected.every(suffix => rule.domain_suffix?.includes(suffix)),
    );

    assert.ok(directIndex > adIndex);
    assert.ok(quicIndex === -1 || quicIndex < adIndex);
    assert.ok(dnsRule);
  }
});

test('uses TUN DNS hijacking without a loopback DNS listener', () => {
  const profile = parseProfileLink(
    'vless://00000000-0000-4000-8000-000000000000@example.com:443'
      + '?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=tcp#VLESS',
  );
  const config = buildSingBoxConfig(profile);
  assert.equal(config.inbounds.some(inbound => inbound.tag === 'dns-in'), false);
  assert.equal(config.route.rules.some(rule => rule.inbound?.includes('dns-in')), false);
  assert.ok(config.route.rules.some(rule => rule.protocol === 'dns' && rule.action === 'hijack-dns'));
});

test('builds only-selected rules with direct fallback and preserves them in selector mode', () => {
  const profiles = [
    parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2'),
    parseProfileLink('trojan://password@203.0.113.11:8444?security=tls&sni=edge.example.com#Trojan'),
  ];
  const settings = {
    appsMode: 'only',
    appsList: ['telegram.exe'],
    sitesMode: 'only',
    sitesList: ['example.org'],
  };
  const config = buildSelectableSingBoxConfig(profiles, 0, settings);

  assert.equal(config.route.final, 'direct');
  assert.ok(config.route.rules.some(rule => rule.process_name?.includes('telegram.exe') && rule.outbound === 'proxy'));
  assert.ok(config.route.rules.some(rule => rule.domain_suffix?.includes('example.org') && rule.outbound === 'proxy'));
  assert.ok(config.dns.rules.some(rule => rule.domain_suffix?.includes('example.org') && rule.server === 'remote'));
  assert.equal(config.dns.final, 'local-dns');
  assert.ok(config.route.rules.some(rule => rule.ip_cidr?.includes('203.0.113.10/32') && rule.outbound === 'direct'));
  assert.ok(config.route.rules.some(rule => rule.ip_cidr?.includes('203.0.113.11/32') && rule.outbound === 'direct'));
});

test('ad blocking is self-contained and does not reference a missing rule-set', () => {
  const profile = parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2');
  const config = buildSingBoxConfig(profile, { adblock: true });
  assert.equal(config.route.rule_set, undefined);
  assert.ok(config.dns.rules.some(rule => rule.action === 'predefined'));
});

test('rejects malformed and unsupported links', () => {
  assert.equal(parseProfileLink('vless://missing-host'), null);
  assert.equal(parseProfileLink('ss://secret@example.com:443'), null);
  assert.equal(parseProfileLink('vless://id@example.com:443?type=kcp'), null);
});

test('normalizes common transport aliases', () => {
  const fixtures = new Map([
    ['h2', 'http'],
    ['http-upgrade', 'httpupgrade'],
    ['splithttp', 'xhttp'],
  ]);
  for (const [source, expected] of fixtures) {
    const profile = parseProfileLink(
      `vless://00000000-0000-4000-8000-000000000001@example.com:443?type=${source}#Alias`,
    );
    assert.equal(profile.transport, expected);
  }
});

test('builds a selector config with every server available for direct dialing', () => {
  const profiles = [
    parseProfileLink('hysteria2://secret@203.0.113.10:443?insecure=1#HY2'),
    parseProfileLink('trojan://password@edge.example.com:8444?security=tls&sni=edge.example.com#Trojan'),
  ];
  const config = buildSelectableSingBoxConfig(profiles, 1, {}, {
    externalController: '127.0.0.1:19090',
    secret: 'test-secret',
  });

  assert.deepEqual(config.outbounds[0], {
    type: 'selector',
    tag: 'proxy',
    outbounds: ['profile-1', 'profile-2'],
    default: 'profile-2',
    interrupt_exist_connections: true,
  });
  assert.equal(config.outbounds[1].tag, 'profile-1');
  assert.equal(config.outbounds[2].tag, 'profile-2');
  assert.ok(config.route.rules.some(rule => rule.ip_cidr?.includes('203.0.113.10/32')));
  assert.ok(config.route.rules.some(rule => rule.domain?.includes('edge.example.com')));
  assert.ok(config.dns.rules.some(rule => rule.domain?.includes('edge.example.com') && rule.server === 'local-dns'));
  assert.equal(config.experimental.clash_api.external_controller, '127.0.0.1:19090');
  assert.equal(config.experimental.clash_api.secret, 'test-secret');
});

test('rejects an invalid selector profile index', () => {
  const profile = parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2');
  assert.throws(() => buildSelectableSingBoxConfig([profile], 1), /out of range/);
});

test('manual runtime includes only the selected profile', () => {
  const profiles = [
    parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2'),
    parseProfileLink('vless://00000000-0000-4000-8000-000000000000@example.com:443?security=tls#VLESS'),
  ];
  const runtime = buildRuntimeSingBoxConfig(profiles, 1, {}, false);

  assert.deepEqual(runtime.profileIndexes, [1]);
  assert.equal(runtime.config.outbounds[0].type, 'selector');
  assert.equal(runtime.config.outbounds[0].tag, 'proxy');
  assert.deepEqual(runtime.config.outbounds[0].outbounds, ['profile-1']);
  assert.equal(runtime.config.outbounds[1].type, 'vless');
  assert.equal(runtime.config.outbounds[1].tag, 'profile-1');
  assert.equal(runtime.config.outbounds.filter(outbound => outbound.tag?.startsWith('profile-')).length, 1);
});

test('automatic runtime keeps valid profiles in one selector', () => {
  const profiles = [
    parseProfileLink('hysteria2://secret@203.0.113.10:443#HY2'),
    parseProfileLink('vless://00000000-0000-4000-8000-000000000000@example.com:443?security=tls#VLESS'),
  ];
  const runtime = buildRuntimeSingBoxConfig(profiles, 1, {}, true);

  assert.deepEqual(runtime.profileIndexes, [0, 1]);
  assert.equal(runtime.config.outbounds[0].type, 'selector');
  assert.equal(runtime.config.outbounds[0].default, 'profile-2');
});

test('bundled sing-box accepts generated configs without deprecated modes', { skip: process.platform !== 'win32' }, () => {
  const binary = resolve('src-tauri/bin/sing-box-x86_64-pc-windows-msvc.exe');
  const vmessPayload = Buffer.from(JSON.stringify({
    v: '2', ps: 'VMess', add: 'vmess.example.com', port: '443',
    id: '00000000-0000-4000-8000-000000000123', aid: '0', scy: 'auto', net: 'ws',
    host: 'cdn.example.com', path: '/ws', tls: 'tls', sni: 'cdn.example.com',
  })).toString('base64');
  const links = [
    'hysteria2://secret@203.0.113.10:443?insecure=1&obfs=salamander&obfs-password=pepper#HY2',
    'trojan://password@203.0.113.11:8444?security=reality&sni=www.apple.com&pbk=public-key&sid=b5d9&type=tcp#Trojan',
    'vless://00000000-0000-4000-8000-000000000000@example.com:443?security=tls&type=ws&path=%2Fvpn&host=edge.example.com#WS',
    'vless://00000000-0000-4000-8000-000000000000@example.com:443?security=reality&sni=www.example.com&pbk=public-key&sid=0123abcd&type=xhttp&mode=stream-up&path=%2Fxhttp&host=www.example.com&alpn=h2#XHTTP',
    `vmess://${vmessPayload}`,
    'ss://YWVzLTI1Ni1nY206c2VjcmV0@ss.example.com:8388#SS',
    'socks5://user:pass@socks.example.com:1080#SOCKS',
    'wg://wg.example.com:51820?pk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%3D&peer_pk=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB%3D&local_address=10.0.0.2%2F32#WG',
    'tuic://00000000-0000-4000-8000-000000000123:secret@tuic.example.com:443?sni=tuic.example.com#TUIC',
    'hysteria://hy.example.com:443?auth=secret&peer=hy.example.com&upmbps=50&downmbps=100#Hysteria',
    'naive+https://user:password@naive.example.com:443?sni=naive.example.com&alpn=h2#Naive',
  ];
  const directory = mkdtempSync(join(tmpdir(), 'warpy-config-test-'));
  try {
    links.forEach((link, index) => {
      const path = join(directory, `${index}.json`);
      writeFileSync(path, JSON.stringify(buildSingBoxConfig(parseProfileLink(link))));
      const result = spawnSync(binary, ['check', '-c', path], { encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr || result.stdout);
      if (!link.startsWith('wg://')) {
        assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /deprecated/i);
      }
    });

    const selectorPath = join(directory, 'selector.json');
    const selectorProfiles = links.map(parseProfileLink);
    writeFileSync(selectorPath, JSON.stringify(buildSelectableSingBoxConfig(
      selectorProfiles,
      1,
      {},
      { externalController: '127.0.0.1:19090', secret: 'test-secret' },
    )));
    const selectorResult = spawnSync(binary, ['check', '-c', selectorPath], { encoding: 'utf8' });
    assert.equal(selectorResult.status, 0, selectorResult.stderr || selectorResult.stdout);
    assert.doesNotMatch(`${selectorResult.stdout}\n${selectorResult.stderr}`, /deprecated/i);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
