import test from 'node:test';
import assert from 'node:assert/strict';
import {
  autoSwitchNotificationEvent,
  connectivityNotificationTransition,
  createNotificationDedupe,
} from '../src/significant-notifications.js';

test('emits one notification only after a real recovery outcome', () => {
  const recovering = connectivityNotificationTransition('connected', 'Recovering', false);
  assert.deepEqual(recovering, { recoveryPending: true, event: null });

  const restored = connectivityNotificationTransition(
    'connecting',
    'Connected',
    recovering.recoveryPending,
  );
  assert.deepEqual(restored, { recoveryPending: false, event: 'restored' });

  const failed = connectivityNotificationTransition(
    'connecting',
    'Error',
    recovering.recoveryPending,
  );
  assert.deepEqual(failed, { recoveryPending: false, event: 'failed' });
});

test('manual connection states do not produce recovery notifications', () => {
  assert.deepEqual(
    connectivityNotificationTransition('connecting', 'Connected', false),
    { recoveryPending: false, event: null },
  );
  assert.deepEqual(
    connectivityNotificationTransition('error', 'Stopped', false),
    { recoveryPending: false, event: null },
  );
});

test('auto switch events are handled once and unknown outcomes stay silent', () => {
  const switched = { outcome: 'switched', observedAtMs: 200 };
  assert.deepEqual(autoSwitchNotificationEvent(switched, 100), {
    observedAt: 200,
    kind: 'auto-switched',
  });
  assert.equal(autoSwitchNotificationEvent(switched, 200), null);
  assert.deepEqual(autoSwitchNotificationEvent({ outcome: 'ignored', observedAtMs: 300 }, 200), {
    observedAt: 300,
    kind: null,
  });
});

test('dedupe suppresses repeated and burst notifications', () => {
  const shouldDeliver = createNotificationDedupe(60_000);
  assert.equal(shouldDeliver('vpn-restored', 100, 1_000), true);
  assert.equal(shouldDeliver('vpn-restored', 100, 2_000), false);
  assert.equal(shouldDeliver('vpn-restored', 200, 30_000), false);
  assert.equal(shouldDeliver('vpn-restored', 300, 61_001), true);
});
