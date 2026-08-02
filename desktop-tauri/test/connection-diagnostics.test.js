import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildConnectionRecommendations,
  classifyConnection,
  summarizeLatencySamples,
} from '../src/connection-diagnostics.js';

test('61 ms with low jitter and no loss is stable', () => {
  const metrics = summarizeLatencySamples([58, 62, 61, 60, 63, 59, 61, 60], 8);

  assert.deepEqual(metrics, {
    latencyMs: 61,
    jitterMs: 2,
    lossPercent: 0,
    successfulSamples: 8,
    attempts: 8,
  });
  assert.equal(classifyConnection(metrics), 'stable');
  assert.deepEqual(buildConnectionRecommendations({
    metrics,
    mtu: 1420,
    warpyAuto: false,
    profileCount: 3,
  }), []);
});

test('current-run loss and jitter produce explicit recommendations', () => {
  const metrics = summarizeLatencySamples([55, 60, 180, 65, 190, 70, 185], 10);

  assert.equal(metrics.lossPercent, 30);
  assert.equal(classifyConnection(metrics), 'poor');
  assert.deepEqual(buildConnectionRecommendations({
    metrics,
    mtu: 1420,
    warpyAuto: false,
    profileCount: 3,
  }), ['warpyAuto', 'automaticMtu']);
});

test('automatic settings are not proposed when already enabled', () => {
  const metrics = {
    latencyMs: 410,
    jitterMs: 30,
    lossPercent: 0,
  };

  assert.deepEqual(buildConnectionRecommendations({
    metrics,
    mtu: 0,
    warpyAuto: true,
    profileCount: 4,
  }), []);
});

test('latency summary rejects an inconclusive run', () => {
  assert.throws(
    () => summarizeLatencySamples([50, 55], 8),
    /Not enough successful latency samples/,
  );
});
