const test = require('node:test');
const assert = require('node:assert/strict');
const { createRealityCoordinator } = require('./reality-coordinator');
const { RealityEngine } = require('./reality-engine');

test('reality coordinator centralizes clue processing and progress projection', () => {
  let broadcasts = 0;
  let boosts = [];
  const growth = {
    snapshot: () => ({ revision: 7, xp: 12, level: 2, region: 'camera', daily: { date: '2026-01-01' }, dailyByDate: {}, boosts }),
    recordClue: () => ({ businessStatus: 'accepted', reason: 'reward_applied', revision: 8, reward: { xp: 2 }, state: {} }),
  };
  const mote = { collectClue: () => ({ duplicate: false, state: {} }) };
  const reality = {
    snapshot: () => ({ updatedAt: 5, inventory: { item_01: 1 }, loadout: [], habitat: { comfort: 0 }, region: 'camera' }),
    setBoosts: value => { boosts = value; },
  };
  const coordinator = createRealityCoordinator({ moteStore: mote, moteGrowthStore: growth, realityEngine: reality, broadcastMoteState: () => broadcasts++ });
  const result = coordinator.applyMoteClue({ eventId: 'reality-lens:v2:2026-01-01:camera:location', clueType: 'location', region: 'camera' });
  assert.equal(result.businessStatus, 'accepted');
  assert.equal(result.resultRevision, 8);
  assert.equal(broadcasts, 1);
  const progress = coordinator.buildProgress();
  assert.equal(progress.inventory.item_01, 1);
  assert.equal(progress.revision, 7);
});

test('reality coordinator rejects precise regions before a store can mutate', () => {
  let called = false;
  const coordinator = createRealityCoordinator({
    moteStore: { collectClue: () => { called = true; return { duplicate: false }; } },
    moteGrowthStore: { snapshot: () => ({ boosts: [] }) },
    realityEngine: { snapshot: () => ({}), setBoosts: () => {} },
  });
  assert.throws(() => coordinator.applyMoteClue({ eventId: 'reality:31.2304,121.4737', clueType: 'location', region: '31.2304,121.4737' }), /precise|coarse/i);
  assert.equal(called, false);
});

test('reality coordinator validates generated events against region and time', () => {
  let calls = 0;
  let resolved = null;
  const coordinator = createRealityCoordinator({
    now: () => 1_700_000_000_000,
    moteStore: { collectClue: () => { calls += 1; return { duplicate: false }; } },
    moteGrowthStore: {
      snapshot: () => ({ boosts: [] }),
      recordClue: () => ({ businessStatus: 'accepted', reward: { xp: 1 }, state: {} }),
    },
    realityEngine: {
      snapshot: () => ({}),
      setBoosts: () => {},
      eventsFor: () => [{
        id: 'reality:v2:2023-11-14:cell:1:2:object:0:one',
        clueType: 'object',
        expiresAt: 1_700_001_800_000,
      }],
      resolve: payload => { resolved = payload; return { duplicate: false, reward: { itemId: 'item_01' } }; },
    },
  });

  const accepted = coordinator.applyMoteClue({
    eventId: 'reality:v2:2023-11-14:cell:1:2:object:0:one',
    clueType: 'object',
    region: 'cell:1:2',
    actions: ['observe'],
  });
  assert.equal(accepted.businessStatus, 'accepted');
  assert.equal(resolved.eventId, 'reality:v2:2023-11-14:cell:1:2:object:0:one');
  assert.deepEqual(resolved.actions, ['observe']);
  assert.equal(calls, 1);
  assert.throws(() => coordinator.applyMoteClue({
    eventId: 'reality:v2:2023-11-14:cell:1:2:object:0:one',
    clueType: 'light',
    region: 'cell:1:2',
  }), /clue type/i);
  assert.equal(calls, 1);
});

test('expired regional encounters cannot be rewarded by an offline backfill', () => {
  const now = 1_700_000_000_000;
  let resolved = false;
  const coordinator = createRealityCoordinator({
    now: () => now,
    moteStore: { collectClue: () => ({ duplicate: false }) },
    moteGrowthStore: { snapshot: () => ({ boosts: [] }), recordClue: () => ({ businessStatus: 'accepted' }) },
    realityEngine: {
      snapshot: () => ({}),
      setBoosts: () => {},
      eventsFor: (_region, at) => [{
        id: 'reality:v2:2023-11-15:cell:1:2:object:0:one',
        clueType: 'object',
        expiresAt: now - 1,
        requestedAt: at,
      }],
      resolve: () => { resolved = true; return { duplicate: false }; },
    },
  });

  assert.throws(() => coordinator.applyMoteClue({
    eventId: 'reality:v2:2023-11-15:cell:1:2:object:0:one',
    clueType: 'object',
    region: 'cell:1:2',
    activityAt: now - 10_000,
    offline: true,
  }), /expired|invalid/i);
  assert.equal(resolved, false);
});

test('replaying a partially applied encounter continues independent reward ledgers', () => {
  let realityCalls = 0;
  let moteCalls = 0;
  let growthCalls = 0;
  const coordinator = createRealityCoordinator({
    now: () => 1_700_000_000_000,
    moteStore: {
      collectClue: () => {
        moteCalls += 1;
        if (moteCalls === 1) throw new Error('simulated interruption after reality reward');
        return { duplicate: true, state: {} };
      },
      getState: () => ({ exploration: { targetId: 'mote_01' } }),
    },
    moteGrowthStore: {
      snapshot: () => ({ boosts: [] }),
      recordClue: () => {
        growthCalls += 1;
        return { businessStatus: 'accepted', reward: { xp: 2 }, state: {} };
      },
    },
    realityEngine: {
      snapshot: () => ({}),
      setBoosts: () => {},
      eventsFor: () => [{
        id: 'reality:v2:2023-11-14:cell:1:2:object:0:one',
        clueType: 'object',
        expiresAt: 1_700_001_800_000,
      }],
      resolve: () => {
        realityCalls += 1;
        return { duplicate: realityCalls > 1, reward: { itemId: 'item_01' } };
      },
    },
  });
  const event = {
    eventId: 'reality:v2:2023-11-14:cell:1:2:object:0:one',
    clueType: 'object',
    region: 'cell:1:2',
  };

  assert.throws(() => coordinator.applyMoteClue(event), /simulated interruption/);
  const replay = coordinator.applyMoteClue(event);

  assert.equal(realityCalls, 2);
  assert.equal(moteCalls, 2);
  assert.equal(growthCalls, 1);
  assert.equal(replay.growth.businessStatus, 'accepted');
});

test('a saved encounter receipt allows partial-ledger recovery after its event expires', () => {
  let now = Date.UTC(2026, 8, 28, 4, 0);
  const realityEngine = new RealityEngine({ now: () => now });
  const event = realityEngine.eventsFor('cell:1:2', now)[0];
  let moteCalls = 0;
  let growthCalls = 0;
  const coordinator = createRealityCoordinator({
    now: () => now,
    moteStore: {
      getState: () => ({ exploration: { targetId: 'mote_01' } }),
      collectClue: () => {
        moteCalls += 1;
        if (moteCalls === 1) throw new Error('simulated interruption after reality receipt');
        return { duplicate: true, state: {} };
      },
    },
    moteGrowthStore: {
      snapshot: () => ({ boosts: [] }),
      recordClue: () => {
        growthCalls += 1;
        return { businessStatus: 'accepted', reward: { xp: 2 }, state: {} };
      },
    },
    realityEngine,
  });
  const payload = { eventId: event.id, clueType: event.clueType, region: event.region, actions: ['observe'] };

  assert.throws(() => coordinator.applyMoteClue(payload), /simulated interruption/);
  assert.equal(realityEngine.getReceipt(event.id).eventId, event.id);
  now = event.expiresAt + 1;

  const replay = coordinator.applyMoteClue(payload);
  assert.equal(replay.businessStatus, 'accepted');
  assert.equal(replay.reality.duplicate, true);
  assert.equal(moteCalls, 2);
  assert.equal(growthCalls, 1);
  assert.equal(realityEngine.snapshot().inventory[event.rewardPreview], 1);
});
