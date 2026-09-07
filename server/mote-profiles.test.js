const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  MOTE_PROFILES,
  INITIAL_MOTE_IDS,
  EXPLORABLE_MOTE_IDS,
  MoteStore,
} = require('./mote-profiles');

test('keeps six initial and four explorable complete Mote profiles', () => {
  assert.equal(MOTE_PROFILES.length, 10);
  assert.equal(INITIAL_MOTE_IDS.length, 6);
  assert.equal(EXPLORABLE_MOTE_IDS.length, 4);
  for (const profile of MOTE_PROFILES) {
    for (const key of ['id', 'name', 'initial', 'voice', 'proactive', 'taskAffinity', 'emotionBias', 'colors', 'motion', 'particles', 'visualPreset']) {
      assert.ok(profile[key] !== undefined, `${profile.id} missing ${key}`);
    }
    assert.ok(Array.isArray(profile.taskAffinity));
    assert.equal(typeof profile.colors.primary, 'string');
  }
});

test('unlocks target after location, object and light clues exactly once', () => {
  const store = new MoteStore();
  const target = EXPLORABLE_MOTE_IDS[0];
  assert.equal(store.setExplorationTarget(target).exploration.targetId, target);
  assert.equal(store.collectClue({ eventId: 'e-location', clueType: 'location' }).unlockedId, null);
  assert.equal(store.collectClue({ eventId: 'e-object', clueType: 'object' }).unlockedId, null);
  const completed = store.collectClue({ eventId: 'e-light', clueType: 'light' });
  assert.equal(completed.unlockedId, target);
  assert.ok(completed.state.unlockedIds.includes(target));
  assert.equal(completed.state.exploration.targetId, null);
  assert.equal(store.collectClue({ eventId: 'e-light', clueType: 'light' }).duplicate, true);
});

test('deduplicates eventId and migrates incomplete persisted state', () => {
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-mote-'));
  const statePath = path.join(runtimeDir, 'motes.json');
  fs.writeFileSync(statePath, JSON.stringify({ activeId: 'missing', unlockedIds: ['mote', 'ember_sprig'], exploration: { targetId: 'prism_moth', fragments: { location: true } } }));
  const store = new MoteStore({ snapshotPath: statePath });
  assert.equal(store.getState().activeId, 'mote');
  assert.deepEqual(store.getState().exploration.fragments, { location: true, object: false, light: false });
  store.setExplorationTarget('prism_moth');
  const first = store.collectClue({ eventId: 'same', clueType: 'object' });
  const duplicate = store.collectClue({ eventId: 'same', clueType: 'object' });
  assert.equal(first.duplicate, false);
  assert.equal(duplicate.duplicate, true);
  const restored = new MoteStore({ snapshotPath: statePath });
  assert.ok(restored.getState().exploration.seenEventIds.includes('same'));
  fs.rmSync(runtimeDir, { recursive: true, force: true });
});
