import { parseProfileLink } from './vpn-config.js';
import { CORE_CONTRACT } from './generated/core-contract.js';
import { JSON_SCHEMA, load as loadYaml } from './vendor/js-yaml.mjs';

export const MAX_SUBSCRIPTION_TEXT_LENGTH = 2 * 1024 * 1024;
export const MAX_SUBSCRIPTION_PROFILES = 2000;
export const AUTO_SUBSCRIPTION_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000;

const PROFILE_SCHEMES = /^(?:vless|trojan|hysteria2|hy2|vmess|ss|socks5?|wg|wireguard|tuic|hysteria|naive(?:\+https|\+quic)?):\/\//i;
const ANY_URI_SCHEME = /^[a-z][a-z0-9+.-]*:\/\//i;
const SING_BOX_PROTOCOLS = new Set([
  'vless', 'trojan', 'hysteria2', 'vmess', 'shadowsocks', 'socks', 'wireguard', 'tuic', 'hysteria', 'naive',
]);
const SING_BOX_TRANSPORTS = new Set(['tcp', 'raw', 'ws', 'grpc', 'http', 'httpupgrade', 'xhttp']);
const CLASH_NETWORKS = new Map([
  ['tcp', 'tcp'],
  ['raw', 'raw'],
  ['ws', 'ws'],
  ['grpc', 'grpc'],
  ['http', 'http'],
  ['h2', 'http'],
  ['httpupgrade', 'httpupgrade'],
  ['http-upgrade', 'httpupgrade'],
  ['http_upgrade', 'httpupgrade'],
  ['xhttp', 'xhttp'],
  ['splithttp', 'xhttp'],
  ['split-http', 'xhttp'],
  ['split_http', 'xhttp'],
]);

function normalizeTransport(value) {
  return CLASH_NETWORKS.get(stringValue(value).toLowerCase()) || stringValue(value).toLowerCase();
}

function normalizePayload(value) {
  const text = String(value ?? '').replace(/^\uFEFF/, '').trim();
  if (!text) throw new Error('Подписка пуста');
  if (text.length > MAX_SUBSCRIPTION_TEXT_LENGTH) {
    throw new Error('Подписка превышает допустимый размер');
  }
  if (/^\s*(?:<!doctype\s+html|<html|<head|<body)\b/i.test(text)) {
    throw new Error('Вместо подписки сервер вернул веб-страницу');
  }
  return text;
}

function decodeBase64Utf8(value) {
  const compact = value.replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/');
  if (!compact || !/^[A-Za-z0-9+/]*={0,2}$/.test(compact)) return null;

  const remainder = compact.length % 4;
  if (remainder === 1) return null;
  const padded = compact + '='.repeat((4 - remainder) % 4);
  try {
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return null;
  }
}

function objectValue(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
}

function stringValue(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function secretValue(value) {
  if (typeof value === 'string') return value;
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '';
}

function headerValue(headers, name) {
  const source = objectValue(headers);
  if (!source) return '';
  const entry = Object.entries(source).find(([key]) => key.toLowerCase() === name.toLowerCase());
  const value = entry?.[1];
  if (Array.isArray(value)) return stringValue(value[0]);
  return stringValue(value);
}

function propertyValue(source, keys) {
  const object = objectValue(source);
  if (!object) return undefined;
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(object, key)) return object[key];
  }
  return undefined;
}

function propertyObject(source, keys) {
  return objectValue(propertyValue(source, keys));
}

function firstString(value) {
  return stringValue(Array.isArray(value) ? value[0] : value);
}

function stringList(value) {
  if (Array.isArray(value)) return value.map(stringValue).filter(Boolean);
  const single = stringValue(value);
  return single ? [single] : [];
}

function scalarList(value) {
  const source = Array.isArray(value) ? value : [value];
  return source
    .filter(item => typeof item === 'string' || typeof item === 'number')
    .map(item => String(item).trim())
    .filter(Boolean);
}

function firstSecret(source, keys, trim = false) {
  for (const key of keys) {
    const value = secretValue(propertyValue(source, [key]));
    if (!value) continue;
    const normalized = trim ? value.trim() : value;
    if (normalized) return normalized;
  }
  return '';
}

function singBoxProfileName(outbound, protocol, host) {
  const tag = stringValue(outbound.tag);
  if (tag && tag.length <= 128 && !/[\u0000-\u001f\u007f]/.test(tag)) return tag;
  return `${protocol} ${host}`;
}

function singBoxOutboundToProfile(value) {
  const outbound = objectValue(value);
  if (!outbound) return null;
  const protocol = stringValue(outbound.type).toLowerCase();
  const peer = Array.isArray(outbound.peers) ? objectValue(outbound.peers[0]) : null;
  const host = stringValue(outbound.server) || stringValue(peer?.address);
  const port = Number(outbound.server_port || peer?.port);
  if (
    !SING_BOX_PROTOCOLS.has(protocol) ||
    !host ||
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535
  ) return null;

  const profile = {
    protocol,
    name: singBoxProfileName(outbound, protocol, host),
    host,
    port,
    raw: '',
  };

  const tls = objectValue(outbound.tls);
  const reality = objectValue(tls?.reality);
  if (reality?.enabled === true && stringValue(reality.public_key)) {
    profile.security = 'reality';
    profile.pbk = stringValue(reality.public_key);
    profile.sid = stringValue(reality.short_id);
  } else if (tls?.enabled === true) {
    profile.security = 'tls';
  }
  const serverName = stringValue(tls?.server_name);
  if (serverName) profile.sni = serverName;
  profile.insecure = tls?.insecure === true;
  const alpn = Array.isArray(tls?.alpn) ? tls.alpn.map(stringValue).filter(Boolean) : [];
  if (alpn.length) profile.alpn = alpn;
  const fingerprint = stringValue(objectValue(tls?.utls)?.fingerprint);
  if (fingerprint) profile.fp = fingerprint;

  const transport = objectValue(outbound.transport);
  const transportType = normalizeTransport(transport?.type);
  if (transportType && !SING_BOX_TRANSPORTS.has(transportType)) return null;
  if (transportType && !['vless', 'trojan', 'vmess'].includes(protocol)) return null;
  if (['vless', 'trojan', 'vmess'].includes(protocol)) {
    profile.transport = transportType || 'tcp';
    const path = stringValue(transport?.path);
    if (path) profile.path = path;
    if (transportType === 'grpc') {
      const serviceName = stringValue(transport?.service_name);
      if (serviceName) profile.serviceName = serviceName;
    } else {
      if (transportType === 'xhttp') {
        const mode = stringValue(transport?.mode);
        profile.xhttpMode = ['stream-up', 'stream-one', 'packet-up'].includes(mode)
          ? mode
          : CORE_CONTRACT.platforms.windows.xhttpDefaultMode;
      }
      const hostHeader = headerValue(transport?.headers, 'host')
        || (Array.isArray(transport?.host) ? stringValue(transport.host[0]) : stringValue(transport?.host));
      if (hostHeader) profile.hostHeader = hostHeader;
    }
  }

  if (protocol === 'vless') {
    profile.uuid = stringValue(outbound.uuid);
    profile.flow = stringValue(outbound.flow);
    profile.packetEncoding = stringValue(outbound.packet_encoding);
    if (!profile.uuid) return null;
  } else if (protocol === 'trojan') {
    profile.uuid = secretValue(outbound.password);
    if (!profile.uuid) return null;
  } else if (protocol === 'hysteria2') {
    profile.uuid = secretValue(outbound.password);
    if (!profile.uuid) return null;
    const obfs = objectValue(outbound.obfs);
    const obfsType = stringValue(obfs?.type);
    const obfsPassword = secretValue(obfs?.password);
    if (obfsType && obfsPassword) {
      profile.obfsType = obfsType;
      profile.obfsPassword = obfsPassword;
    }
    profile.serverPorts = stringList(outbound.server_ports).join(',');
    profile.hopInterval = stringValue(outbound.hop_interval);
    profile.hopIntervalMax = stringValue(outbound.hop_interval_max);
    profile.upMbps = Number(outbound.up_mbps) || 0;
    profile.downMbps = Number(outbound.down_mbps) || 0;
  } else if (protocol === 'vmess') {
    profile.uuid = stringValue(outbound.uuid);
    profile.encryption = stringValue(outbound.security) || 'auto';
    profile.alterId = Number(outbound.alter_id) || 0;
    profile.packetEncoding = stringValue(outbound.packet_encoding);
    if (!profile.uuid) return null;
  } else if (protocol === 'shadowsocks') {
    profile.encryption = stringValue(outbound.method);
    profile.password = secretValue(outbound.password);
    profile.plugin = stringValue(outbound.plugin);
    profile.pluginOptions = stringValue(outbound.plugin_opts);
    if (!profile.encryption || !profile.password) return null;
    if (profile.plugin && !['obfs-local', 'v2ray-plugin'].includes(profile.plugin)) return null;
  } else if (protocol === 'socks') {
    profile.username = stringValue(outbound.username);
    profile.password = secretValue(outbound.password);
  } else if (protocol === 'naive') {
    profile.username = stringValue(outbound.username);
    profile.password = secretValue(outbound.password);
    profile.naiveQuic = outbound.quic === true;
    if (!profile.username || !profile.password) return null;
  } else if (protocol === 'wireguard') {
    profile.privateKey = secretValue(outbound.private_key);
    profile.peerPublicKey = stringValue(outbound.peer_public_key) || stringValue(peer?.public_key);
    profile.preSharedKey = secretValue(outbound.pre_shared_key) || secretValue(peer?.pre_shared_key);
    profile.localAddress = stringList(outbound.local_address || outbound.address).join(',');
    profile.reserved = scalarList(outbound.reserved || peer?.reserved).join(',');
    profile.mtu = Number(outbound.mtu) || 0;
    if (!profile.privateKey || !profile.peerPublicKey || !profile.localAddress) return null;
  } else if (protocol === 'tuic') {
    profile.uuid = stringValue(outbound.uuid);
    profile.password = secretValue(outbound.password);
    profile.congestionControl = stringValue(outbound.congestion_control) || 'cubic';
    profile.udpRelayMode = stringValue(outbound.udp_relay_mode) || 'native';
    if (!profile.uuid) return null;
  } else if (protocol === 'hysteria') {
    profile.uuid = secretValue(outbound.auth_str) || secretValue(outbound.auth);
    profile.obfsPassword = secretValue(outbound.obfs);
    profile.upMbps = Number(outbound.up_mbps) || 0;
    profile.downMbps = Number(outbound.down_mbps) || 0;
  }

  return profile;
}

function parseSingBoxJson(value) {
  let config;
  try {
    config = JSON.parse(value);
  } catch {
    return null;
  }
  const root = objectValue(config);
  const outbounds = Array.isArray(config)
    ? config
    : [
      ...(Array.isArray(root?.outbounds) ? root.outbounds : []),
      ...(Array.isArray(root?.endpoints) ? root.endpoints : []),
    ];
  if (!outbounds.length) return null;

  const profiles = [];
  const seen = new Set();
  let skipped = 0;
  for (const outbound of outbounds) {
    const profile = singBoxOutboundToProfile(outbound);
    if (!profile) {
      skipped += 1;
      continue;
    }
    const key = subscriptionProfileKey(profile);
    if (seen.has(key)) continue;
    seen.add(key);
    profiles.push(profile);
    if (profiles.length > MAX_SUBSCRIPTION_PROFILES) {
      throw new Error('В подписке слишком много профилей');
    }
  }
  return { profiles, skipped };
}

function clashProfileName(proxy, protocol, host) {
  const name = stringValue(propertyValue(proxy, ['name']));
  if (name && name.length <= 128 && !/[\u0000-\u001f\u007f]/.test(name)) return name;
  return `${protocol} ${host}`;
}

function applyClashTransport(proxy, protocol, query) {
  const network = stringValue(propertyValue(proxy, ['network'])).toLowerCase();
  if (protocol === 'hysteria2') return !network || network === 'udp';

  const transportType = network ? CLASH_NETWORKS.get(network) : 'tcp';
  if (!transportType) return false;
  if (transportType === 'tcp') return true;

  query.set('type', transportType);
  let options;
  if (transportType === 'ws') {
    options = propertyObject(proxy, ['ws-opts', 'ws_opts']);
  } else if (transportType === 'grpc') {
    options = propertyObject(proxy, ['grpc-opts', 'grpc_opts']);
  } else if (transportType === 'http') {
    options = propertyObject(proxy, network === 'h2'
      ? ['h2-opts', 'h2_opts']
      : ['http-opts', 'http_opts']);
  } else if (transportType === 'xhttp') {
    options = propertyObject(proxy, ['xhttp-opts', 'xhttp_opts']);
  } else {
    options = propertyObject(proxy, ['http-upgrade-opts', 'http_upgrade_opts']);
  }

  const path = firstString(propertyValue(options, ['path']));
  if (path) query.set('path', path);
  if (transportType === 'grpc') {
    const serviceName = stringValue(propertyValue(options, [
      'grpc-service-name',
      'grpc_service_name',
      'service-name',
      'service_name',
    ]));
    if (serviceName) query.set('serviceName', serviceName);
    return true;
  }
  if (transportType === 'xhttp') {
    const mode = stringValue(propertyValue(options, ['mode']));
    query.set('mode', ['stream-up', 'stream-one', 'packet-up'].includes(mode) ? mode : 'stream-up');
  }

  const hostHeader = headerValue(propertyValue(options, ['headers']), 'host')
    || firstString(propertyValue(options, ['host']));
  if (hostHeader) query.set('host', hostHeader);
  return true;
}

function clashProxyToProfile(value) {
  const proxy = objectValue(value);
  if (!proxy) return null;
  const rawType = stringValue(propertyValue(proxy, ['type'])).toLowerCase();
  const protocol = ({ hy2: 'hysteria2', ss: 'shadowsocks', socks5: 'socks', wg: 'wireguard' })[rawType]
    || rawType;
  const host = stringValue(propertyValue(proxy, ['server']));
  const port = Number(propertyValue(proxy, ['port']));
  if (
    !SING_BOX_PROTOCOLS.has(protocol) ||
    !host ||
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535
  ) return null;

  const profile = {
    protocol,
    name: clashProfileName(proxy, protocol, host),
    host,
    port,
    raw: '',
  };
  const reality = propertyObject(proxy, ['reality-opts', 'reality_opts']);
  const publicKey = stringValue(propertyValue(reality, ['public-key', 'public_key']));
  if (publicKey) {
    profile.security = 'reality';
    profile.pbk = publicKey;
    const shortId = stringValue(propertyValue(reality, ['short-id', 'short_id']));
    if (shortId) profile.sid = shortId;
  } else if (
    propertyValue(proxy, ['tls']) === true ||
    ['trojan', 'hysteria2', 'tuic', 'hysteria', 'naive'].includes(protocol)
  ) {
    profile.security = 'tls';
  }

  const serverName = stringValue(propertyValue(proxy, ['servername', 'server-name', 'sni', 'peer']));
  if (serverName) profile.sni = serverName;
  profile.insecure = propertyValue(proxy, ['skip-cert-verify', 'skip_cert_verify']) === true;
  const alpn = stringList(propertyValue(proxy, ['alpn']));
  if (alpn.length) profile.alpn = alpn;
  const fingerprint = stringValue(propertyValue(proxy, [
    'client-fingerprint',
    'client_fingerprint',
    'fingerprint',
  ]));
  if (fingerprint) profile.fp = fingerprint;

  const transportQuery = new URLSearchParams();
  if (!applyClashTransport(proxy, protocol, transportQuery)) return null;
  profile.transport = transportQuery.get('type') || 'tcp';
  profile.path = transportQuery.get('path') || '';
  profile.serviceName = transportQuery.get('serviceName') || '';
  profile.xhttpMode = transportQuery.get('mode') || '';
  profile.hostHeader = transportQuery.get('host') || '';

  if (protocol === 'vless') {
    profile.uuid = firstSecret(proxy, ['uuid'], true);
    profile.flow = stringValue(propertyValue(proxy, ['flow']));
    profile.packetEncoding = stringValue(propertyValue(proxy, ['packet-encoding', 'packet_encoding']));
    if (!profile.uuid) return null;
  } else if (protocol === 'trojan') {
    profile.uuid = firstSecret(proxy, ['password']);
    if (!profile.uuid) return null;
  } else if (protocol === 'hysteria2') {
    profile.uuid = firstSecret(proxy, ['password', 'auth', 'auth-str', 'auth_str']);
    if (!profile.uuid) return null;
    const obfsObject = objectValue(propertyValue(proxy, ['obfs']));
    const obfsType = stringValue(obfsObject?.type || propertyValue(proxy, ['obfs']));
    const obfsPassword = firstSecret(obfsObject, ['password'])
      || firstSecret(proxy, ['obfs-password', 'obfs_password']);
    if (obfsType && obfsPassword) {
      profile.obfsType = obfsType;
      profile.obfsPassword = obfsPassword;
    }
    profile.upMbps = Number(propertyValue(proxy, ['up', 'up-mbps', 'up_mbps'])) || 0;
    profile.downMbps = Number(propertyValue(proxy, ['down', 'down-mbps', 'down_mbps'])) || 0;
    profile.serverPorts = scalarList(propertyValue(proxy, [
      'ports', 'server-ports', 'server_ports', 'mport',
    ])).join(',');
    profile.hopInterval = stringValue(propertyValue(proxy, ['hop-interval', 'hop_interval']));
    profile.hopIntervalMax = stringValue(propertyValue(proxy, ['hop-interval-max', 'hop_interval_max']));
  } else if (protocol === 'vmess') {
    profile.uuid = firstSecret(proxy, ['uuid'], true);
    profile.encryption = stringValue(propertyValue(proxy, ['cipher'])) || 'auto';
    profile.alterId = Number(propertyValue(proxy, ['alterId', 'alter-id', 'alter_id'])) || 0;
    profile.packetEncoding = stringValue(propertyValue(proxy, ['packet-encoding', 'packet_encoding']));
    if (!profile.uuid) return null;
  } else if (protocol === 'shadowsocks') {
    profile.encryption = stringValue(propertyValue(proxy, ['cipher']));
    profile.password = firstSecret(proxy, ['password']);
    profile.plugin = stringValue(propertyValue(proxy, ['plugin']));
    const pluginOptions = propertyValue(proxy, ['plugin-opts', 'plugin_opts']);
    profile.pluginOptions = typeof pluginOptions === 'string'
      ? pluginOptions
      : Object.entries(objectValue(pluginOptions) || {})
        .map(([key, option]) => option === true ? key : `${key}=${option}`)
        .join(';');
    if (!profile.encryption || !profile.password) return null;
    if (profile.plugin && !['obfs-local', 'v2ray-plugin'].includes(profile.plugin)) return null;
  } else if (protocol === 'socks') {
    profile.username = stringValue(propertyValue(proxy, ['username']));
    profile.password = firstSecret(proxy, ['password']);
  } else if (protocol === 'naive') {
    profile.username = stringValue(propertyValue(proxy, ['username']));
    profile.password = firstSecret(proxy, ['password']);
    profile.naiveQuic = propertyValue(proxy, ['quic']) === true;
    if (!profile.username || !profile.password) return null;
  } else if (protocol === 'wireguard') {
    profile.privateKey = firstSecret(proxy, ['private-key', 'private_key']);
    profile.peerPublicKey = stringValue(propertyValue(proxy, ['public-key', 'public_key']));
    profile.preSharedKey = firstSecret(proxy, ['pre-shared-key', 'pre_shared_key', 'psk']);
    const addresses = [
      ...stringList(propertyValue(proxy, ['ip', 'address'])),
      ...stringList(propertyValue(proxy, ['ipv6'])),
    ];
    profile.localAddress = addresses.join(',');
    profile.reserved = scalarList(propertyValue(proxy, ['reserved'])).join(',');
    profile.mtu = Number(propertyValue(proxy, ['mtu'])) || 0;
    if (!profile.privateKey || !profile.peerPublicKey || !profile.localAddress) return null;
  } else if (protocol === 'tuic') {
    profile.uuid = firstSecret(proxy, ['uuid'], true);
    profile.password = firstSecret(proxy, ['password']);
    profile.congestionControl = stringValue(propertyValue(proxy, [
      'congestion-controller', 'congestion_control', 'congestion-control',
    ])) || 'cubic';
    profile.udpRelayMode = stringValue(propertyValue(proxy, ['udp-relay-mode', 'udp_relay_mode'])) || 'native';
    if (!profile.uuid) return null;
  } else if (protocol === 'hysteria') {
    profile.uuid = firstSecret(proxy, ['auth-str', 'auth_str', 'auth', 'password']);
    profile.obfsPassword = firstSecret(proxy, ['obfs']);
    profile.upMbps = Number(propertyValue(proxy, ['up', 'up-mbps', 'up_mbps'])) || 0;
    profile.downMbps = Number(propertyValue(proxy, ['down', 'down-mbps', 'down_mbps'])) || 0;
  }

  return profile;
}

function parseClashYaml(value) {
  let config;
  try {
    config = loadYaml(value, {
      schema: JSON_SCHEMA,
      json: false,
      maxDepth: 20,
      maxTotalMergeKeys: 0,
    });
  } catch {
    return null;
  }
  const proxies = propertyValue(config, ['proxies']);
  if (!Array.isArray(proxies)) return null;

  const profiles = [];
  const seen = new Set();
  let skipped = 0;
  for (const proxy of proxies) {
    const profile = clashProxyToProfile(proxy);
    if (!profile) {
      skipped += 1;
      continue;
    }
    const key = subscriptionProfileKey(profile);
    if (seen.has(key)) continue;
    seen.add(key);
    profiles.push(profile);
    if (profiles.length > MAX_SUBSCRIPTION_PROFILES) {
      throw new Error('В подписке слишком много профилей');
    }
  }
  return { profiles, skipped };
}

function profileSemanticValues(profile) {
  return [
    profile?.protocol,
    profile?.host,
    profile?.port,
    profile?.uuid,
    profile?.username,
    profile?.password,
    profile?.encryption,
    profile?.alterId,
    profile?.security,
    profile?.sni,
    profile?.pbk,
    profile?.sid,
    profile?.flow,
    profile?.fp,
    profile?.transport,
    profile?.path,
    profile?.hostHeader,
    profile?.serviceName,
    profile?.xhttpMode,
    profile?.alpn,
    profile?.packetEncoding,
    profile?.insecure,
    profile?.obfsType,
    profile?.obfsPassword,
    profile?.serverPorts,
    profile?.hopInterval,
    profile?.hopIntervalMax,
    profile?.upMbps,
    profile?.downMbps,
    profile?.plugin,
    profile?.pluginOptions,
    profile?.privateKey,
    profile?.peerPublicKey,
    profile?.preSharedKey,
    profile?.localAddress,
    profile?.reserved,
    profile?.mtu,
    profile?.congestionControl,
    profile?.udpRelayMode,
    profile?.naiveQuic,
  ];
}

export function subscriptionProfileKey(profile) {
  const raw = String(profile?.raw || '').trim().replace(/^hy2:\/\//i, 'hysteria2://');
  if (raw) return raw.split('#', 1)[0];
  return JSON.stringify(profileSemanticValues(profile));
}

function subscriptionProfileContentKey(profile) {
  const raw = String(profile?.raw || '').trim().replace(/^hy2:\/\//i, 'hysteria2://');
  if (raw) return raw;
  return JSON.stringify([...profileSemanticValues(profile), profile?.name]);
}

export function subscriptionProfilesEqual(currentProfiles, importedProfiles) {
  if (!Array.isArray(currentProfiles) || !Array.isArray(importedProfiles)) return false;
  if (currentProfiles.length !== importedProfiles.length) return false;
  return currentProfiles.every((profile, index) =>
    subscriptionProfileContentKey(profile) === subscriptionProfileContentKey(importedProfiles[index])
  );
}

export function subscriptionRefreshDue(subscription, now = Date.now()) {
  const checkedAt = Number(subscription?.lastCheckedAt) || 0;
  if (checkedAt <= 0 || checkedAt > now) return true;
  return now - checkedAt >= AUTO_SUBSCRIPTION_REFRESH_INTERVAL_MS;
}

export function subscriptionDisplayName(value) {
  const url = new URL(String(value || '').trim());
  let fragment = url.hash.slice(1);
  try {
    fragment = decodeURIComponent(fragment);
  } catch {
    fragment = '';
  }
  fragment = fragment.trim();
  if (fragment && fragment.length <= 64 && !/[\u0000-\u001f\u007f]/.test(fragment)) {
    return fragment;
  }

  const parts = url.hostname.replace(/^www\./i, '').split('.').filter(Boolean);
  const genericPrefixes = new Set(['api', 'sub', 'subs', 'subscription', 'panel']);
  const tokenLike = /^(?:[a-f0-9]{8,}|[0-9]{6,})$/i.test(parts[0] || '');
  let label = (genericPrefixes.has(parts[0]?.toLowerCase()) || tokenLike) && parts[1]
    ? parts[1]
    : parts[0];
  label = String(label || 'SUBSCRIPTION').replace(/^with(?=[a-z0-9])/i, '');
  return label.replace(/[-_]+/g, ' ').toUpperCase();
}

export function replaceSubscriptionProfiles(
  existingProfiles,
  subscriptionId,
  importedProfiles,
  groupName,
  legacyGroupName = '',
) {
  if (!Array.isArray(existingProfiles) || !Array.isArray(importedProfiles)) {
    throw new TypeError('Profiles must be arrays');
  }
  if (importedProfiles.length === 0) {
    throw new TypeError('Subscription must contain profiles');
  }
  if (!subscriptionId || !groupName) {
    throw new TypeError('Subscription identity is required');
  }

  const replacement = importedProfiles.map(profile => ({
    ...profile,
    subscriptionId,
    group: groupName,
  }));
  const next = [];
  let inserted = false;

  const legacyGroupKey = String(legacyGroupName).trim().toLocaleLowerCase();
  for (const profile of existingProfiles) {
    const belongsToLegacyGroup = Boolean(
      legacyGroupKey &&
      !profile?.subscriptionId &&
      String(profile?.group || '').trim().toLocaleLowerCase() === legacyGroupKey
    );
    if (profile?.subscriptionId === subscriptionId || belongsToLegacyGroup) {
      if (!inserted) {
        next.push(...replacement);
        inserted = true;
      }
      continue;
    }
    next.push(profile);
  }

  if (!inserted) next.push(...replacement);
  return next;
}

export function findProfileIndexAfterSubscriptionUpdate(
  profiles,
  previousProfile,
  subscriptionId,
) {
  if (!previousProfile || !Array.isArray(profiles)) return -1;
  if (previousProfile.subscriptionId === subscriptionId) {
    const key = subscriptionProfileKey(previousProfile);
    return profiles.findIndex(profile =>
      profile?.subscriptionId === subscriptionId && subscriptionProfileKey(profile) === key
    );
  }

  const key = subscriptionProfileKey(previousProfile);
  return profiles.findIndex(profile => subscriptionProfileKey(profile) === key);
}

function parseUriList(value) {
  const profiles = [];
  const seen = new Set();
  let skipped = 0;

  for (const token of value.split(/\s+/).map(item => item.trim()).filter(Boolean)) {
    if (token.startsWith('#')) continue;
    if (!isProfileLink(token)) {
      if (ANY_URI_SCHEME.test(token)) skipped += 1;
      continue;
    }

    const profile = parseProfileLink(token);
    if (!profile) {
      skipped += 1;
      continue;
    }
    const key = subscriptionProfileKey(profile);
    if (seen.has(key)) continue;
    seen.add(key);
    profiles.push(profile);
    if (profiles.length > MAX_SUBSCRIPTION_PROFILES) {
      throw new Error('В подписке слишком много профилей');
    }
  }

  return { profiles, skipped };
}

function isProfileLink(value) {
  if (PROFILE_SCHEMES.test(value)) return true;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && Boolean(url.username) && Boolean(url.password);
  } catch {
    return false;
  }
}

export function parseSubscriptionPayload(payload) {
  const text = normalizePayload(payload);
  let result = parseUriList(text);
  let format = 'uri-list';

  if (!result.profiles.length) {
    const jsonResult = parseSingBoxJson(text);
    if (jsonResult?.profiles.length) {
      result = jsonResult;
      format = 'sing-box-json';
    }
  }

  if (!result.profiles.length) {
    const clashResult = parseClashYaml(text);
    if (clashResult?.profiles.length) {
      result = clashResult;
      format = 'clash-yaml';
    }
  }

  if (!result.profiles.length) {
    const decoded = decodeBase64Utf8(text);
    if (decoded !== null) {
      const normalizedDecoded = normalizePayload(decoded);
      result = parseUriList(normalizedDecoded);
      format = 'base64';
      if (!result.profiles.length) {
        const jsonResult = parseSingBoxJson(normalizedDecoded);
        if (jsonResult?.profiles.length) {
          result = jsonResult;
          format = 'base64-sing-box-json';
        }
      }
      if (!result.profiles.length) {
        const clashResult = parseClashYaml(normalizedDecoded);
        if (clashResult?.profiles.length) {
          result = clashResult;
          format = 'base64-clash-yaml';
        }
      }
    }
  }

  if (!result.profiles.length) {
    throw new Error('Подписка не содержит поддерживаемых профилей');
  }
  return { ...result, format };
}
