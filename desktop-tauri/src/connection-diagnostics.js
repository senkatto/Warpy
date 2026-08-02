import { median } from './network-measurements.js';

export function summarizeLatencySamples(samples, attempts) {
  const validSamples = samples.filter(value => Number.isFinite(value) && value >= 0);
  if (validSamples.length < 3) {
    throw new Error('Not enough successful latency samples');
  }

  const differences = validSamples
    .slice(1)
    .map((value, index) => Math.abs(value - validSamples[index]));
  const failedAttempts = Math.max(0, attempts - validSamples.length);

  return {
    latencyMs: Math.round(median(validSamples)),
    jitterMs: Math.round(median(differences)),
    lossPercent: Math.round((failedAttempts * 100) / Math.max(1, attempts)),
    successfulSamples: validSamples.length,
    attempts,
  };
}

export function classifyConnection(metrics) {
  if (
    metrics.lossPercent >= 10
    || metrics.jitterMs >= 100
    || metrics.latencyMs >= 350
  ) {
    return 'poor';
  }
  if (
    metrics.lossPercent >= 3
    || metrics.jitterMs >= 50
    || metrics.latencyMs >= 180
  ) {
    return 'impaired';
  }
  return 'stable';
}

export function buildConnectionRecommendations({
  metrics,
  mtu,
  warpyAuto,
  profileCount,
}) {
  const quality = classifyConnection(metrics);
  if (quality === 'stable') return [];

  const recommendations = [];
  if (!warpyAuto && profileCount > 1) recommendations.push('warpyAuto');
  if (Number(mtu) > 0) recommendations.push('automaticMtu');
  return recommendations;
}
