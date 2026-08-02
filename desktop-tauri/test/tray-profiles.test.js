import assert from 'node:assert/strict';
import test from 'node:test';
import { buildTrayProfileSnapshots } from '../src/tray-profiles.js';

const displayProfile = profile => profile.display;

test('uses localized country names instead of regional-indicator emoji', () => {
  const profiles = [
    { group: 'BlancVPN', display: { name: '🇺🇸', countryCode: 'us', flagEmoji: '🇺🇸' } },
    { group: 'BlancVPN', display: { name: 'US', countryCode: 'us', flagEmoji: '🇺🇸' } },
    { group: 'BlancVPN', display: { name: '🇮🇹', countryCode: 'it', flagEmoji: '🇮🇹' } },
  ];

  const result = buildTrayProfileSnapshots(profiles, 'ru', displayProfile);
  assert.deepEqual(
    result.map(profile => profile.name),
    ['Соединенные Штаты · 1', 'Соединенные Штаты · 2', 'Италия'],
  );
  assert.deepEqual(result.map(profile => profile.countryCode), ['us', 'us', 'it']);
  assert.equal(result.some(profile => /[🇦-🇿]/u.test(profile.name)), false);
});

test('preserves descriptive server names and separates duplicate names per group', () => {
  const profiles = [
    { group: 'One', display: { name: 'Amsterdam, Netherlands', countryCode: 'nl' } },
    { group: 'Two', display: { name: 'Amsterdam, Netherlands', countryCode: 'nl' } },
  ];

  const result = buildTrayProfileSnapshots(profiles, 'en', displayProfile);
  assert.deepEqual(result, [
    { name: 'Amsterdam, Netherlands', countryCode: 'nl', group: 'One' },
    { name: 'Amsterdam, Netherlands', countryCode: 'nl', group: 'Two' },
  ]);
});
