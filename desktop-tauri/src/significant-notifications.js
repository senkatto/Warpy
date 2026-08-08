const DEFAULT_COOLDOWN_MS = 60_000;

export function connectivityNotificationTransition(
  previousUiStatus,
  backendStatus,
  recoveryPending = false,
) {
  const transient = backendStatus === 'Starting'
    || backendStatus === 'Validating'
    || backendStatus === 'Recovering';

  if (transient) {
    return {
      recoveryPending: recoveryPending
        || previousUiStatus === 'connected'
        || previousUiStatus === 'error',
      event: null,
    };
  }

  if (backendStatus.startsWith('Connected')) {
    return {
      recoveryPending: false,
      event: recoveryPending ? 'restored' : null,
    };
  }

  const failed = recoveryPending || previousUiStatus === 'connected';
  return {
    recoveryPending: false,
    event: failed ? 'failed' : null,
  };
}

export function createNotificationDedupe(cooldownMs = DEFAULT_COOLDOWN_MS) {
  const delivered = new Map();
  return (key, eventAt = Date.now(), now = Date.now()) => {
    const normalizedEventAt = Number(eventAt) || now;
    const previous = delivered.get(key);
    if (
      previous
      && (normalizedEventAt <= previous.eventAt || now - previous.sentAt < cooldownMs)
    ) {
      return false;
    }
    delivered.set(key, { eventAt: normalizedEventAt, sentAt: now });
    return true;
  };
}
