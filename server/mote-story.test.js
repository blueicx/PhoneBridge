'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { MOTE_STORY_EVENTS, MoteStoryStore } = require('./mote-story');

function memoryPersistence() {
  const data = new Map();
  return {
    load(name, fallback) { return data.has(name) ? JSON.parse(JSON.stringify(data.get(name))) : fallback; },
    save(name, value) { data.set(name, JSON.parse(JSON.stringify(value))); return value; },
  };
}

function completeContext(eventId = 'all-at-once') {
  return {
    eventId,
    activeId: 'mote',
    conversationCount: 1,
    successfulTasks: 1,
    explorationCount: 1,
    clues: { location: true, object: true, light: true },
    unlockedCount: 7,
    relationshipLevel: 3,
    boostCount: 1,
    recoveredTasks: 1,
  };
}

test('story catalog contains twelve distinct triggerable events', () => {
  assert.equal(MOTE_STORY_EVENTS.length, 12);
  assert.equal(new Set(MOTE_STORY_EVENTS.map(event => event.id)).size, 12);
  assert.ok(MOTE_STORY_EVENTS.every(event => event.title && event.description && event.trigger && event.reward?.xp > 0));
});

test('story completion is idempotent and claim requires completion', () => {
  const store = new MoteStoryStore({ persistence: memoryPersistence(), now: () => 1_700_000_000_000 });
  assert.throws(() => store.claim('first-awakening', 'claim-before'), /not complete/i);

  const first = store.evaluate(completeContext());
  assert.equal(first.duplicate, false);
  assert.equal(first.newlyCompleted.length, 12);
  assert.equal(store.list().filter(event => event.completed).length, 12);

  const replay = store.evaluate(completeContext());
  assert.equal(replay.duplicate, true);
  assert.equal(replay.newlyCompleted.length, 0);

  const claimed = store.claim('first-awakening', 'claim-awakening');
  assert.equal(claimed.duplicate, false);
  assert.equal(claimed.reward.xp, 5);
  const claimReplay = store.claim('first-awakening', 'claim-awakening');
  assert.equal(claimReplay.duplicate, true);
});

test('story state survives restart and supports incremental triggers', () => {
  const persistence = memoryPersistence();
  const first = new MoteStoryStore({ persistence, now: () => 1_700_000_000_000 });
  const result = first.evaluate({ eventId: 'chat-1', conversationCount: 1 });
  assert.deepEqual(result.newlyCompleted.map(event => event.id), ['first-conversation']);

  const restored = new MoteStoryStore({ persistence, now: () => 1_700_000_001_000 });
  assert.equal(restored.list().find(event => event.id === 'first-conversation').completed, true);
  const next = restored.evaluate({ eventId: 'task-1', successfulTasks: 1 });
  assert.deepEqual(next.newlyCompleted.map(event => event.id), ['first-task']);
});
