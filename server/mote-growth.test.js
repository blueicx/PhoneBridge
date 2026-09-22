const test = require('node:test');
const assert = require('node:assert/strict');
const { MoteGrowthStore } = require('./mote-growth');

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
