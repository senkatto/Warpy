import assert from 'node:assert/strict';
import test from 'node:test';
import {
  AUTO_SUBSCRIPTION_REFRESH_INTERVAL_MS,
  findProfileIndexAfterSubscriptionUpdate,
  MAX_SUBSCRIPTION_TEXT_LENGTH,
  parseSubscriptionPayload,
  replaceSubscriptionProfiles,
  subscriptionDisplayName,
  subscriptionProfileKey,
  subscriptionProfilesEqual,
  subscriptionRefreshDue,
} from '../src/subscription.js';

const links = [
  'vless://00000000-0000-4000-8000-000000000000@example.com:443?security=tls#Primary',
  'trojan://password@example.net:8443?security=tls&sni=example.net#Reserve',
  'hysteria2://secret@203.0.113.10:2443?insecure=1#Fast',
];

test('parses a URI subscription and reports unsupported entries', () => {
  const result = parseSubscriptionPayload([
    '# provider comment',
    links[0],
    'ss://unsupported@example.org:443',
    links[1],
    links[2],
  ].join('\n'));

  assert.equal(result.format, 'uri-list');
  assert.equal(result.profiles.length, 3);
  assert.equal(result.skipped, 1);
  assert.deepEqual(result.profiles.map(profile => profile.protocol), ['vless', 'trojan', 'hysteria2']);
});

test('parses unpadded URL-safe Base64 subscriptions', () => {
  const encoded = Buffer.from(links.join('\n'), 'utf8')
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  const result = parseSubscriptionPayload(encoded);

  assert.equal(result.format, 'base64');
  assert.equal(result.profiles.length, 3);
  assert.equal(result.skipped, 0);
});

test('extracts supported outbounds from sing-box JSON without applying unrelated config', () => {
  const payload = JSON.stringify({
    dns: { servers: [{ tag: 'untrusted', address: 'rcode://success' }] },
    route: { rules: [{ action: 'reject' }] },
    outbounds: [
      {
        type: 'vless',
        tag: 'Reality TCP',
        server: '2001:db8::10',
        server_port: 443,
        uuid: '00000000-0000-4000-8000-000000000000',
        flow: 'xtls-rprx-vision',
        packet_encoding: 'xudp',
        tls: {
          enabled: true,
          server_name: 'www.example.com',
          utls: { enabled: true, fingerprint: 'chrome' },
          reality: { enabled: true, public_key: 'public-key', short_id: '0123abcd' },
        },
      },
      {
        type: 'vless',
        tag: 'VLESS WebSocket',
        server: 'ws.example.com',
        server_port: 443,
        uuid: '10000000-0000-4000-8000-000000000000',
        tls: { enabled: true, server_name: 'origin.example.com' },
        transport: { type: 'ws', path: '/socket', headers: { Host: 'cdn.example.com' } },
      },
      {
        type: 'trojan',
        tag: 'Trojan gRPC',
        server: 'trojan.example.com',
        server_port: 8443,
        password: ' secret:with@symbols ',
        tls: { enabled: true, server_name: 'trojan.example.com', insecure: true },
        transport: { type: 'grpc', service_name: 'warpy-service' },
      },
      {
        type: 'hysteria2',
        tag: 'Hysteria2',
        server: '203.0.113.20',
        server_port: 2443,
        password: 'hy-secret',
        tls: { enabled: true, server_name: 'hy.example.com', alpn: ['h3'] },
        obfs: { type: 'salamander', password: ' obfs-secret ' },
      },
      { type: 'direct', tag: 'direct' },
    ],
  });
  const result = parseSubscriptionPayload(payload);

  assert.equal(result.format, 'sing-box-json');
  assert.equal(result.profiles.length, 4);
  assert.equal(result.skipped, 1);
  assert.deepEqual(
    result.profiles.map(profile => profile.protocol),
    ['vless', 'vless', 'trojan', 'hysteria2'],
  );
  assert.deepEqual(
    {
      host: result.profiles[0].host,
      security: result.profiles[0].security,
      pbk: result.profiles[0].pbk,
      sid: result.profiles[0].sid,
      flow: result.profiles[0].flow,
      transport: result.profiles[0].transport,
    },
    {
      host: '2001:db8::10',
      security: 'reality',
      pbk: 'public-key',
      sid: '0123abcd',
      flow: 'xtls-rprx-vision',
      transport: 'tcp',
    },
  );
  assert.equal(result.profiles[1].transport, 'ws');
  assert.equal(result.profiles[1].path, '/socket');
  assert.equal(result.profiles[1].hostHeader, 'cdn.example.com');
  assert.equal(result.profiles[2].uuid, ' secret:with@symbols ');
  assert.equal(result.profiles[2].serviceName, 'warpy-service');
  assert.equal(result.profiles[2].insecure, true);
  assert.equal(result.profiles[3].obfsType, 'salamander');
  assert.equal(result.profiles[3].obfsPassword, ' obfs-secret ');
  assert.deepEqual(result.profiles[3].alpn, ['h3']);
});

test('supports Base64 sing-box JSON and rejects JSON without usable proxy outbounds', () => {
  const config = JSON.stringify({ outbounds: [{
    type: 'trojan',
    tag: 'Proxy',
    server: 'example.net',
    server_port: 443,
    password: 'secret',
  }] });
  const encoded = Buffer.from(config, 'utf8').toString('base64').replace(/=+$/, '');

  assert.equal(parseSubscriptionPayload(encoded).format, 'base64-sing-box-json');
  assert.throws(
    () => parseSubscriptionPayload(JSON.stringify({ outbounds: [{ type: 'direct' }] })),
    /поддерживаемых профилей/,
  );
  assert.throws(
    () => parseSubscriptionPayload(JSON.stringify({ route: { rules: [] } })),
    /поддерживаемых профилей/,
  );
  assert.throws(
    () => parseSubscriptionPayload(JSON.stringify({ outbounds: [{
      type: 'vless',
      server: 'example.com',
      server_port: 443,
      uuid: '00000000-0000-4000-8000-000000000000',
      transport: { type: 'quic' },
    }] })),
    /поддерживаемых профилей/,
  );
});

test('preserves XHTTP streaming mode from sing-box JSON', () => {
  const result = parseSubscriptionPayload(JSON.stringify({
    outbounds: [{
      type: 'vless',
      tag: 'XHTTP Reality',
      server: 'xhttp.example.com',
      server_port: 443,
      uuid: '00000000-0000-4000-8000-000000000000',
      tls: {
        enabled: true,
        server_name: 'www.example.com',
        alpn: ['h2'],
        reality: { enabled: true, public_key: 'public-key', short_id: '0123abcd' },
      },
      transport: {
        type: 'xhttp',
        mode: 'stream-up',
        path: '/xhttp',
        host: 'www.example.com',
      },
    }],
  }));

  assert.equal(result.profiles.length, 1);
  assert.equal(result.profiles[0].transport, 'xhttp');
  assert.equal(result.profiles[0].xhttpMode, 'stream-up');
  assert.equal(result.profiles[0].path, '/xhttp');
  assert.equal(result.profiles[0].hostHeader, 'www.example.com');
});

test('extracts only supported inline proxies from Clash YAML', () => {
  const payload = `
mixed-port: 7890
dns:
  enable: true
  nameserver: [198.51.100.53]
proxy-providers:
  remote:
    type: http
    url: https://untrusted.example/provider.yaml
rules:
  - MATCH,DIRECT
script:
  code: throw new Error('must never run')
proxies:
  - name: Reality IPv6
    type: vless
    server: '2001:db8::20'
    port: 443
    uuid: 00000000-0000-4000-8000-000000000001
    tls: true
    servername: www.example.com
    flow: xtls-rprx-vision
    packet-encoding: xudp
    client-fingerprint: chrome
    reality-opts:
      public-key: reality-public-key
      short-id: 0123abcd
  - name: Trojan WebSocket
    type: trojan
    server: trojan.example.com
    port: 8443
    password: ' secret:with@symbols '
    sni: origin.example.com
    skip-cert-verify: true
    alpn: [h2, http/1.1]
    network: ws
    ws-opts:
      path: /socket
      headers:
        Host: cdn.example.com
  - name: Hysteria2
    type: hysteria2
    server: 203.0.113.30
    port: 2443
    password: ' hy-secret '
    sni: hy.example.com
    network: udp
    obfs: salamander
    obfs-password: ' obfs-secret '
  - name: Unsupported
    type: socks5
    server: 127.0.0.1
    port: 1080
`;
  const result = parseSubscriptionPayload(payload);

  assert.equal(result.format, 'clash-yaml');
  assert.equal(result.profiles.length, 3);
  assert.equal(result.skipped, 1);
  assert.deepEqual(result.profiles.map(profile => profile.protocol), ['vless', 'trojan', 'hysteria2']);
  assert.deepEqual(
    {
      host: result.profiles[0].host,
      security: result.profiles[0].security,
      pbk: result.profiles[0].pbk,
      sid: result.profiles[0].sid,
      flow: result.profiles[0].flow,
      packetEncoding: result.profiles[0].packetEncoding,
      fp: result.profiles[0].fp,
    },
    {
      host: '2001:db8::20',
      security: 'reality',
      pbk: 'reality-public-key',
      sid: '0123abcd',
      flow: 'xtls-rprx-vision',
      packetEncoding: 'xudp',
      fp: 'chrome',
    },
  );
  assert.equal(result.profiles[1].uuid, ' secret:with@symbols ');
  assert.equal(result.profiles[1].transport, 'ws');
  assert.equal(result.profiles[1].path, '/socket');
  assert.equal(result.profiles[1].hostHeader, 'cdn.example.com');
  assert.equal(result.profiles[1].insecure, true);
  assert.deepEqual(result.profiles[1].alpn, ['h2', 'http/1.1']);
  assert.equal(result.profiles[2].uuid, ' hy-secret ');
  assert.equal(result.profiles[2].obfsType, 'salamander');
  assert.equal(result.profiles[2].obfsPassword, ' obfs-secret ');
});

test('supports Base64 Clash YAML with gRPC options', () => {
  const config = `
proxies:
  - name: Trojan gRPC
    type: trojan
    server: grpc.example.net
    port: 443
    password: secret
    network: grpc
    grpc-opts:
      grpc-service-name: warpy-service
`;
  const encoded = Buffer.from(config, 'utf8').toString('base64').replace(/=+$/, '');
  const result = parseSubscriptionPayload(encoded);

  assert.equal(result.format, 'base64-clash-yaml');
  assert.equal(result.profiles.length, 1);
  assert.equal(result.profiles[0].serviceName, 'warpy-service');
});

test('rejects unsafe or ambiguous Clash YAML structures', () => {
  assert.throws(
    () => parseSubscriptionPayload(`
proxy-providers:
  remote:
    type: http
    url: https://example.com/provider.yaml
`),
    /поддерживаемых профилей/,
  );
  assert.throws(
    () => parseSubscriptionPayload(`
proxies:
  - name: Duplicate
    type: vless
    server: example.com
    port: 443
    port: 8443
    uuid: 00000000-0000-4000-8000-000000000000
`),
    /поддерживаемых профилей/,
  );
  assert.throws(
    () => parseSubscriptionPayload(`
defaults: &defaults
  type: vless
  server: example.com
  port: 443
  uuid: 00000000-0000-4000-8000-000000000000
proxies:
  - <<: *defaults
    name: Merged
`),
    /поддерживаемых профилей/,
  );
  assert.throws(
    () => parseSubscriptionPayload(`
proxies:
  - !custom
    name: Tagged
    type: trojan
    server: example.com
    port: 443
    password: secret
`),
    /поддерживаемых профилей/,
  );
  assert.throws(
    () => parseSubscriptionPayload(`
proxies:
  - name: Unknown transport
    type: vless
    server: example.com
    port: 443
    uuid: 00000000-0000-4000-8000-000000000000
    network: quic
`),
    /поддерживаемых профилей/,
  );
});

test('deduplicates renamed copies without exposing the profile name as identity', () => {
  const renamed = links[0].replace('#Primary', '#Renamed');
  const result = parseSubscriptionPayload(`${links[0]}\n${renamed}`);

  assert.equal(result.profiles.length, 1);
  assert.equal(
    subscriptionProfileKey(result.profiles[0]),
    links[0].split('#', 1)[0],
  );
});

test('rejects HTML, empty data and oversized payloads', () => {
  assert.throws(() => parseSubscriptionPayload('<!doctype html><title>Login</title>'), /веб-страницу/);
  assert.throws(() => parseSubscriptionPayload(''), /пуста/);
  assert.throws(
    () => parseSubscriptionPayload('x'.repeat(MAX_SUBSCRIPTION_TEXT_LENGTH + 1)),
    /размер/,
  );
  assert.throws(() => parseSubscriptionPayload('not a subscription'), /поддерживаемых профилей/);
});

test('replaces a subscription in place without mutating existing profiles', () => {
  const manual = { name: 'Manual', raw: links[0] };
  const old = { name: 'Old', raw: links[1], subscriptionId: 'sub-a', group: 'OLD' };
  const other = { name: 'Other', raw: links[2], subscriptionId: 'sub-b', group: 'OTHER' };
  const imported = [{ name: 'New', raw: links[1].replace('#Reserve', '#Renamed') }];

  const result = replaceSubscriptionProfiles([manual, old, other], 'sub-a', imported, 'PROVIDER');

  assert.deepEqual(result.map(profile => profile.name), ['Manual', 'New', 'Other']);
  assert.equal(result[1].subscriptionId, 'sub-a');
  assert.equal(result[1].group, 'PROVIDER');
  assert.equal(old.group, 'OLD');
  assert.equal(imported[0].subscriptionId, undefined);
});

test('appends a new subscription and preserves renamed profile identity', () => {
  const imported = [{ name: 'Renamed', raw: links[0].replace('#Primary', '#Renamed') }];
  const result = replaceSubscriptionProfiles([], 'sub-new', imported, 'NEW');

  assert.equal(result.length, 1);
  assert.equal(subscriptionProfileKey(result[0]), links[0].split('#', 1)[0]);
});

test('never deletes an existing subscription when the replacement is empty', () => {
  const existing = [{ name: 'Working', raw: links[0], subscriptionId: 'sub-a' }];
  assert.throws(
    () => replaceSubscriptionProfiles(existing, 'sub-a', [], 'PROVIDER'),
    /must contain profiles/,
  );
  assert.equal(existing.length, 1);
});

test('keeps the selected subscription profile when only its display name changes', () => {
  const previous = {
    name: 'Primary',
    raw: links[0],
    subscriptionId: 'sub-a',
  };
  const profiles = [
    { name: 'Manual', raw: links[2] },
    {
      name: 'Renamed',
      raw: links[0].replace('#Primary', '#Renamed'),
      subscriptionId: 'sub-a',
    },
  ];

  assert.equal(findProfileIndexAfterSubscriptionUpdate(profiles, previous, 'sub-a'), 1);
});

test('uses a safe URL fragment as the subscription group name', () => {
  assert.equal(
    subscriptionDisplayName('https://0123456789abcdef.withprovider.example/sub/token#BlancVPN'),
    'BlancVPN',
  );
  assert.equal(
    subscriptionDisplayName('https://0123456789abcdef.withprovider.example/sub/token'),
    'PROVIDER',
  );
});

test('adopts a matching legacy group instead of creating a duplicate category', () => {
  const legacyProfiles = [
    { name: 'Manual', raw: links[2] },
    { name: 'Legacy one', raw: links[0], group: 'BLANCVPN' },
    { name: 'Legacy two', raw: links[1], group: 'BLANCVPN' },
  ];
  const imported = [{ name: 'Updated', raw: links[0] }];

  const result = replaceSubscriptionProfiles(
    legacyProfiles,
    'sub-blanc',
    imported,
    'BLANCVPN',
    'BLANCVPN',
  );

  assert.equal(result.length, 2);
  assert.equal(result[1].name, 'Updated');
  assert.equal(result[1].subscriptionId, 'sub-blanc');
  assert.equal(result[1].group, 'BLANCVPN');
  assert.equal(legacyProfiles[1].subscriptionId, undefined);
});

test('detects unchanged subscription profiles while ignoring management metadata', () => {
  const current = [
    { name: 'Primary', raw: links[0], subscriptionId: 'sub-a', group: 'PROVIDER' },
    { name: 'Reserve', raw: links[1], subscriptionId: 'sub-a', group: 'PROVIDER' },
  ];
  const imported = [
    { name: 'Primary', raw: links[0] },
    { name: 'Reserve', raw: links[1] },
  ];

  assert.equal(subscriptionProfilesEqual(current, imported), true);
});

test('detects profile changes, display-name changes and reordered subscriptions', () => {
  const current = [
    { name: 'Primary', raw: links[0] },
    { name: 'Reserve', raw: links[1] },
  ];

  assert.equal(subscriptionProfilesEqual(current, [current[1], current[0]]), false);
  assert.equal(
    subscriptionProfilesEqual(current, [
      { name: 'Renamed', raw: links[0].replace('#Primary', '#Renamed') },
      current[1],
    ]),
    false,
  );
  assert.equal(subscriptionProfilesEqual(current, [current[0]]), false);
});

test('schedules subscription refreshes no more than once per day', () => {
  const now = 1_800_000_000_000;
  assert.equal(subscriptionRefreshDue({}, now), true);
  assert.equal(subscriptionRefreshDue({ lastCheckedAt: now - 1000 }, now), false);
  assert.equal(
    subscriptionRefreshDue({ lastCheckedAt: now - AUTO_SUBSCRIPTION_REFRESH_INTERVAL_MS }, now),
    true,
  );
  assert.equal(subscriptionRefreshDue({ lastCheckedAt: now + 1000 }, now), true);
});
