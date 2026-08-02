export function normalizeNetworkContext(value) {
  const trust = ['trusted', 'untrusted', 'unknown'].includes(value?.trust)
    ? value.trust
    : 'unknown';
  const generation = Number.isSafeInteger(value?.generation) && value.generation >= 0
    ? value.generation
    : 0;
  return {
    trust,
    internet: value?.internet === true,
    generation,
  };
}

export function networkProtectionDecision({
  enabled,
  network,
  backendStatus,
  hasProfiles,
  busy,
  blocked,
  handledGeneration,
}) {
  const context = normalizeNetworkContext(network);
  if (!enabled) return { action: 'none', reason: 'off', context };
  if (!context.internet) return { action: 'none', reason: 'offline', context };
  if (context.trust === 'trusted') return { action: 'none', reason: 'trusted', context };
  if (context.trust !== 'untrusted') return { action: 'none', reason: 'unknown', context };
  if (blocked) return { action: 'none', reason: 'blocked', context };
  if (!hasProfiles) return { action: 'none', reason: 'no-profiles', context };
  if (busy || backendStatus !== 'Stopped') {
    return { action: 'none', reason: 'already-protected', context };
  }
  if (handledGeneration === context.generation) {
    return { action: 'none', reason: 'handled', context };
  }
  return { action: 'connect', reason: 'public-network', context };
}
