const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { MoteRelationshipStore, MoteQuestStore } = require('./mote-expansion');

test('relationship rewards are idempotent and level changes behavior', () => {
  const store = new MoteRelationshipStore();
  assert.equal(store.recordInteraction({ eventId: 'tap-1', kind: 'tap', amount: 10 }).duplicate, false);
  assert.equal(store.recordInteraction({ eventId: 'tap-1', kind: 'tap', amount: 10 }).duplicate, true);
  assert.equal(store.snapshot().xp, 10);
  assert.equal(store.recordInteraction({ eventId: 'tap-2', kind: 'task', amount: 90 }).level, 2);
});

test('routine relationship receipts remain durable beyond the recent interaction window', () => {
  const store = new MoteRelationshipStore();
  store.recordInteraction({ eventId: 'routine:finished-focus', kind: 'routine', amount: 1 });
  for (let index = 0; index < 520; index += 1) {
    store.recordInteraction({ eventId: `tap-${index}`, kind: 'tap', amount: 1 });
  }
  const xpBeforeReplay = store.snapshot().xp;
  const replay = store.recordInteraction({ eventId: 'routine:finished-focus', kind: 'routine', amount: 1 });
  assert.equal(replay.duplicate, true);
  assert.equal(store.snapshot().xp, xpBeforeReplay);
  assert.ok(store.snapshot().seenEventIds.includes('routine:finished-focus'));
});

test('quest claim is idempotent', () => {
  const store = new MoteQuestStore({ quests: [{ id: 'q1', title: 'one', reward: 5 }] });
  assert.equal(store.claim('q1', 'event-q1').duplicate, false);
  assert.equal(store.claim('q1', 'event-q1').duplicate, true);
  assert.equal(store.snapshot().claimed.length, 1);
});

test('relationship and quest state survive a node restart', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-mote-'));
  try {
    const relationshipPath = path.join(dir, 'relationship.json');
    const questPath = path.join(dir, 'quests.json');
    const relationship = new MoteRelationshipStore({ snapshotPath: relationshipPath });
    relationship.recordInteraction({ eventId: 'persist-1', amount: 120 });
    const quests = new MoteQuestStore({ quests: [{ id: 'q1', title: 'one' }], snapshotPath: questPath });
    quests.claim('q1', 'persist-q1');
    assert.equal(new MoteRelationshipStore({ snapshotPath: relationshipPath }).snapshot().level, 2);
    assert.equal(new MoteQuestStore({ quests: [{ id: 'q1', title: 'one' }], snapshotPath: questPath }).list()[0].claimed, true);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
