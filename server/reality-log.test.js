'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { MOTE_PROFILES } = require('./mote-profiles');
const { MoteGrowthStore, buildClueEventId, DAY_MS } = require('./mote-growth');
const { RealityEngine } = require('./reality-engine');
const { buildRealityLog } = require('./reality-log');

function stores({ growth = [], reality = [] } = {}) {
  return {
    growthStore: { listReceipts: () => structuredClone(growth) },
    realityEngine: { listReceipts: () => structuredClone(reality) },
    moteProfiles: { activeId: 'sprite', profiles: MOTE_PROFILES },
  };
}

test('projects only reward-confirmed receipts, merges ledgers by eventId, and emits a privacy allowlist', () => {
  const sharedId = 'reality:v2:2026-09-30:cell:1:2:object:960:object_01';
  const growthOnlyId = 'reality-lens:v2:2026-09-30:camera:light:offline_01';
  const realityOnlyId = 'reality:v2:2026-09-30:camera:location:960:location_01';
  const { growthStore, realityEngine, moteProfiles } = stores({
    growth: [
      { eventId: sharedId, businessStatus: 'accepted', createdAt: 200, region: 'cell:1:2', clueType: 'object', reward: { xp: 2 } },
      { eventId: growthOnlyId, businessStatus: 'accepted', createdAt: 150, region: 'camera', clueType: 'light', reward: { xp: 3 } },
      { eventId: 'reality-lens:v2:2026-09-30:camera:location:dup', businessStatus: 'duplicate', createdAt: 500, region: 'camera', clueType: 'location', reward: { xp: 0 } },
      { eventId: 'reality-lens:v2:2026-09-30:camera:object:reject', businessStatus: 'rejected', createdAt: 600, region: 'camera', clueType: 'object', reward: { xp: 0 } },
    ],
    reality: [
      {
        eventId: sharedId, region: 'cell:1:2', clueType: 'object', resolvedAt: 201,
        reward: { itemId: 'item_01', amount: 1, xp: 9 },
        event: { title: 'PRIVATE-EVENT-TITLE', image: 'PRIVATE-IMAGE', latitude: 31.2, longitude: 121.4, path: ['PRIVATE-TRACK'] },
        observationText: 'PRIVATE-OBSERVATION-TEXT',
      },
      {
        eventId: realityOnlyId, region: 'camera', clueType: 'location', resolvedAt: 100,
        reward: { itemId: 'item_02', amount: 1, xp: 4 }, event: { title: 'PRIVATE-SECOND-TITLE' },
      },
      {
        eventId: realityOnlyId, region: 'camera', clueType: 'location', resolvedAt: 100,
        reward: { itemId: 'item_02', amount: 1, xp: 4 }, event: { title: 'DUPLICATE-RECEIPT' },
      },
    ],
  });

  const result = buildRealityLog({ growthStore, realityEngine, moteProfiles });

  assert.deepEqual(result.entries.map(entry => entry.eventId), [sharedId, growthOnlyId, realityOnlyId]);
  assert.deepEqual(Object.keys(result.entries[0]).sort(), [
    'eventId', 'status', 'occurredAt', 'coarseRegion', 'clueType', 'moteId', 'observation', 'reward',
  ].sort());
  assert.equal(result.entries[0].status, 'confirmed');
  assert.equal(result.entries[0].occurredAt, 201);
  assert.equal(result.entries[0].coarseRegion, 'cell:1:2');
  assert.equal(result.entries[0].moteId, 'sprite');
  assert.equal(result.entries[0].observation, '叶狐：一起留意可观察的物体线索。');
  assert.deepEqual(result.entries[0].reward, { xp: 11, items: [{ id: 'item_01', amount: 1 }], boost: null });
  const serialized = JSON.stringify(result);
  for (const privateValue of ['PRIVATE-EVENT-TITLE', 'PRIVATE-IMAGE', 'PRIVATE-TRACK', 'PRIVATE-OBSERVATION-TEXT', '31.2', '121.4']) {
    assert.equal(serialized.includes(privateValue), false, privateValue);
  }
});

test('projects legacy coarse-region receipts only when their clue type and safe event ID suffix match', () => {
  const legacyGrowthId = 'reality-lens:cell:-1:2:location:legacy_01';
  const legacyRealityId = 'reality:camera:light';
  const { growthStore, realityEngine, moteProfiles } = stores({
    growth: [
      { eventId: legacyGrowthId, businessStatus: 'accepted', createdAt: 100, region: 'cell:-1:2', clueType: 'location', reward: { xp: 2 } },
      { eventId: 'reality:camera:object:PRIVATE TEXT', businessStatus: 'accepted', createdAt: 200, region: 'camera', clueType: 'object', reward: { xp: 2 } },
    ],
    reality: [{
      eventId: legacyRealityId, region: 'camera', clueType: 'light', resolvedAt: 150,
      reward: { itemId: 'item_03', amount: 1, xp: 3 },
    }],
  });

  const result = buildRealityLog({ growthStore, realityEngine, moteProfiles });

  assert.deepEqual(result.entries.map(entry => entry.eventId), [legacyRealityId, legacyGrowthId]);
  assert.equal(result.entries[0].coarseRegion, 'camera');
  assert.equal(result.entries[0].clueType, 'light');
  assert.equal(result.entries[1].coarseRegion, 'cell:-1:2');
  assert.equal(result.entries[1].clueType, 'location');
  assert.equal(JSON.stringify(result).includes('PRIVATE TEXT'), false);
  const firstPage = buildRealityLog({ growthStore, realityEngine, moteProfiles, limit: 1 });
  const secondPage = buildRealityLog({ growthStore, realityEngine, moteProfiles, cursor: firstPage.nextCursor, limit: 1 });
  assert.equal(secondPage.entries[0].eventId, legacyGrowthId);
});

test('does not expose unknown persisted item identifiers as exploration rewards', () => {
  const args = stores({ reality: [{
    eventId: 'reality:v2:2026-09-30:camera:light:960:item_01',
    region: 'camera', clueType: 'light', resolvedAt: 500,
    reward: { itemId: 'PRIVATE-ITEM-NAME', amount: 1, xp: 4 },
  }] });

  const result = buildRealityLog(args);

  assert.deepEqual(result.entries[0].reward.items, []);
  assert.equal(JSON.stringify(result).includes('PRIVATE-ITEM-NAME'), false);
});

test('uses deterministic descending occurredAt/eventId ordering and keyset pagination without gaps or overlap', () => {
  const entries = ['a', 'b', 'c', 'd', 'e'].map(eventId => ({
    eventId: `reality:v2:2026-09-30:camera:light:960:${eventId}`,
    region: 'camera', clueType: 'light', resolvedAt: 500,
    reward: { itemId: 'item_01', amount: 1, xp: 1 },
  }));
  const args = stores({ reality: entries });

  const first = buildRealityLog({ ...args, limit: 2 });
  const second = buildRealityLog({ ...args, cursor: first.nextCursor, limit: 2 });
  const third = buildRealityLog({ ...args, cursor: second.nextCursor, limit: 2 });

  const allIds = [...first.entries, ...second.entries, ...third.entries].map(entry => entry.eventId);
  assert.deepEqual(allIds, entries.map(entry => entry.eventId).sort().reverse());
  assert.equal(new Set(allIds).size, entries.length);
  assert.equal(first.entries.length, 2);
  assert.equal(second.entries.length, 2);
  assert.equal(third.entries.length, 1);
  assert.equal(third.nextCursor, null);
});

test('rejects malformed, unknown, and out-of-range pagination input', () => {
  const args = stores({ reality: [{
    eventId: 'reality:v2:2026-09-30:camera:light:960:light_01',
    region: 'camera', clueType: 'light', resolvedAt: 500,
    reward: { itemId: 'item_01', amount: 1, xp: 1 },
  }] });

  for (const cursor of ['not-base64!', Buffer.from('{"occurredAt":1,"eventId":"missing"}').toString('base64url')]) {
    assert.throws(() => buildRealityLog({ ...args, cursor }), error => error.code === 'invalid_cursor');
  }
  assert.throws(() => buildRealityLog({ ...args, limit: 101 }), error => error.code === 'invalid_limit');
  assert.throws(() => buildRealityLog({ ...args, limit: '10x' }), error => error.code === 'invalid_limit');
});

test('ignores receipts whose coarse region, clue type, or timestamp contradicts the receipt identity', () => {
  const { growthStore, realityEngine, moteProfiles } = stores({
    growth: [
      { eventId: 'reality-lens:v2:2026-09-30:cell:1:2:object:region_mismatch', businessStatus: 'accepted', createdAt: 200, region: 'cell:9:9', clueType: 'object', reward: { xp: 2 } },
      { eventId: 'reality-lens:v2:2026-09-30:camera:light:clue_mismatch', businessStatus: 'accepted', createdAt: 200, region: 'camera', clueType: 'object', reward: { xp: 2 } },
    ],
    reality: [
      { eventId: 'reality:v2:2026-09-30:camera:location:960:missing_time', region: 'camera', clueType: 'location', resolvedAt: null, reward: { xp: 4 } },
    ],
  });

  assert.deepEqual(buildRealityLog({ growthStore, realityEngine, moteProfiles }).entries, []);
});

test('reads receipt accessors only and does not mutate stores while projecting', () => {
  let growthSnapshots = 0;
  const growthStore = {
    listReceipts: () => [],
    snapshot: () => { growthSnapshots += 1; throw new Error('snapshot mutates daily growth state'); },
  };
  const realityEngine = { listReceipts: () => [], snapshot: () => { throw new Error('snapshot not needed'); } };

  assert.deepEqual(buildRealityLog({
    growthStore, realityEngine,
    moteProfiles: { activeId: 'mote', profiles: MOTE_PROFILES },
  }), { entries: [], nextCursor: null });
  assert.equal(growthSnapshots, 0);
});

test('receipt accessors clone existing ledgers without rotating growth state', () => {
  let now = Date.parse('2026-09-30T03:00:00.000Z');
  const growthStore = new MoteGrowthStore({ now: () => now });
  const growthId = buildClueEventId({ activityAt: now, region: 'camera', clueType: 'object', nonce: 'receipt_test' });
  growthStore.recordClue({ eventId: growthId, region: 'camera', clueType: 'object', activityAt: now });
  const previousGrowthUpdatedAt = growthStore.state.updatedAt;
  now += DAY_MS;

  const growthReceipts = growthStore.listReceipts();
  growthReceipts[0].reward.xp = 999;
  assert.equal(growthStore.getReceipt(growthId).reward.xp, 2);
  assert.equal(growthStore.state.updatedAt, previousGrowthUpdatedAt);

  const realityEngine = new RealityEngine({ now: () => now });
  const realityEvent = realityEngine.eventsFor('camera', now).find(item => item.clueType === 'light');
  realityEngine.resolve({ eventId: realityEvent.id, region: 'camera', clueType: 'light', at: now });
  const realityReceipts = realityEngine.listReceipts();
  realityReceipts[0].reward.xp = 999;
  assert.notEqual(realityEngine.getReceipt(realityEvent.id).reward.xp, 999);
});
