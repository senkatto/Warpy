import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../src-tauri/native-generated/ui.js', import.meta.url), 'utf8');
async function nativeShell(settings = {}, runtimeStatus = 'Stopped') {
  let now = 0;
  const errors = [], calls = [], history = [], network = [], windows = [];
  const context = vm.createContext({
    __nativeNow: () => now,
    __nativeLog: value => errors.push(value),
    __nativeUrl: value => {
      const url = new URL(value); const base = new URL(value); base.hash = '';
      return JSON.stringify({ base: base.href, hash: url.hash, protocol: url.protocol, hostname: url.hostname, username: url.username, password: url.password, port: url.port, search: url.search, pathname: url.pathname, origin: url.origin });
    },
    __nativeBase64: (value, encode) => encode ? Buffer.from(value, 'binary').toString('base64') : Buffer.from(value, 'base64').toString('binary'),
    __nativeUuid: () => '12345678-1234-4234-8234-123456789012',
    __nativeWindow: command => windows.push(command),
    __nativeFetch: (id, url, upload) => network.push({ id, url, upload }),
    __nativeFetchCancel: () => {},
    __nativeInvoke: (id, name, args) => { const call = {id,name,args:JSON.parse(args)}; calls.push(call); history.push(call); },
  });
  vm.runInContext(source, context);
  async function settle() {
    for (let wave = 0; wave < 45; wave++) {
      const waiting = calls.splice(0);
      for (const call of waiting) {
        let value = null;
        if (call.name === 'load_settings') value = JSON.stringify(settings);
        else if (call.name === 'get_vpn_runtime_snapshot') value = { status: runtimeStatus, desiredRunning: runtimeStatus!=='Stopped' };
        else if (call.name === 'get_vpn_status') value = runtimeStatus;
        else if (call.name === 'get_kill_switch_status') value = 'Off';
        else if (call.name === 'get_app_version') value = '1.0.7';
        else if (call.name === 'probe_profiles') value = [];
        else if (call.name === 'get_running_processes') value = ['native-fixture.exe'];
        else if (call.name === 'stop_vpn' || call.name === 'cancel_vpn_start') runtimeStatus = 'Stopped';
        else if (call.name === 'native_clipboard_read') value = ' вставка';
        context.__nativeResolve(call.id, true, value);
      }
      await new Promise(resolve => setImmediate(resolve));
    }
  }
  await settle();
  return { context, errors, calls, history, network, windows, settle,
    setRuntimeStatus: value => { runtimeStatus = value; },
    run: code => vm.runInContext(code, context),
    advance: async milliseconds => { now += milliseconds; context.__nativeTick(); await settle(); },
    scene: () => JSON.parse(context.__nativeBuildScene()),
  };
}
const settings = { profiles: [{ protocol: 'vless', name: 'SNKT', host: '192.0.2.1', port: 2053, uuid: '00000000-0000-4000-8000-000000000001', security: 'reality', pbk: 'test', sni: 'example.com' }], active: 0, lang: 'ru' };

test('native controller initializes and renders the original dimensions without a browser', async () => {
  const shell = await nativeShell(settings);
  const scene = shell.scene();
  assert.ok(scene.ops.some(op => op.kind === 'rect' && op.w === 380 && op.h === 680 && op.radius === 36));
  assert.ok(scene.ops.some(op => op.kind === 'text' && op.text === 'SNKT'));
  assert.ok(scene.ops.some(op => op.kind === 'svg' && op.source.includes('1869.79')));
  assert.equal(shell.errors.some(error => /TypeError|ReferenceError/.test(error)), false, shell.errors.join('\n'));
});

test('native ID lookup avoids selector traversal and follows tree and ID changes', async () => {
  const shell = await nativeShell(settings);
  shell.run(`
    const power = document.getElementById('power-btn');
    document.querySelector = () => { throw new Error('Full tree selector traversal'); };
    for (let i = 0; i < 100; i++) {
      if (document.getElementById('power-btn') !== power) throw new Error('Lost static ID');
    }
    const parent = document.createElement('div');
    const first = document.createElement('span'); first.id = 'native-test-id';
    const second = document.createElement('span'); second.id = 'native-test-id';
    parent.append(first, second); document.body.append(parent);
    if (document.getElementById('native-test-id') !== first) throw new Error('Wrong duplicate ID order');
    first.remove();
    if (document.getElementById('native-test-id') !== second) throw new Error('Stale removed ID');
    second.setAttribute('id', 'native-renamed-id');
    if (document.getElementById('native-test-id') !== null) throw new Error('Stale renamed ID');
    if (document.getElementById('native-renamed-id') !== second) throw new Error('Missing renamed ID');
    parent.replaceChildren(first);
    if (document.getElementById('native-renamed-id') !== null) throw new Error('Stale replaced child');
    if (document.getElementById('native-test-id') !== first) throw new Error('Missing reattached child');
    parent.removeChild(first);
    if (document.getElementById('native-test-id') !== null) throw new Error('Stale detached child');
    parent.remove();
  `);
});

test('native first selector match stops before unrelated subtrees', async () => {
  const shell = await nativeShell(settings);
  shell.run(`
    const parent = document.createElement('div');
    const first = document.createElement('span'); first.id = 'native-first-match';
    const later = document.createElement('div');
    Object.defineProperty(later, 'children', { get() { throw new Error('Visited later subtree'); } });
    parent.append(first, later);
    if (parent.querySelector('#native-first-match') !== first) throw new Error('Wrong first match');
  `);
});

test('native SVG cache preserves changed icon markup and colors', async () => {
  const shell = await nativeShell(settings);
  shell.scene();
  shell.run(`
    const svg = nativeLogo.querySelector('svg');
    const originalMarkup = nativeMarkup;
    nativeMarkup = () => { throw new Error('Rebuilt unchanged SVG'); };
    nativeSvg(svg, 0, 0, 109, 20, '#fff');
    nativeMarkup = originalMarkup;
    svg.setAttribute('data-native-test', 'changed');
    nativeSvg(svg, 0, 0, 109, 20, '#123456');
    if (!nativeScene.ops.at(-1).source.includes('data-native-test="changed"')) throw new Error('Stale SVG attributes');
    if (nativeScene.ops.at(-1).source.includes('currentColor')) throw new Error('Stale SVG color');
  `);
});

test('every original dialog is reachable and produces a native scene', async () => {
  const shell = await nativeShell(settings);
  for (const overlay of ['profiles', 'settings', 'language', 'share', 'add', 'speedtest', 'running-apps', 'confirm', 'message', 'settings-unsaved']) {
    shell.run(`document.getElementById('overlay-${overlay}').classList.remove('hidden')`);
    assert.doesNotThrow(() => shell.scene(), overlay);
    shell.run(`document.getElementById('overlay-${overlay}').classList.add('hidden')`);
  }
});

test('profile actions bubble to their original controller and settings inputs emit change', async () => {
  const shell = await nativeShell(settings);
  shell.run("document.getElementById('btn-settings').dispatchEvent(new Event('click'))");
  await shell.settle();
  const scene = shell.scene();
  const toggle = scene.hits.find(hit => hit.kind === 'toggle');
  assert.ok(toggle);
  const before = shell.run(`nativeElements.get(${toggle.uid}).checked`);
  shell.context.__nativePointer('click', toggle.x + 12, toggle.y + 12);
  assert.equal(shell.run(`nativeElements.get(${toggle.uid}).checked`), !before);
  assert.equal(shell.run("document.getElementById('btn-save-settings').disabled"), false);
  shell.run("document.getElementById('close-settings').dispatchEvent(new Event('click'))");
  assert.equal(shell.run("document.getElementById('overlay-settings-unsaved').classList.contains('hidden')"), false);
});

test('clipboard sharing generates the same profile URI as a native SVG QR', async () => {
  const shell = await nativeShell(settings);
  shell.run("document.getElementById('btn-profiles').dispatchEvent(new Event('click'))");
  await shell.settle();
  shell.run("document.getElementById('profile-list').querySelector('.p-share').dispatchEvent(new Event('click'))");
  assert.match(shell.run("document.getElementById('share-qr').src"), /^data:image\/svg\+xml,/);
  assert.match(shell.run("document.getElementById('share-link').value"), /^vless:\/\//);
  assert.ok(shell.scene().ops.some(op => op.kind === 'svg' && op.w === 150));
});

test('hidden native window stops particle frames and metric traffic', async () => {
  const shell = await nativeShell(settings);
  shell.context.__nativeVisibility(false);
  await shell.advance(60_000);
  assert.equal(shell.network.length, 0);
  assert.equal(shell.run("document.hidden"), true);
  assert.ok(shell.run("[...nativeTimers.values()].every(timer => timer.at > performance.now())"));
});

test('native particles use a retained animation without continuous controller frames', async () => {
  const shell = await nativeShell(settings, 'Connected');
  await shell.advance(34);
  const frame = shell.scene().ops.find(op => op.kind === 'particles');
  assert.ok(frame);
  assert.equal(frame.connected, true);
  assert.equal(frame.connected_at, 0);
  const particleCanvas = shell.run("document.getElementById('particles').nativeCanvas");
  const retained = particleCanvas.ops;
  await shell.advance(34);
  assert.equal(particleCanvas.ops, retained);
  shell.run("document.getElementById('overlay-settings').classList.remove('hidden')");
  assert.equal(shell.scene().ops.some(op => op.kind === 'particles' || op.spin), false);
});

test('native connecting spinner advances in the painter and cancellation clears particles', async () => {
  const shell = await nativeShell(settings, 'Starting');
  await shell.advance(34);
  assert.ok(shell.scene().ops.some(op => op.spin));
  assert.equal(shell.scene().ops.find(op => op.kind === 'particles').connected, false);
  shell.context.__nativePointer('click', 210, 274);
  await shell.settle();
  await shell.advance(40);
  assert.equal(shell.scene().ops.some(op => op.kind === 'particles' || op.spin), false);
});

test('native particles retain their phase across connecting, connected and visibility changes', async () => {
  const shell = await nativeShell(settings, 'Starting');
  await shell.advance(34);
  const started = shell.scene().ops.find(op => op.kind === 'particles').started;
  shell.setRuntimeStatus('Connected');
  await shell.advance(2100);
  await shell.advance(34);
  const connected = shell.scene().ops.find(op => op.kind === 'particles');
  assert.equal(connected.started, started);
  assert.equal(connected.connected, true);
  assert.ok(connected.connected_at > 0);
  shell.context.__nativeVisibility(false);
  await shell.advance(10_000);
  shell.context.__nativeVisibility(true);
  await shell.advance(34);
  const resumed = shell.scene().ops.find(op => op.kind === 'particles');
  assert.equal(resumed.started, started);
  assert.equal(resumed.connected_at, connected.connected_at);
});

test('native network health check preserves received byte count and cancellation', async () => {
  const shell = await nativeShell(settings);
  shell.run("globalThis.healthBytes = -1; fetch('https://speed.cloudflare.com/__down?bytes=1024').then(response=>response.arrayBuffer()).then(buffer=>healthBytes=buffer.byteLength)");
  const id = shell.network.at(-1).id;
  shell.context.__nativeFetchEvent(id, {status:200});
  shell.context.__nativeFetchEvent(id, {bytes:512});
  shell.context.__nativeFetchEvent(id, {bytes:512});
  shell.context.__nativeFetchEvent(id, {done:true});
  await shell.settle();
  assert.equal(shell.run('healthBytes'),1024);
  shell.run("globalThis.aborted=''; const cancel=new AbortController(); fetch('https://speed.cloudflare.com/__down',{signal:cancel.signal}).catch(error=>aborted=error.name); cancel.abort()");
  await shell.settle();
  assert.equal(shell.run('aborted'),'AbortError');
});

test('native settings support keyboard toggles and editing at the caret', async () => {
  const shell = await nativeShell(settings);
  shell.run("document.getElementById('btn-settings').dispatchEvent(new Event('click'))");
  await shell.settle(); shell.scene();
  shell.run("document.getElementById('s-quic').focus()");
  const before = shell.run("document.getElementById('s-quic').checked");
  await shell.context.__nativeKey(' ');
  assert.equal(shell.run("document.getElementById('s-quic').checked"),!before);
  shell.run("document.getElementById('s-mtu').value='1400'; document.getElementById('s-mtu').focus()");
  await shell.context.__nativeKey('Home'); await shell.context.__nativeKey('Delete');
  await shell.context.__nativeKey('1');
  assert.equal(shell.run("document.getElementById('s-mtu').value"),'1400');
  await shell.context.__nativeKey('a',true); await shell.context.__nativeKey('9');
  assert.equal(shell.run("document.getElementById('s-mtu').value"),'9');
});

test('modal scenes block background buttons and preserve share dialog dimensions', async () => {
  const shell = await nativeShell(settings);
  shell.run("document.getElementById('btn-profiles').dispatchEvent(new Event('click'))");
  await shell.settle(); shell.scene();
  shell.run("document.getElementById('profile-list').querySelector('.p-share').dispatchEvent(new Event('click'))");
  const scene = shell.scene();
  assert.ok(scene.ops.some(op=>op.kind==='rect' && op.w===340 && op.h===449));
  assert.equal(scene.hits.some(hit=>hit.uid===shell.run("document.getElementById('btn-settings').uid")),false);
  assert.ok(scene.hits.some(hit=>hit.uid===shell.run("document.getElementById('btn-copy-share').uid")));
});

test('native power button cancels a connection already in progress', async () => {
  const shell = await nativeShell(settings,'Starting');
  shell.scene(); shell.history.length = 0;
  shell.context.__nativePointer('click',210,274);
  await shell.settle();
  assert.ok(shell.history.some(call=>call.name==='cancel_vpn_start'),JSON.stringify({history:shell.history,errors:shell.errors,status:shell.run("document.getElementById('power-btn').className")}));
  assert.ok(shell.history.some(call=>call.name==='stop_vpn'));
  assert.equal(shell.history.some(call=>call.name==='start_vpn'),false);
});

test('native running-app selection updates the existing controller state', async () => {
  const shell = await nativeShell(settings);
  shell.run("document.getElementById('btn-settings').dispatchEvent(new Event('click')); document.getElementById('btn-open-tunneling').dispatchEvent(new Event('click')); document.getElementById('btn-app-running').dispatchEvent(new Event('click'))");
  await shell.settle();
  const scene = shell.scene();
  assert.ok(shell.run("document.getElementById('running-apps-list').querySelector('.app-item')"),JSON.stringify({history:shell.history,errors:shell.errors,list:shell.run("document.getElementById('running-apps-list').textContent")}));
  const uid = shell.run("document.getElementById('running-apps-list').querySelector('.app-item').uid");
  const row = scene.hits.find(hit=>hit.uid===uid);
  assert.ok(row);
  shell.context.__nativePointer('click',row.x+50,row.y+16);
  shell.run("document.getElementById('confirm-running-apps').dispatchEvent(new Event('click'))");
  assert.equal(shell.run("document.getElementById('s-apps-list').value"),'native-fixture.exe');
});
