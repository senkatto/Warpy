import { parseProfileLink } from './vpn-config.js';
import { JSON_SCHEMA, load as loadYaml } from './vendor/js-yaml.mjs';

export const MAX_SUBSCRIPTION_TEXT_LENGTH = 2 * 1024 * 1024;
export const MAX_SUBSCRIPTION_PROFILES = 2000;
export const AUTO_SUBSCRIPTION_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000;

const PROFILE_SCHEMES = /^(?:vless|trojan|hysteria2|hy2):\/\//i;
const ANY_URI_SCHEME = /^[a-z][a-z0-9+.-]*:\/\//i;
const SING_BOX_PROTOCOLS = new Set(['vless', 'trojan', 'hysteria2']);
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
  ['xhttp', 'xhttp'],
]);

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
  return typeof value === 'string' ? value : '';
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

function firstSecret(source, keys, trim = false) {
  for (const key of keys) {
    const value = propertyValue(source, [key]);
    if (typeof value !== 'string') continue;
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
  const host = stringValue(outbound.server);
  const port = Number(outbound.server_port);
  const credential = protocol === 'vless'
    ? stringValue(outbound.uuid)
    : secretValue(outbound.password);
  if (
    !SING_BOX_PROTOCOLS.has(protocol) ||
    !host ||
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535 ||
    !credential
  ) return null;

  const hostForUrl = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
  let url;
  try {
    url = new URL(`${protocol}://placeholder@${hostForUrl}:${port}`);
  } catch {
    return null;
  }
  url.username = credential;
  const query = url.searchParams;
  const tls = objectValue(outbound.tls);
  const reality = objectValue(tls?.reality);
  if (reality?.enabled === true && stringValue(reality.public_key)) {
    query.set('security', 'reality');
    query.set('pbk', stringValue(reality.public_key));
    const shortId = stringValue(reality.short_id);
    if (shortId) query.set('sid', shortId);
  } else if (tls?.enabled === true) {
    query.set('security', 'tls');
  }
  const serverName = stringValue(tls?.server_name);
  if (serverName) query.set('sni', serverName);
  if (tls?.insecure === true) query.set('insecure', '1');
  const alpn = Array.isArray(tls?.alpn) ? tls.alpn.map(stringValue).filter(Boolean) : [];
  if (alpn.length) query.set('alpn', alpn.join(','));
  const fingerprint = stringValue(objectValue(tls?.utls)?.fingerprint);
  if (fingerprint) query.set('fp', fingerprint);

  if (protocol === 'vless') {
    const flow = stringValue(outbound.flow);
    const packetEncoding = stringValue(outbound.packet_encoding);
    if (flow) query.set('flow', flow);
    if (packetEncoding) query.set('packetEncoding', packetEncoding);
  }

  const transport = objectValue(outbound.transport);
  const transportType = stringValue(transport?.type).toLowerCase();
  if (transportType && !SING_BOX_TRANSPORTS.has(transportType)) return null;
  if (transportType && SING_BOX_TRANSPORTS.has(transportType) && transportType !== 'tcp') {
    query.set('type', transportType);
    const path = stringValue(transport.path);
    if (path) query.set('path', path);
    if (transportType === 'grpc') {
      const serviceName = stringValue(transport.service_name);
      if (serviceName) query.set('serviceName', serviceName);
    } else {
      if (transportType === 'xhttp') {
        const mode = stringValue(transport.mode);
        query.set('mode', ['stream-up', 'stream-one', 'packet-up'].includes(mode) ? mode : 'stream-up');
      }
      const hostHeader = headerValue(transport.headers, 'host')
        || (Array.isArray(transport.host) ? stringValue(transport.host[0]) : stringValue(transport.host));
      if (hostHeader) query.set('host', hostHeader);
    }
  }

  if (protocol === 'hysteria2') {
    const obfs = objectValue(outbound.obfs);
    const obfsType = stringValue(obfs?.type);
    const obfsPassword = secretValue(obfs?.password);
    if (obfsType && obfsPassword) {
      query.set('obfs', obfsType);
      query.set('obfs-password', obfsPassword);
    }
  }

  url.hash = singBoxProfileName(outbound, protocol, host);
  return parseProfileLink(url.href);
}

function parseSingBoxJson(value) {
  let config;
  try {
    config = JSON.parse(value);
  } catch {
    return null;
  }
  const outbounds = Array.isArray(config) ? config : objectValue(config)?.outbounds;
  if (!Array.isArray(outbounds)) return null;

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
  const protocol = rawType === 'hy2' ? 'hysteria2' : rawType;
  const host = stringValue(propertyValue(proxy, ['server']));
  const port = Number(propertyValue(proxy, ['port']));
  const credential = protocol === 'vless'
    ? firstSecret(proxy, ['uuid'], true)
    : firstSecret(proxy, protocol === 'hysteria2'
      ? ['password', 'auth', 'auth-str', 'auth_str']
      : ['password']);
  if (
    !SING_BOX_PROTOCOLS.has(protocol) ||
    !host ||
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535 ||
    !credential
  ) return null;

  const hostForUrl = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
  let url;
  try {
    url = new URL(`${protocol}://placeholder@${hostForUrl}:${port}`);
  } catch {
    return null;
  }
  url.username = credential;
  const query = url.searchParams;
  const reality = propertyObject(proxy, ['reality-opts', 'reality_opts']);
  const publicKey = stringValue(propertyValue(reality, ['public-key', 'public_key']));
  if (publicKey) {
    query.set('security', 'reality');
    query.set('pbk', publicKey);
    const shortId = stringValue(propertyValue(reality, ['short-id', 'short_id']));
    if (shortId) query.set('sid', shortId);
  } else if (propertyValue(proxy, ['tls']) === true || protocol !== 'vless') {
    query.set('security', 'tls');
  }

  const serverName = stringValue(propertyValue(proxy, ['servername', 'server-name', 'sni', 'peer']));
  if (serverName) query.set('sni', serverName);
  if (propertyValue(proxy, ['skip-cert-verify', 'skip_cert_verify']) === true) {
    query.set('insecure', '1');
  }
  const alpn = stringList(propertyValue(proxy, ['alpn']));
  if (alpn.length) query.set('alpn', alpn.join(','));
  const fingerprint = stringValue(propertyValue(proxy, [
    'client-fingerprint',
    'client_fingerprint',
    'fingerprint',
  ]));
  if (fingerprint) query.set('fp', fingerprint);

  if (protocol === 'vless') {
    const flow = stringValue(propertyValue(proxy, ['flow']));
    const packetEncoding = stringValue(propertyValue(proxy, ['packet-encoding', 'packet_encoding']));
    if (flow) query.set('flow', flow);
    if (packetEncoding) query.set('packetEncoding', packetEncoding);
  }
  if (!applyClashTransport(proxy, protocol, query)) return null;

  if (protocol === 'hysteria2') {
    const obfsObject = objectValue(propertyValue(proxy, ['obfs']));
    const obfsType = stringValue(obfsObject?.type || propertyValue(proxy, ['obfs']));
    const obfsPassword = firstSecret(obfsObject, ['password'])
      || firstSecret(proxy, ['obfs-password', 'obfs_password']);
    if (obfsType && obfsPassword) {
      query.set('obfs', obfsType);
      query.set('obfs-password', obfsPassword);
    }
  }

  url.hash = clashProfileName(proxy, protocol, host);
  return parseProfileLink(url.href);
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

export function subscriptionProfileKey(profile) {
  const raw = String(profile?.raw || '').trim().replace(/^hy2:\/\//i, 'hysteria2://');
  if (raw) return raw.split('#', 1)[0];
  return [profile?.protocol, profile?.host, profile?.port, profile?.uuid]
    .map(value => String(value ?? ''))
    .join('\u0000');
}

function subscriptionProfileContentKey(profile) {
  const raw = String(profile?.raw || '').trim().replace(/^hy2:\/\//i, 'hysteria2://');
  if (raw) return raw;
  return JSON.stringify([
    profile?.protocol,
    profile?.host,
    profile?.port,
    profile?.uuid,
    profile?.password,
    profile?.security,
    profile?.type,
    profile?.sni,
    profile?.pbk,
    profile?.sid,
    profile?.flow,
    profile?.path,
    profile?.hostHeader,
    profile?.serviceName,
    profile?.alpn,
    profile?.obfs,
    profile?.obfsPassword,
    profile?.insecure,
    profile?.name,
  ]);
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

  const key = String(previousProfile.raw || '') || [
    previousProfile.protocol,
    previousProfile.host,
    previousProfile.port,
    previousProfile.uuid,
  ].join('\u0000');
  return profiles.findIndex(profile => {
    const profileKey = String(profile?.raw || '') || [
      profile?.protocol,
      profile?.host,
      profile?.port,
      profile?.uuid,
    ].join('\u0000');
    return profileKey === key;
  });
}

function parseUriList(value) {
  const profiles = [];
  const seen = new Set();
  let skipped = 0;

  for (const token of value.split(/\s+/).map(item => item.trim()).filter(Boolean)) {
    if (token.startsWith('#')) continue;
    if (!PROFILE_SCHEMES.test(token)) {
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
