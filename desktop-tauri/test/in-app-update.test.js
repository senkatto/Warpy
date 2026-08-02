import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('Windows updates install quietly and report progress inside the app', async () => {
  const [configRaw, html, source] = await Promise.all([
    readFile(path.join(projectRoot, 'src-tauri', 'tauri.conf.json'), 'utf8'),
    readFile(path.join(projectRoot, 'src', 'index.html'), 'utf8'),
    readFile(path.join(projectRoot, 'src', 'index.js'), 'utf8'),
  ]);
  const config = JSON.parse(configRaw);

  assert.equal(config.plugins.updater.windows.installMode, 'quiet');
  assert.match(html, /id="update-banner-progress"/);
  assert.match(source, /warpy:\/\/update-progress/);
  assert.match(source, /startAutomaticUpdateChecks\(\)/);
});
