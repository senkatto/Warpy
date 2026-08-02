function localizedRegionName(countryCode, language) {
  if (!/^[a-z]{2}$/i.test(countryCode)) return '';
  const fallback = countryCode.toUpperCase();
  try {
    const name = new Intl.DisplayNames([language], { type: 'region' }).of(fallback);
    return name && name.toUpperCase() !== fallback ? name : fallback;
  } catch {
    return fallback;
  }
}

function isCountryCodeOnly(name, countryCode) {
  if (!/^[a-z]{2}$/i.test(countryCode)) return false;
  return name.replace(/[\s._-]/g, '').toUpperCase() === countryCode.toUpperCase();
}

function isFlagOnly(name, flagEmoji) {
  return Boolean(flagEmoji) && name === flagEmoji;
}

export function buildTrayProfileSnapshots(profiles, language, getProfileDisplay) {
  const snapshots = profiles.map(profile => {
    const display = getProfileDisplay(profile);
    const countryCode = String(display.countryCode || '').toLowerCase();
    let name = String(display.name || profile.name || profile.protocol || '').trim();
    if (!name || isFlagOnly(name, display.flagEmoji) || isCountryCodeOnly(name, countryCode)) {
      name = localizedRegionName(countryCode, language) || name || 'VPN profile';
    }
    return {
      name,
      countryCode: /^[a-z]{2}$/.test(countryCode) ? countryCode : null,
      group: typeof profile.group === 'string' && profile.group.trim()
        ? profile.group.trim()
        : null,
    };
  });

  const totals = new Map();
  for (const profile of snapshots) {
    const key = `${profile.group || ''}\u0000${profile.name.toLocaleLowerCase(language)}`;
    totals.set(key, (totals.get(key) || 0) + 1);
  }

  const seen = new Map();
  return snapshots.map(profile => {
    const key = `${profile.group || ''}\u0000${profile.name.toLocaleLowerCase(language)}`;
    if (totals.get(key) === 1) return profile;
    const ordinal = (seen.get(key) || 0) + 1;
    seen.set(key, ordinal);
    return { ...profile, name: `${profile.name} · ${ordinal}` };
  });
}
