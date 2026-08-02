import assert from 'node:assert/strict';
import test from 'node:test';
import {
  networkProtectionDecision,
  normalizeNetworkContext,
} from '../src/network-policy.js';

const publicNetwork = { trust: 'untrusted', internet: true, generation: 7 };

function decide(overrides = {}) {
  return networkProtectionDecision({
    enabled: true,
    network: publicNetwork,
    backendStatus: 'Stopped',
    hasProfiles: true,
    busy: false,
    blocked: false,
    handledGeneration: -1,
    ...overrides,
  });
}

test('normalizes malformed service snapshots without trusting them', () => {
  assert.deepEqual(normalizeNetworkContext({ trust: 'public', internet: 1, generation: -4 }), {
    trust: 'unknown',
    internet: false,
    generation: 0,
  });
});

test('connects once when an opted-in user is on a public network', () => {
  assert.equal(decide().action, 'connect');
  assert.equal(decide({ handledGeneration: 7 }).reason, 'handled');
});

test('never disconnects or reconnects an already active tunnel', () => {
  const result = decide({ backendStatus: 'Connected' });
  assert.deepEqual([result.action, result.reason], ['none', 'already-protected']);
});

test('does nothing on trusted, offline or unknown networks', () => {
  assert.equal(decide({ network: { trust: 'trusted', internet: true, generation: 1 } }).reason, 'trusted');
  assert.equal(decide({ network: { trust: 'untrusted', internet: false, generation: 2 } }).reason, 'offline');
  assert.equal(decide({ network: { trust: 'unknown', internet: true, generation: 3 } }).reason, 'unknown');
});

test('manual and failure blocks prevent surprise reconnects', () => {
  assert.equal(decide({ blocked: true }).reason, 'blocked');
});

test('default-off policy cannot change connectivity', () => {
  assert.equal(decide({ enabled: false }).reason, 'off');
});
