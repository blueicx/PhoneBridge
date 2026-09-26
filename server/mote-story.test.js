'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { MOTE_STORY_EVENTS, MOTE_EXCLUSIVE_STORIES, MoteStoryStore, deriveExclusiveTriggers, claimMoteStoryWithReward } = require('./mote-story');
const { MoteRelationshipStore } = require('./mote-expansion');
const { MOTE_PROFILES } = require('./mote-profiles');

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

test('story catalog retains twelve shared events and adds one exclusive event per Mote', () => {
  assert.equal(MOTE_STORY_EVENTS.length, 32);
  assert.equal(MOTE_EXCLUSIVE_STORIES.length, 20);
  assert.equal(new Set(MOTE_STORY_EVENTS.filter(event => !event.exclusive).map(event => event.id)).size, 12);
  assert.ok(MOTE_STORY_EVENTS.every(event => event.title && event.description && event.trigger && event.reward?.xp > 0));
  assert.equal(new Set(MOTE_EXCLUSIVE_STORIES.map(event => event.moteId)).size, 20);
  assert.deepEqual(new Set(MOTE_EXCLUSIVE_STORIES.map(event => event.moteId)), new Set(MOTE_PROFILES.map(profile => profile.id)));
  assert.ok(MOTE_EXCLUSIVE_STORIES.every(event => event.exclusive && event.moteName && event.completion && event.reward?.xp > 0));
});

test('exclusive story completion requires its own Mote to be active', () => {
  const store = new MoteStoryStore();
  const wrongMote = store.evaluate({ eventId: 'other-mote-task', activeId: 'sprite', successfulTasks: 1, exclusiveTriggers: ['task_success'] });
  assert.equal(wrongMote.newlyCompleted.some(event => event.id === 'exclusive-mote'), false);

  const correctMote = store.evaluate({ eventId: 'star-core-task', activeId: 'mote', successfulTasks: 1, exclusiveTriggers: ['task_success'] });
  assert.equal(correctMote.newlyCompleted.some(event => event.id === 'exclusive-mote'), true);
  assert.equal(correctMote.events.find(event => event.id === 'exclusive-mote').completed, true);
});

test('cumulative shared history cannot complete an exclusive story on a later Mote activation', () => {
  const store = new MoteStoryStore();
  const result = store.evaluate({ ...completeContext('switch-to-ghost'), activeId: 'ghost', exclusiveTriggers: ['activation'] });
  assert.equal(result.newlyCompleted.some(event => event.id === 'exclusive-ghost'), false);
});

test('an existing relationship level cannot complete a threshold story without a crossing event', () => {
  const triggers = deriveExclusiveTriggers({ relationshipLevel: 3 });
  assert.equal(triggers.includes('relationship_2'), false);
  assert.equal(triggers.includes('relationship_3'), false);
  const store = new MoteStoryStore();
  const result = store.evaluate({
    eventId: 'switch-to-tortoise-at-level-three',
    activeId: 'moss_tortoise',
    relationshipLevel: 3,
    exclusiveTriggers: triggers,
  });
  assert.equal(result.newlyCompleted.some(event => event.id === 'exclusive-moss_tortoise'), false);
});

test('exclusive trigger derivation uses only event-local counters and includes crossed relationship levels', () => {
  assert.deepEqual(deriveExclusiveTriggers({}), []);
  assert.deepEqual(
    new Set(deriveExclusiveTriggers({
      successfulTasks: 1,
      recoveredTasks: 1,
      relationshipLevel: 3,
      previousRelationshipLevel: 1,
      clueCounts: { light: 1 },
      boostCount: 1,
    })),
    new Set(['task_success', 'task_recovery', 'relationship_2', 'relationship_3', 'clue_light', 'boost'])
  );
});

test('each exclusive story has a live matching trigger and cannot complete for another profile', () => {
  const triggerContext = {
    activation: { activeId: 'mote' },
    conversation: { conversationCount: 1 },
    task_success: { successfulTasks: 1 },
    task_recovery: { recoveredTasks: 1 },
    exploration: { explorationCount: 1 },
    clue_location: { clueCounts: { location: 1 } },
    clue_object: { clueCounts: { object: 1 } },
    clue_light: { clueCounts: { light: 1 } },
    mote_unlock: { unlockedCount: 7 },
    relationship_2: { relationshipLevel: 2, previousRelationshipLevel: 1 },
    relationship_3: { relationshipLevel: 3, previousRelationshipLevel: 2 },
    boost: { boostCount: 1 },
  };
  for (const event of MOTE_EXCLUSIVE_STORIES) {
    const store = new MoteStoryStore();
    const result = store.evaluate({ ...triggerContext[event.trigger], eventId: `trigger:${event.id}`, activeId: event.moteId, exclusiveTriggers: [event.trigger] });
    assert.equal(result.newlyCompleted.some(item => item.id === event.id), true, `${event.id} should complete for its own profile`);
  }
});

test('legacy story snapshots keep their shared progress after catalog expansion', () => {
  const persistence = memoryPersistence();
  persistence.save('mote-story', {
    version: 1,
    completed: [{ id: 'first-conversation', sourceEventId: 'old-chat', completedAt: 100 }],
    claimed: [],
    seenEventIds: ['old-chat'],
    revision: 4,
    updatedAt: 100,
  });
  const restored = new MoteStoryStore({ persistence, now: () => 200 });
  assert.equal(restored.list().find(event => event.id === 'first-conversation').completed, true);
  assert.equal(restored.list().find(event => event.id === 'exclusive-mote').completed, false);
  assert.equal(restored.snapshot().version, 2);
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

  const exclusive = store.evaluate({ eventId: 'star-core-task', activeId: 'mote', successfulTasks: 1, exclusiveTriggers: ['task_success'] });
  assert.equal(exclusive.newlyCompleted.some(event => event.id === 'exclusive-mote'), true);
  const exclusiveClaim = store.claim('exclusive-mote', 'claim-star-core');
  assert.equal(exclusiveClaim.reward.xp, 10);
  assert.equal(store.claim('exclusive-mote', 'claim-star-core').duplicate, true);
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

test('replaying a claimed story recovers a reward lost after the claim snapshot was saved', () => {
  const storyPersistence = memoryPersistence();
  const relationshipBacking = memoryPersistence();
  let failRewardWrite = true;
  const relationshipPersistence = {
    load: (...args) => relationshipBacking.load(...args),
    save: (...args) => {
      if (failRewardWrite) throw new Error('simulated relationship snapshot failure');
      return relationshipBacking.save(...args);
    },
  };
  const storyStore = new MoteStoryStore({ persistence: storyPersistence });
  storyStore.evaluate({ eventId: 'startup', activeId: 'mote' });
  assert.throws(() => claimMoteStoryWithReward({
    storyStore,
    relationshipStore: new MoteRelationshipStore({ persistence: relationshipPersistence }),
    eventId: 'first-awakening',
    claimId: 'claim-before-crash',
  }), /simulated relationship snapshot failure/);
  assert.equal(storyStore.list().find(event => event.id === 'first-awakening').claimed, true);

  failRewardWrite = false;
  const resumed = claimMoteStoryWithReward({
    storyStore: new MoteStoryStore({ persistence: storyPersistence }),
    relationshipStore: new MoteRelationshipStore({ persistence: relationshipPersistence }),
    eventId: 'first-awakening',
    claimId: 'claim-before-crash',
  });
  assert.equal(resumed.duplicate, true);
  assert.equal(resumed.relationship.duplicate, false);
  assert.equal(resumed.relationship.xp, 5);

  const replay = claimMoteStoryWithReward({
    storyStore: new MoteStoryStore({ persistence: storyPersistence }),
    relationshipStore: new MoteRelationshipStore({ persistence: relationshipPersistence }),
    eventId: 'first-awakening',
    claimId: 'claim-before-crash',
  });
  assert.equal(replay.relationship.duplicate, true);
  assert.equal(replay.relationship.xp, 5);
});

test('story reward receipts survive relationship interaction compaction', () => {
  const storyStore = new MoteStoryStore({ persistence: memoryPersistence() });
  storyStore.evaluate({ eventId: 'startup', activeId: 'mote' });
  const relationshipStore = new MoteRelationshipStore({ persistence: memoryPersistence() });
  const claim = { storyStore, relationshipStore, eventId: 'first-awakening', claimId: 'claim-once' };

  claimMoteStoryWithReward(claim);
  relationshipStore.recordInteraction({ eventId: 'story:client-generated', amount: 1 });
  for (let index = 0; index < 513; index += 1) {
    relationshipStore.recordInteraction({ eventId: `interaction:${index}`, amount: 1 });
  }

  assert.equal(relationshipStore.snapshot().seenEventIds.includes('story:client-generated'), false);
  const replay = claimMoteStoryWithReward(claim);
  assert.equal(replay.relationship.duplicate, true);
  assert.equal(replay.relationship.xp, 519);
});
