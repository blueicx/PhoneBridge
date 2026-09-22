const test = require('node:test');
const assert = require('node:assert/strict');
const { createRealityCoordinator } = require('./reality-coordinator');

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
