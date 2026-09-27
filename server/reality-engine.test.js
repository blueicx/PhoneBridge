const test = require('node:test');
const assert = require('node:assert/strict');
const { RealityEngine, ITEMS, RECIPES, DECORATIONS, QUESTS, EVENTS, ENCOUNTERS } = require('./reality-engine');

test('content catalog has the complete expanded pack', () => {
  assert.equal(ITEMS.length, 40);
  assert.equal(RECIPES.length, 20);
  assert.equal(DECORATIONS.length, 30);
  assert.equal(QUESTS.length, 36);
  assert.equal(EVENTS.length, 40);
  assert.equal(ENCOUNTERS.length, 20);
  assert.equal(new Set(ITEMS.map(item => item.id)).size, ITEMS.length);
});

test('reality events are deterministic and rewards are idempotent', () => {
  let now = 1700000000000;
  const engine = new RealityEngine({ now: () => now });
  const events = engine.eventsFor('cell:1:2');
  assert.deepEqual(events, engine.eventsFor('cell:1:2'));
  assert.match(events[0].id, /^reality:v2:\d{4}-\d{2}-\d{2}:cell:1:2:/);
  assert.notEqual(events[0].id, engine.eventsFor('cell:1:2', now + 24 * 60 * 60 * 1000)[0].id);
  const event = events[0];
  const result = engine.resolve({ eventId: event.id, region: 'cell:1:2', clueType: event.clueType, at: now });
  assert.equal(result.duplicate, false);
  assert.equal(engine.resolve({ eventId: event.id, region: 'cell:1:2', clueType: event.clueType, at: now }).duplicate, true);
  assert.throws(() => engine.resolve({ eventId: events[1].id, region: 'cell:9:9', clueType: events[1].clueType, at: now }), /invalid/);
});

test('reality events accept only coarse cell or camera regions', () => {
  const engine = new RealityEngine({ now: () => 1700000000000 });
  assert.throws(() => engine.eventsFor('31.2304,121.4737'), /coarse region/i);
  assert.throws(() => engine.eventsFor('cell:1:2:precise'), /coarse region/i);
  assert.equal(engine.eventsFor('camera').length, 8);
});

test('crafting, habitat, encounters and quests persist through the engine state', () => {
  const engine = new RealityEngine({ persistence: { load: () => null, save: () => {} } });
  engine.state.inventory.item_01 = 1;
  engine.state.inventory.item_08 = 1;
  engine.craft('recipe_01');
  assert.equal(engine.snapshot().inventory.item_21, 1);
  engine.decorate('decor_01');
  assert.equal(engine.snapshot().habitat.comfort, 1);
  const event = engine.eventsFor('cell:0:0')[0];
  const started = engine.startEncounter({ eventId: event.id, region: 'cell:0:0' });
  assert.ok(started.encounter.id);
  const resolved = engine.resolve({ eventId: event.id, region: 'cell:0:0', clueType: event.clueType, actions: ['observe', 'skill'] });
  assert.ok(resolved.reward.xp > event.xp);
  assert.equal(engine.claimQuest('quest_01', 'claim-1').duplicate, false);
  assert.equal(engine.claimQuest('quest_01', 'claim-1').duplicate, true);
});

test('encounter start is replay-safe and resolution preserves the chosen action receipt', () => {
  let now = 1_700_000_000_000;
  const engine = new RealityEngine({ now: () => now });
  const event = engine.eventsFor('cell:4:5', now)[0];
  const started = engine.startEncounter({ eventId: event.id, region: event.region, at: now });
  engine.state.activeEncounter.step = 2;
  const replayedStart = engine.startEncounter({ eventId: event.id, region: event.region, at: now });
  assert.equal(replayedStart.duplicate, true);
  assert.equal(engine.snapshot().activeEncounter.step, 2);

  const result = engine.resolve({ eventId: event.id, region: event.region, clueType: event.clueType, actions: ['observe'], at: now });
  assert.equal(result.duplicate, false);
  assert.equal(result.receipt.action, 'observe');
  assert.equal(result.receipt.reward.itemId, result.reward.itemId);
  assert.equal(result.reward.item.name, ITEMS.find(item => item.id === result.reward.itemId).name);
  assert.equal(engine.getReceipt(event.id).reward.xp, result.reward.xp);

  const replayed = engine.resolve({ eventId: event.id, region: event.region, clueType: event.clueType, actions: ['dodge'], at: now });
  assert.equal(replayed.duplicate, true);
  assert.equal(replayed.receipt.action, 'observe');
  assert.deepEqual(replayed.reward, result.reward);
  assert.equal(engine.snapshot().inventory[result.reward.itemId], 1);
  assert.equal(started.encounter.id, replayedStart.encounter.id);
});

test('active time-limited boosts modify reality rewards and expire', () => {
  let now = 1_700_000_000_000;
  const engine = new RealityEngine({ now: () => now });
  engine.setBoosts([{ id: 'field-focus', multiplier: 2, expiresAt: now + 10_000 }]);
  const event = engine.eventsFor('cell:5:6', now)[0];
  const boosted = engine.resolve({ eventId: event.id, region: 'cell:5:6', clueType: event.clueType, at: now });
  assert.equal(boosted.reward.baseXp, event.difficulty + event.xp);
  assert.equal(boosted.reward.xp, boosted.reward.baseXp * 2);

  now += 2 * 30 * 60 * 1000;
  const next = engine.eventsFor('cell:5:6', now)[0];
  const expired = engine.resolve({ eventId: next.id, region: 'cell:5:6', clueType: next.clueType, at: now });
  assert.equal(expired.reward.xp, expired.reward.baseXp);
});

test('quest claims validate completion and loadout validates owned equipment', () => {
  const engine = new RealityEngine({ now: () => 1_700_000_000_000 });
  assert.throws(() => engine.claimQuest('quest_01', 'before-observation'), /not complete/i);
  assert.throws(() => engine.setLoadout(['item_30']), /not owned/i);
  engine.state.inventory.item_30 = 1;
  assert.deepEqual(engine.setLoadout(['item_30']).loadout, ['item_30']);
});
