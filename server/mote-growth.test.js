const test = require('node:test');
const assert = require('node:assert/strict');
const { MoteGrowthStore, DAY_MS } = require('./mote-growth');

test('daily exploration completes the three-clue loop once and grants a boost', () => {
  const store = new MoteGrowthStore({ now: () => 1_735_689_600_000 });

  const location = store.recordClue({ eventId: 'reality-lens:cell:1:2:location', clueType: 'location', region: 'cell:1:2' });
  const object = store.recordClue({ eventId: 'reality-lens:cell:1:2:object', clueType: 'object', region: 'cell:1:2' });
  const light = store.recordClue({ eventId: 'reality-lens:cell:1:2:light', clueType: 'light', region: 'cell:1:2' });

  assert.equal(location.duplicate, false);
  assert.equal(object.reward.dailyCompleted, false);
  assert.equal(light.reward.dailyCompleted, true);
  assert.equal(light.reward.boost.id, 'field-focus');
  assert.equal(store.snapshot().daily.completed, true);
  assert.equal(store.snapshot().level, 1);

  const duplicate = store.recordClue({ eventId: 'reality-lens:cell:1:2:light', clueType: 'light', region: 'cell:1:2' });
  assert.equal(duplicate.duplicate, true);
  assert.equal(duplicate.reward.xp, 0);
  assert.equal(store.snapshot().seenEventIds.length, 3);
});

test('growth rejects precise locations and cross-region payloads', () => {
  const store = new MoteGrowthStore({ now: () => 1_735_689_600_000 });

  assert.throws(
    () => store.recordClue({ eventId: 'reality:31.2304,121.4737', clueType: 'location', region: '31.2304,121.4737' }),
    /coarse region|precise/i
  );
  assert.throws(
    () => store.recordClue({ eventId: 'reality:cell:1:2:location', clueType: 'location', region: 'cell:bad' }),
    /coarse region/i
  );
  assert.throws(
    () => store.recordClue({ eventId: 'reality:cell:1:2:location', clueType: 'location', region: 'cell:2:3' }),
    /region/i
  );
  assert.throws(
    () => store.recordClue({ eventId: 'reality:cell:1:20:location', clueType: 'location', region: 'cell:1:2' }),
    /region/i
  );
  assert.throws(
    () => store.recordClue({ eventId: 'reality:cell:1:2:location', clueType: 'location', region: 'camera' }),
    /region/i
  );
});

test('a new day resets daily progress while retaining total growth', () => {
  let now = 1_735_689_600_000;
  const store = new MoteGrowthStore({ now: () => now });
  store.recordClue({ eventId: 'reality:cell:1:2:location:a', clueType: 'location', region: 'cell:1:2' });
  const before = store.snapshot().xp;
  now += 86_400_000;
  const next = store.recordClue({ eventId: 'reality:cell:1:2:location:b', clueType: 'location', region: 'cell:1:2' });
  assert.equal(next.duplicate, false);
  assert.equal(store.snapshot().daily.completed, false);
  assert.equal(store.snapshot().daily.clues.location, 1);
  assert.ok(store.snapshot().xp > before);
});

test('growth state survives persistence and restores seen event ids', () => {
  let saved = null;
  const persistence = {
    load: () => saved,
    save: (_key, value) => { saved = JSON.parse(JSON.stringify(value)); },
  };
  const first = new MoteGrowthStore({ now: () => 1_735_689_600_000, persistence });
  first.recordClue({ eventId: 'reality:cell:4:5:location:one', clueType: 'location', region: 'cell:4:5' });
  const restored = new MoteGrowthStore({ now: () => 1_735_689_600_000, persistence });
  assert.equal(restored.snapshot().xp, 2);
  assert.equal(restored.recordClue({ eventId: 'reality:cell:4:5:location:one', clueType: 'location', region: 'cell:4:5' }).duplicate, true);
});

test('clue ids carry the Shanghai activity date and only award each type once per day', () => {
  let now = Date.parse('2026-01-01T16:30:00.000Z'); // 2026-01-02 in Asia/Shanghai
  const store = new MoteGrowthStore({ now: () => now });
  const firstId = MoteGrowthStore.buildClueEventId({
    activityAt: now,
    region: 'cell:1:2',
    clueType: 'location',
    nonce: 'a'
  });
  const first = store.recordClue({ eventId: firstId, clueType: 'location', region: 'cell:1:2', activityAt: now });
  assert.equal(first.businessStatus, 'accepted');
  assert.equal(first.activityDate, '2026-01-02');
  assert.equal(first.revision, 1);

  const second = store.recordClue({
    eventId: MoteGrowthStore.buildClueEventId({ activityAt: now, region: 'cell:1:2', clueType: 'location', nonce: 'b' }),
    clueType: 'location',
    region: 'cell:1:2',
    activityAt: now
  });
  assert.equal(second.businessStatus, 'duplicate');
  assert.equal(second.reason, 'clue_type_already_collected_today');
  assert.equal(second.reward.xp, 0);

  now = Date.parse('2026-01-02T16:30:00.000Z'); // next Shanghai day
  const nextDay = store.recordClue({
    eventId: MoteGrowthStore.buildClueEventId({ activityAt: now, region: 'cell:1:2', clueType: 'location', nonce: 'c' }),
    clueType: 'location',
    region: 'cell:1:2',
    activityAt: now
  });
  assert.equal(nextDay.businessStatus, 'accepted');
  assert.equal(nextDay.activityDate, '2026-01-03');
  assert.equal(store.snapshot().daily.date, '2026-01-03');
});

test('offline clues older than seven days are rejected without a reward', () => {
  const now = Date.parse('2026-02-10T08:00:00.000Z');
  const store = new MoteGrowthStore({ now: () => now });
  const result = store.recordClue({
    eventId: MoteGrowthStore.buildClueEventId({ activityAt: now - (8 * DAY_MS), region: 'cell:2:3', clueType: 'object', nonce: 'expired' }),
    clueType: 'object',
    region: 'cell:2:3',
    activityAt: now - (8 * DAY_MS),
    offline: true
  });
  assert.equal(result.businessStatus, 'rejected');
  assert.equal(result.reason, 'offline_event_expired');
  assert.equal(result.reward.xp, 0);
  assert.equal(store.snapshot().xp, 0);
});

test('accepted clue receipts survive restart and replay is business-idempotent', () => {
  let saved = null;
  const persistence = {
    load: () => saved,
    save: (_key, value) => { saved = JSON.parse(JSON.stringify(value)); }
  };
  const now = Date.parse('2026-03-03T00:00:00.000Z');
  const eventId = MoteGrowthStore.buildClueEventId({ activityAt: now, region: 'cell:4:5', clueType: 'light', nonce: 'persisted' });
  const first = new MoteGrowthStore({ now: () => now, persistence });
  const accepted = first.recordClue({ eventId, clueType: 'light', region: 'cell:4:5', activityAt: now });
  assert.equal(first.getReceipt(eventId).businessStatus, 'accepted');
  const restored = new MoteGrowthStore({ now: () => now, persistence });
  assert.equal(restored.getReceipt(eventId).revision, accepted.revision);
  const replay = restored.recordClue({ eventId, clueType: 'light', region: 'cell:4:5', activityAt: now });
  assert.equal(replay.businessStatus, 'duplicate');
  assert.equal(replay.reason, 'event_already_processed');
  assert.equal(restored.snapshot().xp, accepted.state.xp);
});

test('a persistence failure rolls back an in-flight reward', () => {
  let fail = false;
  const persistence = {
    load: () => null,
    save: () => { if (fail) throw new Error('simulated crash'); }
  };
  const now = Date.parse('2026-04-01T00:00:00.000Z');
  const store = new MoteGrowthStore({ now: () => now, persistence });
  fail = true;
  assert.throws(() => store.recordClue({ eventId: 'reality:cell:1:2:location:crash', clueType: 'location', region: 'cell:1:2', activityAt: now }), /simulated crash/);
  assert.equal(store.snapshot().xp, 0);
  assert.equal(store.getReceipt('reality:cell:1:2:location:crash'), null);
});
