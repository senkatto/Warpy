export function toMbps(bytes, durationMs) {
  return durationMs > 0 ? (bytes * 8 * 1000) / (durationMs * 1_000_000) : 0;
}

export function median(values) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
}

export function cloudflareDownloadUrl(bytes, purpose) {
  const run = `${Date.now()}-${Math.random()}`;
  return `https://speed.cloudflare.com/__down?bytes=${bytes}&${purpose}=${run}`;
}

export async function fetchWithTimeout(url, parentSignal, timeoutMs) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  parentSignal.addEventListener('abort', abort, { once: true });
  const timeout = setTimeout(abort, timeoutMs);
  try {
    const response = await fetch(url, { cache: 'no-store', signal: controller.signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response;
  } finally {
    clearTimeout(timeout);
    parentSignal.removeEventListener('abort', abort);
  }
}

export async function measureLatencySamples({
  attempts,
  minSuccessful,
  pauseMs = 0,
  signal,
  timeoutMs,
  urlFactory,
  onAttempt,
}) {
  const warmup = await fetchWithTimeout(urlFactory(), signal, timeoutMs);
  await warmup.arrayBuffer();

  const samples = [];
  for (let index = 0; index < attempts; index++) {
    onAttempt?.(index + 1, attempts);
    try {
      const startedAt = performance.now();
      const response = await fetchWithTimeout(urlFactory(), signal, timeoutMs);
      await response.arrayBuffer();
      samples.push(performance.now() - startedAt);
    } catch (error) {
      if (signal.aborted) throw error;
    }
    if (pauseMs > 0 && index + 1 < attempts) {
      await new Promise(resolve => setTimeout(resolve, pauseMs));
    }
  }

  if (samples.length < minSuccessful) {
    throw new Error('Latency measurement was inconclusive');
  }
  return samples;
}
