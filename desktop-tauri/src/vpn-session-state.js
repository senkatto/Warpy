const TRANSIENT_SERVICE_STATUSES = new Set(['Starting', 'Validating', 'Recovering']);

export function normalizeServiceConnectionStatus(rawStatus, desiredRunning = false) {
  const status = String(rawStatus || 'Error');
  if (status.startsWith('Connected')) return 'connected';
  if (TRANSIENT_SERVICE_STATUSES.has(status)) return 'connecting';
  if (status === 'Stopped') return desiredRunning ? 'error' : 'stopped';
  return 'error';
}

export function reconcileServiceConnection(previous, snapshot, startedAt = 0) {
  const status = normalizeServiceConnectionStatus(snapshot?.status, snapshot?.desiredRunning === true);
  const previousStartedAt = Number(previous?.connectedAt) || 0;
  const observedStartedAt = Number(startedAt) || 0;
  const connectedAt = status === 'connected'
    ? observedStartedAt || previousStartedAt || Date.now()
    : 0;

  return {
    status,
    connectedAt,
    changed: status !== previous?.status || connectedAt !== previousStartedAt,
    serviceStatus: String(snapshot?.status || 'Error'),
  };
}

export function isTransientServiceStatus(status) {
  return TRANSIENT_SERVICE_STATUSES.has(String(status));
}
