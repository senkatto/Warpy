import assert from 'node:assert/strict';
import test from 'node:test';

import { classifyClipboardImport } from '../src/clipboard-import.js';

test('recognizes supported profile links', () => {
  for (const scheme of [
    'vless', 'trojan', 'hysteria2', 'hy2', 'vmess', 'ss',
    'socks', 'socks5', 'wg', 'wireguard', 'tuic', 'hysteria',
  ]) {
    assert.deepEqual(
      classifyClipboardImport(`  ${scheme}://example.com/config  `),
      { type: 'profile', value: `${scheme}://example.com/config` },
    );
  }
});

test('recognizes an HTTPS subscription URL', () => {
  assert.deepEqual(
    classifyClipboardImport('https://example.com/subscription?id=42'),
    { type: 'subscription', value: 'https://example.com/subscription?id=42' },
  );
});

test('rejects empty, insecure and unknown clipboard values', () => {
  assert.equal(classifyClipboardImport('  ').type, 'empty');
  assert.equal(classifyClipboardImport('http://example.com/subscription').type, 'invalid');
  assert.equal(classifyClipboardImport('ssh://example.com').type, 'invalid');
  assert.equal(classifyClipboardImport('not a URL').type, 'invalid');
});
