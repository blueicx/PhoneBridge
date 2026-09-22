'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { RealityEventStore } = require('./reality-event-store');

test('RealityEventStore generates deterministic coarse-region events with expiry', () => {
  const templates = [
    { templateId: 'one', kind: 'location', clueType: 'location', difficulty: 1, rewardItem: 'item_01', xp: 4 },
    { templateId: 'two', kind: 'object', clueType: 'object', difficulty: 2, rewardItem: 'item_02', xp: 5 },
  ];
  const store = new RealityEventStore({ templates, now: () => 1_700_000_000_000 });
  const first = store.eventsFor('cell:1:2', 1_700_000_000_000);
  assert.deepEqual(first, store.eventsFor('cell:1:2', 1_700_000_000_000));
  assert.notDeepEqual(first, store.eventsFor('cell:9:9', 1_700_000_000_000));
  assert.match(first[0].id, /^reality:v2:/);
  assert.equal(first[0].expiresAt, Math.floor(1_700_000_000_000 / 1_800_000 + 1) * 1_800_000);
  assert.throws(() => store.eventsFor('31.2,121.4', 1_700_000_000_000), /coarse region/i);
});
