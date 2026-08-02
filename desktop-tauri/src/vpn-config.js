import { CORE_CONTRACT } from './generated/core-contract.js';

const SUPPORTED_PROTOCOLS = new Set(CORE_CONTRACT.protocols);
const SUPPORTED_TRANSPORTS = new Set(CORE_CONTRACT.transports);
const TAGS = CORE_CONTRACT.tags;
const DNS = CORE_CONTRACT.dns;
const ROUTING = CORE_CONTRACT.routing;
const WINDOWS = CORE_CONTRACT.platforms.windows;

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
  connectTimeout: '2s',
  tcpKeepAlive: '30s',
  tcpKeepAliveInterval: '15s',
});

function decode(value) {
  return decodeURIComponent(value || '');
}

function parsePort(url) {
  const port = url.port ? Number.parseInt(url.port, 10) : 443;
  return Number.isInteger(port) && port > 0 && port <= 65535 ? port : null;
}

export function parseProfileLink(rawLink) {
  const source = String(rawLink || '').trim();
  if (!source) return null;

  try {
    const link = source.replace(/^hy2:\/\//i, 'hysteria2://');
    const url = new URL(link);
    const protocol = url.protocol.slice(0, -1).toLowerCase();
    const port = parsePort(url);
    const credential = decode(url.username);
    const host = url.hostname.startsWith('[') && url.hostname.endsWith(']')
      ? url.hostname.slice(1, -1)
      : url.hostname;
    if (!SUPPORTED_PROTOCOLS.has(protocol) || !host || !port || !credential) return null;

    const obfsPassword = url.searchParams.get('obfs-password')
      || url.searchParams.get('obfs_password')
      || '';
    const transport = (url.searchParams.get('type') || 'tcp').toLowerCase();
    const requestedXhttpMode = (url.searchParams.get('mode') || '').toLowerCase();
    const profile = {
      protocol,
      name: decode(url.hash.slice(1)).trim() || protocol,
      host,
      port,
      uuid: credential,
      security: (url.searchParams.get('security') || '').toLowerCase(),
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
      raw: link,
    };

    if (protocol !== 'hysteria2' && !SUPPORTED_TRANSPORTS.has(transport)) return null;
    if (transport === 'xhttp' && protocol !== 'vless') return null;
    return profile;
  } catch {
    return null;
  }
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
      // stream-up intermittently stalls multiplexed browser traffic with the
      // current sing-box/Xray pairing. stream-one is also what Android uses.
      mode: requestedMode === 'stream-up' ? 'stream-one' : requestedMode,
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
  } else {
    throw new Error(`Unsupported protocol: ${profile.protocol}`);
  }

  return outbound;
}

function normalizeProfile(inputProfile) {
  const reparsed = inputProfile.raw ? parseProfileLink(inputProfile.raw) : null;
  const profile = reparsed
    ? { ...reparsed, name: inputProfile.name, group: inputProfile.group }
    : { ...inputProfile };
  if (!profile.host || !profile.port || !profile.uuid || !SUPPORTED_PROTOCOLS.has(profile.protocol)) {
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
    .map(value => value.replace(/^(https?:\/\/)?(www\.)?/, '').split('/')[0].split(':')[0])
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
    outbounds: [
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
  config.outbounds = [
    {
      type: 'selector',
      tag: TAGS.proxy,
      outbounds: tags,
      default: tags[activeIndex],
      interrupt_exist_connections: true,
    },
    ...profiles.map((profile, index) => buildProxyOutbound(profile, tags[index])),
    { type: 'direct', tag: TAGS.direct },
    { type: 'block', tag: TAGS.block },
  ];

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
