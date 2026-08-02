import assert from 'node:assert/strict';
import test from 'node:test';

import { median, toMbps } from '../src/network-measurements.js';

test('network measurements use stable median and throughput units', () => {
  assert.equal(median([60, 10, 30]), 30);
  assert.equal(median([10, 20, 30, 40]), 25);
  assert.equal(median([]), 0);
  assert.equal(toMbps(1_000_000, 1000), 8);
  assert.equal(toMbps(1000, 0), 0);
});
