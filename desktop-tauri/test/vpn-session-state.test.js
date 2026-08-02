import assert from 'node:assert/strict';
import test from 'node:test';
import {
  isTransientServiceStatus,
  normalizeServiceConnectionStatus,
  reconcileServiceConnection,
} from '../src/vpn-session-state.js';

test('service status is the only source of canonical connection state', () => {
  assert.equal(normalizeServiceConnectionStatus('Connected:profile_0'), 'connected');
  assert.equal(normalizeServiceConnectionStatus('Recovering'), 'connecting');
  assert.equal(normalizeServiceConnectionStatus('Stopped', false), 'stopped');
  assert.equal(normalizeServiceConnectionStatus('Stopped', true), 'error');
  assert.equal(normalizeServiceConnectionStatus('Error:core failed'), 'error');
});

test('reconciliation preserves start time only for the same connected session', () => {
  const connected = reconcileServiceConnection(
    { status: 'connecting', connectedAt: 0 },
    { status: 'Connected:profile_0', desiredRunning: true },
    1234,
  );
  assert.deepEqual(connected, {
    status: 'connected',
    connectedAt: 1234,
    changed: true,
    serviceStatus: 'Connected:profile_0',
  });

  const stopped = reconcileServiceConnection(
    connected,
    { status: 'Stopped', desiredRunning: false },
  );
  assert.equal(stopped.status, 'stopped');
  assert.equal(stopped.connectedAt, 0);
  assert.equal(isTransientServiceStatus('Starting'), true);
});
