const test = require('node:test');
const assert = require('node:assert/strict');
const { ProactivePolicy } = require('./proactive-policy');

test('ProactivePolicy defaults to one reminder per hour and six per day', () => {
  const policy = new ProactivePolicy({ now: () => Date.parse('2026-09-22T10:00:00+08:00') });
  const snapshot = policy.snapshot();
  assert.equal(snapshot.maxPerHour, 1);
  assert.equal(snapshot.maxPerDay, 6);
  assert.equal(snapshot.quietStart, 23);
  assert.equal(snapshot.quietEnd, 7);
  assert.equal(snapshot.focusActive, false);
});

test('ProactivePolicy explains quiet, focus, hourly and daily suppression', () => {
  let current = Date.parse('2026-09-22T10:00:00+08:00');
  const policy = new ProactivePolicy({ now: () => current });
  assert.equal(policy.attempt('a').allowed, true);
  assert.equal(policy.attempt('b').reason, 'hourly_limit');
  assert.match(policy.explain('b').message, /每小时/);

  policy.update({ focusActive: true });
  assert.equal(policy.explain('c').reason, 'focus_mode');
  policy.update({ focusActive: false, paused: true });
  assert.equal(policy.explain('c').reason, 'paused');
  policy.update({ paused: false });
  for (const key of ['b', 'c', 'd', 'e', 'f']) {
    current += 61 * 60 * 1000;
    assert.equal(policy.attempt(key).allowed, true);
  }
  current += 61 * 60 * 1000;
  assert.equal(policy.explain('g').reason, 'daily_limit');

  current = Date.parse('2026-09-22T23:30:00+08:00');
  assert.equal(policy.explain('night').reason, 'quiet_hours');
  current = Date.parse('2026-09-23T08:00:00+08:00');
  assert.equal(policy.attempt('new-day').allowed, true);
});

test('ProactivePolicy persists state and supports an immediate mute window', () => {
  let saved = null;
  let current = Date.parse('2026-09-22T10:00:00+08:00');
  const first = new ProactivePolicy({ now: () => current, persistence: { load: () => saved, save: (_, state) => { saved = state; } } });
  first.attempt('persisted');
  first.mute(30 * 60 * 1000);
  const restored = new ProactivePolicy({ now: () => current, persistence: { load: () => saved, save: (_, state) => { saved = state; } } });
  assert.equal(restored.snapshot().mutedUntil > current, true);
  assert.equal(restored.explain('next').reason, 'muted');
  current += 31 * 60 * 1000;
  assert.equal(restored.attempt('next').allowed, false, 'the hourly limit is still respected after mute');
});
