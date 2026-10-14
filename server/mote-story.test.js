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

const storyStartContexts = {
  conversation: { conversationCount: 1 },
  task_success: { successfulTasks: 1 },
  task_recovery: { recoveredTasks: 1 },
  exploration: { explorationCount: 1 },
  clue_location: { clueCounts: { location: 1 } },
  clue_object: { clueCounts: { object: 1 } },
  clue_light: { clueCounts: { light: 1 } },
  relationship_2: { relationshipLevel: 2, previousRelationshipLevel: 1 },
  relationship_3: { relationshipLevel: 3, previousRelationshipLevel: 2 },
  mote_unlock: { unlockedCount: 7 },
  boost: { boostCount: 1 },
};

function startExclusiveStory(store, storyId) {
  const story = MOTE_EXCLUSIVE_STORIES.find(item => item.id === storyId);
  return store.evaluate({
    ...storyStartContexts[story.trigger],
    eventId: `start:${story.id}`,
    activeId: story.moteId,
    exclusiveTriggers: [story.trigger],
  });
}

function completeExclusiveStory(store, storyId) {
  startExclusiveStory(store, storyId);
  const story = MOTE_EXCLUSIVE_STORIES.find(item => item.id === storyId);
  let result = null;
  for (const step of story.steps) {
    result = store.advance({ storyId, stepId: step.id, eventId: `step:${story.id}:${step.id}`, activeId: story.moteId });
  }
  return result;
}

test('story catalog retains twelve shared events and adds one exclusive event per Mote', () => {
  assert.equal(MOTE_STORY_EVENTS.length, 32);
  assert.equal(MOTE_EXCLUSIVE_STORIES.length, 20);
  assert.equal(new Set(MOTE_STORY_EVENTS.filter(event => !event.exclusive).map(event => event.id)).size, 12);
  assert.ok(MOTE_STORY_EVENTS.every(event => event.title && event.description && event.trigger && event.reward?.xp > 0));
  assert.equal(new Set(MOTE_EXCLUSIVE_STORIES.map(event => event.moteId)).size, 20);
  assert.deepEqual(new Set(MOTE_EXCLUSIVE_STORIES.map(event => event.moteId)), new Set(MOTE_PROFILES.map(profile => profile.id)));
  assert.ok(MOTE_EXCLUSIVE_STORIES.every(event => event.exclusive && event.moteName && event.completion && event.reward?.xp > 0));
  assert.ok(MOTE_EXCLUSIVE_STORIES.every(event => event.branches?.length === 2));
  assert.ok(MOTE_EXCLUSIVE_STORIES.every(event => new Set(event.branches.map(branch => branch.outcome)).size === 2));
  assert.ok(MOTE_EXCLUSIVE_STORIES.every(event => event.steps?.length === 3));
  assert.ok(MOTE_EXCLUSIVE_STORIES.every(event => new Set(event.steps.map(step => step.id)).size === 3));
});

test('exclusive story advances three durable steps before branching and replays each step idempotently', () => {
  const persistence = memoryPersistence();
  let now = 1_700_000_000_000;
  const store = new MoteStoryStore({ persistence, now: () => now });
  const storyId = 'exclusive-mote';
  const started = store.evaluate({ eventId: 'story-start-mote', activeId: 'mote', successfulTasks: 1, exclusiveTriggers: ['task_success'] });
  assert.equal(started.newlyCompleted.some(item => item.id === storyId), false);
  assert.equal(started.events.find(item => item.id === storyId).started, true);
  assert.equal(started.events.find(item => item.id === storyId).stepIndex, 0);
  assert.throws(() => store.chooseBranch(storyId, 'go-further'), /not complete/i);

  const definition = MOTE_EXCLUSIVE_STORIES.find(item => item.id === storyId);
  for (const [index, step] of definition.steps.entries()) {
    now += 1_000;
    const result = store.advance({
      storyId,
      eventId: `story-step:${storyId}:${step.id}`,
      stepId: step.id,
      activeId: 'mote',
    });
    assert.equal(result.duplicate, false);
    assert.equal(result.event.stepIndex, index + 1);
    assert.equal(result.event.completed, index === definition.steps.length - 1);
    const replay = store.advance({ storyId, eventId: `story-step:${storyId}:${step.id}`, stepId: step.id, activeId: 'mote' });
    assert.equal(replay.duplicate, true);
    assert.equal(replay.event.stepIndex, index + 1);
  }

  const restored = new MoteStoryStore({ persistence, now: () => now + 1 });
  assert.equal(restored.list().find(item => item.id === storyId).stepIndex, 3);
  assert.equal(restored.list().find(item => item.id === storyId).completed, true);
  assert.equal(restored.chooseBranch(storyId, 'go-further').duplicate, false);
  assert.throws(() => restored.advance({ storyId, eventId: 'story-step:wrong', stepId: 'unknown', activeId: 'mote' }), /complete|step/i);
});

test('story step progression rejects another Mote, out-of-order steps and conflicting event id reuse', () => {
  const store = new MoteStoryStore();
  const storyId = 'exclusive-mote';
  const story = MOTE_EXCLUSIVE_STORIES.find(item => item.id === storyId);
  store.evaluate({ eventId: 'story-start-mote', activeId: 'mote', successfulTasks: 1, exclusiveTriggers: ['task_success'] });
  assert.throws(() => store.advance({ storyId, eventId: 'wrong-mote-step', stepId: story.steps[0].id, activeId: 'sprite' }), /Mote must be active/i);
  assert.throws(() => store.advance({ storyId, eventId: 'wrong-order-step', stepId: story.steps[1].id, activeId: 'mote' }), /step/i);
  store.advance({ storyId, eventId: 'reused-step-event', stepId: story.steps[0].id, activeId: 'mote' });
  assert.throws(() => store.advance({ storyId, eventId: 'reused-step-event', stepId: story.steps[1].id, activeId: 'mote' }), /reused/i);
});

test('failed story-step persistence rolls back and permits the same receipt to retry', () => {
  const backing = memoryPersistence();
  let failSave = false;
  const persistence = {
    load: (...args) => backing.load(...args),
    save: (...args) => {
      if (failSave && args[0] === 'mote-story') throw new Error('simulated story snapshot failure');
      return backing.save(...args);
    },
  };
  const store = new MoteStoryStore({ persistence });
  startExclusiveStory(store, 'exclusive-mote');
  const step = MOTE_EXCLUSIVE_STORIES.find(item => item.id === 'exclusive-mote').steps[0];
  const revision = store.snapshot().revision;
  failSave = true;
  assert.throws(() => store.advance({ storyId: 'exclusive-mote', eventId: 'retry-after-save-failure', stepId: step.id, activeId: 'mote' }), /simulated story snapshot failure/);
  assert.equal(store.list().find(item => item.id === 'exclusive-mote').stepIndex, 0);
  assert.equal(store.snapshot().revision, revision);
  failSave = false;
  const retried = store.advance({ storyId: 'exclusive-mote', eventId: 'retry-after-save-failure', stepId: step.id, activeId: 'mote' });
  assert.equal(retried.duplicate, false);
  assert.equal(retried.event.stepIndex, 1);
});

test('story step receipt remains idempotent after the general story-event window rolls over', () => {
  const store = new MoteStoryStore({ persistence: memoryPersistence() });
  startExclusiveStory(store, 'exclusive-mote');
  const step = MOTE_EXCLUSIVE_STORIES.find(item => item.id === 'exclusive-mote').steps[0];
  const payload = { storyId: 'exclusive-mote', eventId: 'durable-story-step', stepId: step.id, activeId: 'mote' };
  store.advance(payload);
  for (let index = 0; index < 1_100; index += 1) store.evaluate({ eventId: `unrelated:${index}` });
  const replay = store.advance(payload);
  assert.equal(replay.duplicate, true);
  assert.equal(replay.event.stepIndex, 1);
});

test('exclusive stories persist one distinct branch and award its result exactly once', () => {
  const persistence = memoryPersistence();
  let now = 1_700_000_000_000;
  const storyStore = new MoteStoryStore({ persistence, now: () => now });
  const relationshipStore = new MoteRelationshipStore({ persistence, now: () => now });
  const storyId = 'exclusive-mote';
  assert.throws(() => storyStore.chooseBranch(storyId, 'go-further'), /not complete/i);
  completeExclusiveStory(storyStore, storyId);

  const selected = storyStore.chooseBranch(storyId, 'go-further');
  assert.equal(selected.duplicate, false);
  assert.match(selected.event.branchOutcome, /星核/);
  assert.throws(() => storyStore.chooseBranch(storyId, 'keep-at-home'), /already chosen/i);

  now += 1_000;
  const restored = new MoteStoryStore({ persistence, now: () => now });
  assert.equal(restored.list().find(event => event.id === storyId).branchChoiceId, 'go-further');
  const claim = claimMoteStoryWithReward({
    storyStore: restored,
    relationshipStore,
    eventId: storyId,
    claimId: 'branch-claim-once',
  });
  assert.equal(claim.reward.xp, 13);
  assert.equal(claim.relationship.xp, 13);
  assert.equal(restored.chooseBranch(storyId, 'go-further').duplicate, true);

  const replay = claimMoteStoryWithReward({
    storyStore: new MoteStoryStore({ persistence, now: () => now }),
    relationshipStore,
    eventId: storyId,
    claimId: 'branch-claim-once',
  });
  assert.equal(replay.duplicate, true);
  assert.equal(replay.relationship.duplicate, true);
  assert.equal(relationshipStore.snapshot().xp, 13);
});

test('legacy story claim selects the no-bonus ending without changing its reward', () => {
  const store = new MoteStoryStore({ persistence: memoryPersistence() });
  completeExclusiveStory(store, 'exclusive-mote');
  const result = claimMoteStoryWithReward({
    storyStore: store,
    relationshipStore: new MoteRelationshipStore(),
    eventId: 'exclusive-mote',
    claimId: 'legacy-client-claim',
  });
  assert.equal(result.reward.xp, 10);
  assert.equal(result.event.branchChoiceId, 'keep-at-home');
  assert.ok(result.event.branchOutcome);
});

test('failed branch or claim persistence rolls back in-memory choice and receipt state', () => {
  const backing = memoryPersistence();
  let failSave = false;
  const persistence = {
    load: (...args) => backing.load(...args),
    save: (...args) => {
      if (failSave && args[0] === 'mote-story') throw new Error('simulated story snapshot failure');
      return backing.save(...args);
    },
  };
  const store = new MoteStoryStore({ persistence });
  completeExclusiveStory(store, 'exclusive-mote');
  const revision = store.snapshot().revision;
  failSave = true;
  assert.throws(() => store.chooseBranch('exclusive-mote', 'go-further'), /simulated story snapshot failure/);
  assert.equal(store.list().find(event => event.id === 'exclusive-mote').branchChoiceId, '');
  assert.equal(store.snapshot().revision, revision);
  assert.throws(() => store.claim('exclusive-mote', 'claim-after-failure'), /simulated story snapshot failure/);
  assert.equal(store.list().find(event => event.id === 'exclusive-mote').claimed, false);
  assert.equal(store.list().find(event => event.id === 'exclusive-mote').branchChoiceId, '');
  assert.equal(store.snapshot().revision, revision);
});

test('exclusive story completion requires its own Mote to be active', () => {
  const store = new MoteStoryStore();
  const wrongMote = store.evaluate({ eventId: 'other-mote-task', activeId: 'sprite', successfulTasks: 1, exclusiveTriggers: ['task_success'] });
  assert.equal(wrongMote.newlyCompleted.some(event => event.id === 'exclusive-mote'), false);
  assert.equal(wrongMote.startedStories.includes('exclusive-mote'), false);

  const correctMote = store.evaluate({ eventId: 'star-core-task', activeId: 'mote', successfulTasks: 1, exclusiveTriggers: ['task_success'] });
  assert.equal(correctMote.newlyCompleted.some(event => event.id === 'exclusive-mote'), false);
  assert.equal(correctMote.startedStories.includes('exclusive-mote'), true);
  assert.equal(correctMote.events.find(event => event.id === 'exclusive-mote').started, true);
  assert.equal(correctMote.events.find(event => event.id === 'exclusive-mote').completed, false);
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
    assert.equal(result.startedStories.includes(event.id), true, `${event.id} should start for its own profile`);
    assert.equal(result.newlyCompleted.some(item => item.id === event.id), false, `${event.id} should need its three story steps`);
  }
});

test('legacy story snapshots keep their shared progress after catalog expansion', () => {
  const persistence = memoryPersistence();
  persistence.save('mote-story', {
    version: 1,
    completed: [
      { id: 'first-conversation', sourceEventId: 'old-chat', completedAt: 100 },
      { id: 'exclusive-mote', sourceEventId: 'old-exclusive', completedAt: 110 },
    ],
    claimed: [],
    seenEventIds: ['old-chat'],
    revision: 4,
    updatedAt: 100,
  });
  const restored = new MoteStoryStore({ persistence, now: () => 200 });
  assert.equal(restored.list().find(event => event.id === 'first-conversation').completed, true);
  assert.equal(restored.list().find(event => event.id === 'exclusive-mote').completed, true);
  assert.equal(restored.list().find(event => event.id === 'exclusive-mote').stepIndex, 3);
  assert.equal(restored.snapshot().version, 4);
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

  const exclusive = startExclusiveStory(store, 'exclusive-mote');
  assert.equal(exclusive.newlyCompleted.some(event => event.id === 'exclusive-mote'), false);
  assert.equal(exclusive.events.find(event => event.id === 'exclusive-mote').stepIndex, 0);
  const completed = completeExclusiveStory(store, 'exclusive-mote');
  assert.equal(completed.event.completed, true);
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
