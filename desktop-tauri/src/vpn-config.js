import { CORE_CONTRACT } from './generated/core-contract.js';

const SUPPORTED_PROTOCOLS = new Set(CORE_CONTRACT.protocols);
const SUPPORTED_TRANSPORTS = new Set(CORE_CONTRACT.transports);
const TAGS = CORE_CONTRACT.tags;
const DNS = CORE_CONTRACT.dns;
const ROUTING = CORE_CONTRACT.routing;
const WINDOWS = CORE_CONTRACT.platforms.windows;
const TRANSPORT_ALIASES = new Map([
  ['h2', 'http'],
  ['http-upgrade', 'httpupgrade'],
  ['http_upgrade', 'httpupgrade'],
  ['splithttp', 'xhttp'],
  ['split-http', 'xhttp'],
  ['split_http', 'xhttp'],
]);

const AD_DOMAINS = [
  '2mdn.net',
  'adform.net',
  'adnxs.com',
  'adsafeprotected.com',
  'adsrvr.org',
  'adservice.google.com',
  'amazon-adsystem.com',
  'app-measurement.com',
  'appsflyer.com',
  'branch.io',
  'casalemedia.com',
  'chartbeat.com',
  'criteo.com',
  'criteo.net',
  'doubleclick.net',
  'flurry.com',
  'google-analytics.com',
  'googleadservices.com',
  'googlesyndication.com',
  'googletagmanager.com',
  'googletagservices.com',
  'hotjar.com',
  'imasdk.googleapis.com',
  'inmobi.com',
  'ironsrc.com',
  'media.net',
  'mixpanel.com',
  'moatads.com',
  'mopub.com',
  'onesignal.com',
  'openx.net',
  'outbrain.com',
  'pubmatic.com',
  'scorecardresearch.com',
  'segment.io',
  'sentry.io',
  'smartadserver.com',
  'taboola.com',
  'unityads.unity3d.com',
  'vungle.com',
  'yandexadexchange.net',
  'yandexmetrica.com',
];

const QUIC_BROWSER_PROCESSES = [
  'arc.exe',
  'brave.exe',
  'chrome.exe',
  'comet.exe',
  'firefox.exe',
  'msedge.exe',
  'opera.exe',
  'vivaldi.exe',
];
const RUSSIAN_DOMAIN_SUFFIXES = ROUTING.russianDomainSuffixes;
const PROXY_DIAL = Object.freeze({
  connectTimeout: '10s',
  tcpKeepAlive: '30s',
  tcpKeepAliveInterval: '15s',
});

function decode(value) {
  return decodeURIComponent(value || '');
}

function normalizeTransport(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return TRANSPORT_ALIASES.get(normalized) || normalized;
}

function decodeBase64Utf8(value) {
  const compact = String(value || '').trim().replace(/-/g, '+').replace(/_/g, '/');
  if (!compact || compact.length % 4 === 1) return null;
  try {
    const binary = atob(compact + '='.repeat((4 - compact.length % 4) % 4));
    return new TextDecoder().decode(Uint8Array.from(binary, character => character.charCodeAt(0)));
  } catch {
    return null;
  }
}

function encodeBase64Utf8(value, { urlSafe = false } = {}) {
  const bytes = new TextEncoder().encode(String(value));
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  const encoded = btoa(binary);
  return urlSafe ? encoded.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '') : encoded;
}

function encodeQuery(entries) {
  const query = entries
    .filter(([, value]) => value !== undefined && value !== null && String(value) !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
  return query ? `?${query}` : '';
}

function profileEndpoint(profile) {
  const host = String(profile.host || '');
  return `${host.includes(':') && !host.startsWith('[') ? `[${host}]` : host}:${profile.port}`;
}

function profileFragment(profile) {
  return `#${encodeURIComponent(String(profile.name || profile.sni || profile.host || profile.protocol))}`;
}

function profileName(url, fallback) {
  return decode(url.hash.slice(1)).trim() || fallback;
}

function parseVmessLink(source) {
  const decoded = decodeBase64Utf8(source.replace(/^vmess:\/\//i, '').split('#', 1)[0]);
  if (!decoded) return null;
  try {
    const value = JSON.parse(decoded);
    const host = String(value.add || '').trim();
    const port = Number.parseInt(value.port, 10);
    const uuid = String(value.id || '').trim();
    const transport = normalizeTransport(value.net || 'tcp');
    if (!host || !uuid || !Number.isInteger(port) || port < 1 || port > 65535) return null;
    if (!SUPPORTED_TRANSPORTS.has(transport)) return null;
    const requestedXhttpMode = String(value.mode || '').toLowerCase();
    return {
      protocol: 'vmess',
      name: String(value.ps || '').trim() || host,
      host,
      port,
      uuid,
      security: String(value.tls || '').toLowerCase(),
      sni: String(value.sni || ''),
      fp: String(value.fp || 'chrome'),
      alpn: String(value.alpn || '').split(',').map(item => item.trim()).filter(Boolean),
      transport,
      path: String(value.path || ''),
      hostHeader: String(value.host || ''),
      serviceName: transport === 'grpc' ? String(value.path || '') : '',
      xhttpMode: transport === 'xhttp' && ['stream-up', 'stream-one', 'packet-up'].includes(requestedXhttpMode)
        ? requestedXhttpMode
        : (transport === 'xhttp' ? WINDOWS.xhttpDefaultMode : ''),
      encryption: String(value.scy || 'auto'),
      alterId: Number.parseInt(value.aid, 10) || 0,
      packetEncoding: String(value.packetEncoding || ''),
      raw: source,
    };
  } catch {
    return null;
  }
}

function parseShadowsocksLink(source) {
  const [withoutFragment] = source.split('#', 1);
  const name = source.includes('#') ? decode(source.slice(source.indexOf('#') + 1)).trim() : '';
  const body = withoutFragment.replace(/^ss:\/\//i, '');
  let decoded;
  if (body.includes('@')) {
    const at = body.lastIndexOf('@');
    const rawCredential = body.slice(0, at);
    const credential = rawCredential.includes(':') ? decode(rawCredential) : decodeBase64Utf8(rawCredential);
    if (!credential) return null;
    decoded = `${credential}@${body.slice(at + 1)}`;
  } else {
    decoded = decodeBase64Utf8(body);
  }
  if (!decoded?.includes('@')) return null;
  const at = decoded.lastIndexOf('@');
  const credential = decoded.slice(0, at);
  const separator = credential.indexOf(':');
  if (separator < 1) return null;
  try {
    const url = new URL(`ss://${encodeURIComponent(credential.slice(0, separator))}:${encodeURIComponent(credential.slice(separator + 1))}@${decoded.slice(at + 1)}`);
    const port = parsePort(url, null);
    if (!url.hostname || !port) return null;
    const pluginSpec = url.searchParams.get('plugin') || '';
    const separatorIndex = pluginSpec.indexOf(';');
    const plugin = (separatorIndex < 0 ? pluginSpec : pluginSpec.slice(0, separatorIndex)).trim();
    const pluginOptions = separatorIndex < 0 ? '' : pluginSpec.slice(separatorIndex + 1);
    if (plugin && !['obfs-local', 'v2ray-plugin'].includes(plugin)) return null;
    return {
      protocol: 'shadowsocks',
      name: name || url.hostname,
      host: url.hostname,
      port,
      encryption: credential.slice(0, separator),
      password: credential.slice(separator + 1),
      plugin,
      pluginOptions,
      raw: source,
    };
  } catch {
    return null;
  }
}

function parsePort(url, defaultPort = 443) {
  const port = url.port ? Number.parseInt(url.port, 10) : defaultPort;
  return Number.isInteger(port) && port > 0 && port <= 65535 ? port : null;
}

export function parseProfileLink(rawLink) {
  const source = String(rawLink || '').trim();
  if (!source) return null;

  if (/^vmess:\/\//i.test(source)) return parseVmessLink(source);
  if (/^ss:\/\//i.test(source)) return parseShadowsocksLink(source);

  try {
    const naiveQuic = /^naive\+quic:\/\//i.test(source);
    const link = source
      .replace(/^hy2:\/\//i, 'hysteria2://')
      .replace(/^socks5:\/\//i, 'socks://')
      .replace(/^wg:\/\//i, 'wireguard://')
      .replace(/^naive\+(?:https|quic):\/\//i, 'naive://');
    const url = new URL(link);
    const parsedProtocol = url.protocol.slice(0, -1).toLowerCase();
    const protocol = parsedProtocol === 'https' && url.username && url.password
      ? 'naive'
      : parsedProtocol;
    const port = parsePort(url, protocol === 'socks' ? 1080 : (protocol === 'wireguard' ? 51820 : 443));
    const credential = decode(url.username);
    const password = decode(url.password);
    const opaqueCredential = password ? `${credential}:${password}` : credential;
    const host = url.hostname.startsWith('[') && url.hostname.endsWith(']')
      ? url.hostname.slice(1, -1)
      : url.hostname;
    if (!SUPPORTED_PROTOCOLS.has(protocol) || !host || !port) return null;
    if (['vless', 'trojan', 'hysteria2', 'tuic', 'naive'].includes(protocol) && !credential) return null;
    if (protocol === 'naive' && !password) return null;

    const obfsPassword = url.searchParams.get('obfs-password')
      || url.searchParams.get('obfs_password')
      || '';
    const transport = normalizeTransport(url.searchParams.get('type') || 'tcp');
    const requestedXhttpMode = (url.searchParams.get('mode') || '').toLowerCase();
    const profile = {
      protocol,
      name: profileName(url, host || protocol),
      host,
      port,
      uuid: ['trojan', 'hysteria2', 'hysteria'].includes(protocol) ? opaqueCredential : credential,
      security: protocol === 'naive' ? 'tls' : (url.searchParams.get('security') || '').toLowerCase(),
      sni: url.searchParams.get('sni') || url.searchParams.get('peer') || '',
      pbk: url.searchParams.get('pbk') || '',
      sid: url.searchParams.get('sid') || '',
      flow: url.searchParams.get('flow') || '',
      fp: url.searchParams.get('fp') || '',
      transport,
      path: decode(url.searchParams.get('path') || ''),
      hostHeader: url.searchParams.get('host') || '',
      serviceName: decode(url.searchParams.get('serviceName') || url.searchParams.get('service_name') || ''),
      xhttpMode: transport === 'xhttp' && ['stream-up', 'stream-one', 'packet-up'].includes(requestedXhttpMode)
        ? requestedXhttpMode
        : (transport === 'xhttp' ? WINDOWS.xhttpDefaultMode : ''),
      alpn: (url.searchParams.get('alpn') || '').split(',').map(item => item.trim()).filter(Boolean),
      packetEncoding: url.searchParams.get('packetEncoding') || url.searchParams.get('packet_encoding') || '',
      insecure: ['1', 'true'].includes(
        url.searchParams.get('insecure')
          || url.searchParams.get('allowInsecure')
          || url.searchParams.get('allow_insecure')
          || ''
      ),
      obfsType: url.searchParams.get('obfs')
        || url.searchParams.get('obfs-type')
        || (obfsPassword ? 'salamander' : ''),
      obfsPassword,
      serverPorts: url.searchParams.get('server_ports')
        || url.searchParams.get('server-ports')
        || url.searchParams.get('mport')
        || url.searchParams.get('ports')
        || '',
      hopInterval: url.searchParams.get('hop_interval') || url.searchParams.get('hop-interval') || '',
      hopIntervalMax: url.searchParams.get('hop_interval_max') || url.searchParams.get('hop-interval-max') || '',
      upMbps: Number.parseInt(url.searchParams.get('up_mbps') || url.searchParams.get('upmbps'), 10) || 0,
      downMbps: Number.parseInt(url.searchParams.get('down_mbps') || url.searchParams.get('downmbps'), 10) || 0,
      username: protocol === 'socks' || protocol === 'naive' ? credential : '',
      password: ['tuic', 'socks', 'naive'].includes(protocol) ? password : '',
      encryption: '',
      alterId: 0,
      privateKey: url.searchParams.get('pk') || url.searchParams.get('private_key') || '',
      peerPublicKey: url.searchParams.get('peer_pk') || url.searchParams.get('public_key') || '',
      preSharedKey: url.searchParams.get('pre_shared_key') || url.searchParams.get('psk') || '',
      localAddress: url.searchParams.get('local_address') || url.searchParams.get('address') || '',
      reserved: url.searchParams.get('reserved') || '',
      mtu: Number.parseInt(url.searchParams.get('mtu'), 10) || 0,
      congestionControl: url.searchParams.get('congestion_control') || url.searchParams.get('congestion-control') || 'cubic',
      udpRelayMode: url.searchParams.get('udp_relay_mode') || url.searchParams.get('udp-relay-mode') || 'native',
      naiveQuic: protocol === 'naive' && (
        naiveQuic || ['1', 'true'].includes(url.searchParams.get('quic') || '')
      ),
      raw: source,
    };

    if (protocol === 'hysteria') {
      profile.uuid = url.searchParams.get('auth') || url.searchParams.get('auth_str') || credential;
      profile.obfsPassword = url.searchParams.get('obfs') || profile.obfsPassword;
      profile.upMbps = Number.parseInt(url.searchParams.get('upmbps') || url.searchParams.get('up_mbps'), 10) || 0;
      profile.downMbps = Number.parseInt(url.searchParams.get('downmbps') || url.searchParams.get('down_mbps'), 10) || 0;
    }
    if (protocol === 'wireguard' && (!profile.privateKey || !profile.peerPublicKey || !profile.localAddress)) return null;
    if (!['hysteria2', 'hysteria', 'tuic', 'wireguard', 'socks', 'naive'].includes(protocol) && !SUPPORTED_TRANSPORTS.has(transport)) return null;
    return profile;
  } catch {
    return null;
  }
}

export function profileShareLink(inputProfile) {
  const original = String(inputProfile?.raw || '').trim();
  if (original) return original;

  const profile = normalizeProfile(inputProfile);
  const endpoint = profileEndpoint(profile);
  const fragment = profileFragment(profile);
  const insecure = profile.insecure ? '1' : '';
  const alpn = Array.isArray(profile.alpn) ? profile.alpn.join(',') : String(profile.alpn || '');
  const commonTransport = [
    ['security', profile.security],
    ['sni', profile.sni],
    ['pbk', profile.pbk],
    ['sid', profile.sid],
    ['fp', profile.fp],
    ['alpn', alpn],
    ['type', profile.transport],
    ['host', profile.hostHeader],
    ['path', profile.path],
    ['serviceName', profile.serviceName],
    ['mode', profile.xhttpMode],
    ['packetEncoding', profile.packetEncoding],
  ];

  if (profile.protocol === 'vmess') {
    const payload = {
      v: '2',
      ps: profile.name || profile.host,
      add: profile.host,
      port: String(profile.port),
      id: profile.uuid,
      aid: String(profile.alterId || 0),
      scy: profile.encryption || 'auto',
      net: profile.transport || 'tcp',
      host: profile.hostHeader || '',
      path: profile.transport === 'grpc' ? (profile.serviceName || '') : (profile.path || ''),
      tls: profile.security || '',
      sni: profile.sni || '',
      fp: profile.fp || '',
      alpn,
      mode: profile.xhttpMode || '',
      packetEncoding: profile.packetEncoding || '',
    };
    return `vmess://${encodeBase64Utf8(JSON.stringify(payload))}`;
  }

  if (profile.protocol === 'shadowsocks') {
    const credentials = encodeBase64Utf8(`${profile.encryption}:${profile.password}`, { urlSafe: true });
    const plugin = profile.plugin
      ? [['plugin', [profile.plugin, profile.pluginOptions].filter(Boolean).join(';')]]
      : [];
    return `ss://${credentials}@${endpoint}${encodeQuery(plugin)}${fragment}`;
  }

  if (profile.protocol === 'socks') {
    const credentials = profile.username || profile.password
      ? `${encodeURIComponent(profile.username || '')}:${encodeURIComponent(profile.password || '')}@`
      : '';
    return `socks5://${credentials}${endpoint}${fragment}`;
  }

  if (profile.protocol === 'naive') {
    const credentials = `${encodeURIComponent(profile.username)}:${encodeURIComponent(profile.password)}`;
    const scheme = profile.naiveQuic ? 'naive+quic' : 'naive+https';
    return `${scheme}://${credentials}@${endpoint}${encodeQuery([
      ['sni', profile.sni],
      ['alpn', alpn],
      ['insecure', insecure],
    ])}${fragment}`;
  }

  if (profile.protocol === 'wireguard') {
    return `wireguard://${endpoint}${encodeQuery([
      ['pk', profile.privateKey],
      ['peer_pk', profile.peerPublicKey],
      ['pre_shared_key', profile.preSharedKey],
      ['local_address', profile.localAddress],
      ['reserved', profile.reserved],
      ['mtu', profile.mtu > 0 ? profile.mtu : ''],
    ])}${fragment}`;
  }

  if (profile.protocol === 'hysteria2') {
    return `hysteria2://${encodeURIComponent(profile.uuid)}@${endpoint}${encodeQuery([
      ['sni', profile.sni],
      ['alpn', alpn],
      ['insecure', insecure],
      ['obfs', profile.obfsType],
      ['obfs-password', profile.obfsPassword],
      ['server_ports', profile.serverPorts],
      ['hop_interval', profile.hopInterval],
      ['hop_interval_max', profile.hopIntervalMax],
      ['up_mbps', profile.upMbps > 0 ? profile.upMbps : ''],
      ['down_mbps', profile.downMbps > 0 ? profile.downMbps : ''],
    ])}${fragment}`;
  }

  if (profile.protocol === 'tuic') {
    const credentials = `${encodeURIComponent(profile.uuid)}:${encodeURIComponent(profile.password || '')}`;
    return `tuic://${credentials}@${endpoint}${encodeQuery([
      ['sni', profile.sni],
      ['alpn', alpn],
      ['insecure', insecure],
      ['congestion_control', profile.congestionControl],
      ['udp_relay_mode', profile.udpRelayMode],
    ])}${fragment}`;
  }

  if (profile.protocol === 'hysteria') {
    return `hysteria://${endpoint}${encodeQuery([
      ['auth', profile.uuid],
      ['sni', profile.sni],
      ['alpn', alpn],
      ['insecure', insecure],
      ['obfs', profile.obfsPassword],
      ['upmbps', profile.upMbps > 0 ? profile.upMbps : ''],
      ['downmbps', profile.downMbps > 0 ? profile.downMbps : ''],
    ])}${fragment}`;
  }

  const credential = encodeURIComponent(profile.uuid);
  const query = profile.protocol === 'vless'
    ? [...commonTransport, ['flow', profile.flow]]
    : [...commonTransport, ['insecure', insecure]];
  return `${profile.protocol}://${credential}@${endpoint}${encodeQuery(query)}${fragment}`;
}

function isIpAddress(host) {
  return /^[0-9.]+$/.test(host) || host.includes(':');
}

function buildTransport(profile) {
  const type = profile.transport || 'tcp';
  if (type === 'tcp' || type === 'raw') return undefined;
  if (!SUPPORTED_TRANSPORTS.has(type)) throw new Error(`Unsupported transport: ${type}`);

  if (type === 'ws') {
    const transport = { type: 'ws', path: profile.path || '/' };
    if (profile.hostHeader) transport.headers = { Host: profile.hostHeader };
    return transport;
  }
  if (type === 'grpc') {
    return { type: 'grpc', service_name: profile.serviceName || '' };
  }
  if (type === 'xhttp') {
    const requestedMode = profile.xhttpMode || WINDOWS.xhttpDefaultMode;
    const transport = {
      type: 'xhttp',
      mode: requestedMode,
      path: profile.path || '/',
    };
    if (profile.hostHeader) transport.host = profile.hostHeader;
    return transport;
  }
  if (type === 'http') {
    const transport = { type: 'http', path: profile.path || '/' };
    if (profile.hostHeader) transport.host = [profile.hostHeader];
    return transport;
  }

  const transport = { type: 'httpupgrade', path: profile.path || '/' };
  if (profile.hostHeader) transport.host = profile.hostHeader;
  return transport;
}

function buildTls(profile, required = false) {
  const security = profile.security || (profile.pbk ? 'reality' : (required ? 'tls' : ''));
  if (!required && security === 'none') return undefined;
  if (!required && !security && !profile.pbk) return undefined;

  const tls = {
    enabled: true,
    server_name: profile.sni || profile.host,
    insecure: Boolean(profile.insecure),
    utls: { enabled: true, fingerprint: profile.fp || 'chrome' },
  };
  if (profile.alpn?.length) tls.alpn = profile.alpn;
  if (profile.pbk) {
    tls.reality = {
      enabled: true,
      public_key: profile.pbk,
      short_id: profile.sid || '',
    };
  }
  return tls;
}

function buildProxyOutbound(profile, tag = TAGS.proxy) {
  if (profile.protocol === 'wireguard') {
    throw new Error('WireGuard is configured as an endpoint');
  }
  const outbound = {
    type: profile.protocol,
    tag,
    server: profile.host,
    server_port: profile.port,
    connect_timeout: PROXY_DIAL.connectTimeout,
  };

  if (profile.protocol === 'vless') {
    outbound.tcp_keep_alive = PROXY_DIAL.tcpKeepAlive;
    outbound.tcp_keep_alive_interval = PROXY_DIAL.tcpKeepAliveInterval;
    outbound.uuid = profile.uuid;
    if (profile.flow) outbound.flow = profile.flow;
    outbound.packet_encoding = profile.packetEncoding || 'xudp';
    const tls = buildTls(profile);
    if (tls) outbound.tls = tls;
    const transport = buildTransport(profile);
    if (transport) outbound.transport = transport;
  } else if (profile.protocol === 'trojan') {
    outbound.tcp_keep_alive = PROXY_DIAL.tcpKeepAlive;
    outbound.tcp_keep_alive_interval = PROXY_DIAL.tcpKeepAliveInterval;
    outbound.password = profile.uuid;
    outbound.tls = buildTls(profile, true);
    const transport = buildTransport(profile);
    if (transport) outbound.transport = transport;
  } else if (profile.protocol === 'hysteria2') {
    outbound.password = profile.uuid;
    outbound.tls = {
      enabled: true,
      server_name: profile.sni || profile.host,
      insecure: Boolean(profile.insecure),
      alpn: profile.alpn?.length ? profile.alpn : ['h3'],
    };
    if (profile.obfsType && profile.obfsPassword) {
      outbound.obfs = { type: profile.obfsType, password: profile.obfsPassword };
    }
    const serverPorts = String(profile.serverPorts || '')
      .split(/[;,]/)
      .map(value => value.trim())
      .filter(Boolean);
    if (serverPorts.length) {
      outbound.server_ports = serverPorts;
      outbound.hop_interval = profile.hopInterval || '10s';
      if (profile.hopIntervalMax) outbound.hop_interval_max = profile.hopIntervalMax;
    }
    if (profile.upMbps > 0) outbound.up_mbps = profile.upMbps;
    if (profile.downMbps > 0) outbound.down_mbps = profile.downMbps;
  } else if (profile.protocol === 'vmess') {
    outbound.uuid = profile.uuid;
    outbound.security = profile.encryption || 'auto';
    outbound.alter_id = profile.alterId || 0;
    if (profile.packetEncoding) outbound.packet_encoding = profile.packetEncoding;
    const tls = buildTls(profile);
    if (tls) outbound.tls = tls;
    const transport = buildTransport(profile);
    if (transport) outbound.transport = transport;
  } else if (profile.protocol === 'shadowsocks') {
    outbound.method = profile.encryption;
    outbound.password = profile.password;
    if (profile.plugin) outbound.plugin = profile.plugin;
    if (profile.pluginOptions) outbound.plugin_opts = profile.pluginOptions;
  } else if (profile.protocol === 'socks') {
    outbound.version = '5';
    if (profile.username) outbound.username = profile.username;
    if (profile.password) outbound.password = profile.password;
  } else if (profile.protocol === 'naive') {
    outbound.tcp_keep_alive = PROXY_DIAL.tcpKeepAlive;
    outbound.tcp_keep_alive_interval = PROXY_DIAL.tcpKeepAliveInterval;
    outbound.username = profile.username;
    outbound.password = profile.password;
    outbound.quic = Boolean(profile.naiveQuic);
    outbound.tls = {
      enabled: true,
      server_name: profile.sni || profile.host,
      insecure: Boolean(profile.insecure),
      ...(profile.alpn?.length ? { alpn: profile.alpn } : {}),
    };
  } else if (profile.protocol === 'tuic') {
    outbound.uuid = profile.uuid;
    outbound.password = profile.password || '';
    outbound.congestion_control = profile.congestionControl || 'cubic';
    outbound.udp_relay_mode = profile.udpRelayMode || 'native';
    outbound.tls = buildTls(profile, true);
  } else if (profile.protocol === 'hysteria') {
    if (profile.uuid) outbound.auth_str = profile.uuid;
    if (profile.obfsPassword) outbound.obfs = profile.obfsPassword;
    if (profile.upMbps > 0) outbound.up_mbps = profile.upMbps;
    if (profile.downMbps > 0) outbound.down_mbps = profile.downMbps;
    outbound.tls = buildTls(profile, true);
  } else {
    throw new Error(`Unsupported protocol: ${profile.protocol}`);
  }

  return outbound;
}

function buildWireGuardEndpoint(profile, tag) {
  const peer = {
    address: profile.host,
    port: profile.port,
    public_key: profile.peerPublicKey,
    allowed_ips: ['0.0.0.0/0', '::/0'],
  };
  if (profile.preSharedKey) peer.pre_shared_key = profile.preSharedKey;
  const reserved = String(profile.reserved || '').split(/[,;]/).map(Number);
  if (reserved.length === 3 && reserved.every(value => Number.isInteger(value) && value >= 0 && value <= 255)) {
    peer.reserved = reserved;
  }
  return {
    type: 'wireguard',
    tag,
    address: String(profile.localAddress).split(/[,;]/).map(value => value.trim()).filter(Boolean),
    private_key: profile.privateKey,
    peers: [peer],
    ...(profile.mtu > 0 ? { mtu: profile.mtu } : {}),
  };
}

function normalizeProfile(inputProfile) {
  const reparsed = inputProfile.raw ? parseProfileLink(inputProfile.raw) : null;
  const profile = reparsed
    ? { ...reparsed, name: inputProfile.name, group: inputProfile.group }
    : { ...inputProfile };
  const hasCredentials = {
    vless: Boolean(profile.uuid),
    trojan: Boolean(profile.uuid),
    hysteria2: Boolean(profile.uuid),
    vmess: Boolean(profile.uuid),
    shadowsocks: Boolean(profile.encryption && profile.password),
    socks: true,
    wireguard: Boolean(profile.privateKey && profile.peerPublicKey && profile.localAddress),
    tuic: Boolean(profile.uuid),
    hysteria: true,
    naive: Boolean(profile.username && profile.password),
  }[profile.protocol];
  if (!profile.host || !profile.port || !hasCredentials || !SUPPORTED_PROTOCOLS.has(profile.protocol)) {
    throw new Error('Incomplete VPN profile');
  }
  return profile;
}

function directServerRule(profile) {
  return isIpAddress(profile.host)
    ? {
        ip_cidr: [profile.host.includes(':') ? profile.host : `${profile.host}/32`],
        action: 'route',
        outbound: TAGS.direct,
      }
    : { domain: [profile.host], action: 'route', outbound: TAGS.direct };
}

function cleanDomains(domains) {
  return [...new Set((domains || []).map(value => String(value).trim().toLowerCase())
    .map(value => value.replace(/^https?:\/\//, '').split('/')[0].split(':')[0])
    .filter(Boolean))];
}

function cleanProcessNames(names) {
  return [...new Set((names || []).map(value => String(value).trim()).filter(Boolean))];
}

export function buildSingBoxConfig(inputProfile, settings = {}) {
  const profile = normalizeProfile(inputProfile);

  const serverIsIp = isIpAddress(profile.host);
  const dnsRules = [];
  if (!serverIsIp) {
    dnsRules.push({
      domain: [profile.host],
      action: 'route',
      server: TAGS.localDns,
    });
  }

  if (settings.adblock) {
    dnsRules.push({
      domain_suffix: AD_DOMAINS,
      action: 'predefined',
      rcode: 'NOERROR',
    });
  }
  dnsRules.push({
    domain_suffix: RUSSIAN_DOMAIN_SUFFIXES,
    action: 'route',
      server: TAGS.localDns,
  });

  const sites = cleanDomains(settings.sitesList);
  if (settings.sitesMode === 'bypass' && sites.length) {
    dnsRules.unshift({ domain_suffix: sites, action: 'route', server: TAGS.localDns });
  } else if (settings.sitesMode === 'only' && sites.length) {
    dnsRules.unshift({ domain_suffix: sites, action: 'route', server: TAGS.remoteDns });
  }

  const config = {
    log: { level: 'warn', timestamp: true },
    dns: {
      servers: [
        {
          type: 'https',
          tag: TAGS.remoteDns,
          server: DNS.remoteServer,
          server_port: DNS.remotePort,
          path: DNS.remotePath,
          detour: TAGS.proxy,
          tls: { enabled: true, server_name: DNS.remoteTlsServerName },
        },
        {
          type: 'udp',
          tag: TAGS.localDns,
          server: DNS.localServer,
          server_port: DNS.localPort,
        },
      ],
      rules: dnsRules,
      final: settings.sitesMode === 'only' && sites.length ? TAGS.localDns : TAGS.remoteDns,
      strategy: DNS.strategy,
    },
    inbounds: [
      {
        type: 'tun',
        tag: TAGS.tun,
        interface_name: WINDOWS.interfaceName,
        address: WINDOWS.addresses,
        auto_route: true,
        strict_route: WINDOWS.strictRoute,
        stack: WINDOWS.stack,
        mtu: Number(settings.mtu) > 0 ? Number(settings.mtu) : WINDOWS.defaultMtu,
      },
    ],
    outbounds: profile.protocol === 'wireguard'
      ? [{ type: 'direct', tag: TAGS.direct }, { type: 'block', tag: TAGS.block }]
      : [
          buildProxyOutbound(profile),
          { type: 'direct', tag: TAGS.direct },
          { type: 'block', tag: TAGS.block },
        ],
    route: {
      rules: [],
      final: TAGS.proxy,
      auto_detect_interface: true,
      default_domain_resolver: { server: TAGS.localDns, strategy: DNS.strategy },
    },
  };
  if (profile.protocol === 'wireguard') {
    config.endpoints = [buildWireGuardEndpoint(profile, TAGS.proxy)];
  }

  const rules = [];
  const blockQuic = settings.quic === true;
  if (blockQuic && ROUTING.blockQuicOnlyWhenEnabled) {
    rules.push({
      process_name: QUIC_BROWSER_PROCESSES,
      network: 'udp',
      port: 443,
      action: 'reject',
      method: 'default',
      no_drop: true,
    });
  }
  rules.push(
    { inbound: [TAGS.tun], action: 'sniff', timeout: '300ms' },
    directServerRule(profile),
    { protocol: 'dns', action: 'hijack-dns' },
    {
      domain_suffix: ROUTING.healthDomainSuffixes,
      action: 'route',
      outbound: TAGS.proxy,
    },
  );
  if (blockQuic && ROUTING.blockQuicOnlyWhenEnabled) {
    rules.push({
      network: 'udp',
      port: 443,
      action: 'reject',
      method: 'default',
      no_drop: true,
    });
  }
  if (settings.adblock) {
    rules.push({ domain_suffix: AD_DOMAINS, action: 'reject' });
  }
  rules.push({
    domain_suffix: RUSSIAN_DOMAIN_SUFFIXES,
    action: 'route',
    outbound: TAGS.direct,
  });
  if (settings.lan) rules.push({ ip_is_private: true, action: 'route', outbound: TAGS.direct });

  const apps = cleanProcessNames(settings.appsList);
  if (apps.length && settings.appsMode === 'bypass') {
    rules.push({ process_name: apps, action: 'route', outbound: TAGS.direct });
  } else if (apps.length && settings.appsMode === 'only') {
    rules.push({ process_name: apps, action: 'route', outbound: TAGS.proxy });
    config.route.final = TAGS.direct;
  }

  if (sites.length && settings.sitesMode === 'bypass') {
    rules.push({ domain_suffix: sites, action: 'route', outbound: TAGS.direct });
  } else if (sites.length && settings.sitesMode === 'only') {
    rules.push({ domain_suffix: sites, action: 'route', outbound: TAGS.proxy });
    config.route.final = TAGS.direct;
  }

  rules.push({ ip_cidr: ['::/0'], action: 'reject' });
  config.route.rules = rules;
  return config;
}

export function buildSelectableSingBoxConfig(inputProfiles, activeIndex = 0, settings = {}, control = {}) {
  if (!Array.isArray(inputProfiles) || inputProfiles.length === 0) {
    throw new Error('At least one VPN profile is required');
  }

  const profiles = inputProfiles.map(normalizeProfile);
  if (!Number.isInteger(activeIndex) || activeIndex < 0 || activeIndex >= profiles.length) {
    throw new Error('Active VPN profile is out of range');
  }

  const config = buildSingBoxConfig(profiles[activeIndex], settings);
  const tags = profiles.map((_, index) => `profile-${index + 1}`);
  const endpoints = profiles
    .map((profile, index) => profile.protocol === 'wireguard'
      ? buildWireGuardEndpoint(profile, tags[index])
      : null)
    .filter(Boolean);
  config.outbounds = [
    {
      type: 'selector',
      tag: TAGS.proxy,
      outbounds: tags,
      default: tags[activeIndex],
      interrupt_exist_connections: true,
    },
    ...profiles.flatMap((profile, index) => profile.protocol === 'wireguard'
      ? []
      : [buildProxyOutbound(profile, tags[index])]),
    { type: 'direct', tag: TAGS.direct },
    { type: 'block', tag: TAGS.block },
  ];
  if (endpoints.length) config.endpoints = endpoints;
  else delete config.endpoints;

  const uniqueServerRules = [];
  const serverRuleKeys = new Set();
  profiles.forEach(profile => {
    const rule = directServerRule(profile);
    const key = JSON.stringify(rule);
    if (!serverRuleKeys.has(key)) {
      serverRuleKeys.add(key);
      uniqueServerRules.push(rule);
    }
  });
  const activeServerRuleKey = JSON.stringify(directServerRule(profiles[activeIndex]));
  const activeServerRuleIndex = config.route.rules.findIndex(
    rule => JSON.stringify(rule) === activeServerRuleKey,
  );
  if (activeServerRuleIndex < 0) {
    throw new Error('Active VPN server route is missing');
  }
  config.route.rules.splice(activeServerRuleIndex, 1, ...uniqueServerRules);

  const dnsServerHosts = new Set(
    config.dns.rules.flatMap(rule => Array.isArray(rule.domain) ? rule.domain : [])
  );
  profiles.forEach(profile => {
    if (!isIpAddress(profile.host) && !dnsServerHosts.has(profile.host)) {
      config.dns.rules.unshift({
        domain: [profile.host],
        action: 'route',
        server: TAGS.localDns,
      });
      dnsServerHosts.add(profile.host);
    }
  });

  if (control.externalController) {
    config.experimental = {
      clash_api: {
        external_controller: control.externalController,
        secret: String(control.secret || ''),
      },
    };
  }

  return config;
}

export function buildRuntimeSingBoxConfig(inputProfiles, activeIndex = 0, settings = {}, autoMode = false) {
  if (!Array.isArray(inputProfiles) || !Number.isInteger(activeIndex) || !inputProfiles[activeIndex]) {
    throw new Error('Active VPN profile is out of range');
  }

  if (!autoMode) {
    return {
      config: buildSelectableSingBoxConfig([inputProfiles[activeIndex]], 0, settings),
      profileIndexes: [activeIndex],
    };
  }

  const profiles = [];
  const profileIndexes = [];
  let runtimeActiveIndex = -1;
  inputProfiles.forEach((profile, index) => {
    try {
      buildSingBoxConfig(profile, settings);
      if (index === activeIndex) runtimeActiveIndex = profiles.length;
      profiles.push(profile);
      profileIndexes.push(index);
    } catch (error) {
      if (index === activeIndex) throw error;
    }
  });
  if (runtimeActiveIndex < 0) throw new Error('Active VPN profile is out of range');

  return {
    config: buildSelectableSingBoxConfig(profiles, runtimeActiveIndex, settings),
    profileIndexes,
  };
}
