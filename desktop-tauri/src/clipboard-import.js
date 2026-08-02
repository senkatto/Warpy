const PROFILE_SCHEMES = new Set([
  'vless:', 'trojan:', 'hysteria2:', 'hy2:', 'vmess:', 'ss:',
  'socks:', 'socks5:', 'wg:', 'wireguard:', 'tuic:', 'hysteria:',
]);

export function classifyClipboardImport(value) {
  const text = String(value || '').trim();
  if (!text) return { type: 'empty', value: '' };

  try {
    const url = new URL(text);
    if (PROFILE_SCHEMES.has(url.protocol.toLowerCase())) {
      return { type: 'profile', value: text };
    }
    if (url.protocol === 'https:' && !url.username && !url.password) {
      return { type: 'subscription', value: text };
    }
  } catch {
    // Invalid URLs are handled by the caller with a localized message.
  }

  return { type: 'invalid', value: text };
}
