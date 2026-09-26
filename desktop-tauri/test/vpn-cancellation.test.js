import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../src/index.js', import.meta.url), 'utf8');
const controls = source.slice(source.indexOf('let vpnOperationTail ='), source.indexOf('async function checkStatus()'));

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function harness() {
  const start = deferred();
  const dispatched = deferred();
  const calls = [];
  const state = {
    profiles: [{}], status: 'stopped', commandPending: null, connectAttempt: 0,
    startCommandAttempt: 0, startCommandDispatched: false,
  };
  const context = vm.createContext({
    S: state, errorMsg: {}, syncUI() {}, show() {}, restorePreferredProfileSelection() {},
    stopTimers() {}, scheduleAutomaticSubscriptionRefresh() {},
    makeConfig: () => ({ config: '{}', profileKeys: ['p'] }),
    getVpnRuntimeSnapshot: async () => ({ status: 'Stopped' }),
    applyServiceConnectionSnapshot: async () => { state.status = 'stopped'; },
    t: key => key, setTimeout,
    invoke: async command => {
      calls.push(command);
      if (command === 'start_vpn') { dispatched.resolve(); await start.promise; }
      if (command === 'cancel_vpn_start') start.resolve();
    },
  });
  vm.runInContext(controls, context);
  return { context, state, calls, dispatched, start };
}

test('second click cancels a dispatched start before the service reports connecting', async () => {
  const h = harness();
  const first = h.context.toggleVpn();
  await h.dispatched.promise;
  const second = h.context.toggleVpn();
  await Promise.resolve();
  try {
    assert.ok(h.calls.includes('cancel_vpn_start'));
  } finally {
    h.start.resolve();
    await Promise.all([first, second]);
  }
  assert.equal(h.calls.filter(c => c === 'start_vpn').length, 1);
  assert.equal(h.state.commandPending, null);
});

test('stop invalidates a start still waiting in the command queue', async () => {
  const h = harness();
  const start = h.context.startVpn();
  const stop = h.context.stopVpn();
  h.start.resolve();
  await Promise.all([start, stop]);
  assert.equal(h.calls.filter(c => c === 'start_vpn').length, 0);
  assert.equal(h.state.commandPending, null);
});

test('a click while stopping does not enqueue another start', async () => {
  const h = harness();
  h.state.commandPending = 'stop';
  h.start.resolve();
  await h.context.toggleVpn();
  assert.deepEqual(h.calls, []);
});
