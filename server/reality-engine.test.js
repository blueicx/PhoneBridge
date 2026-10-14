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

test('encounter receipt preserves the server-selected Mote for replay-stable presentation', () => {
  const now = 1_700_000_000_000;
  const engine = new RealityEngine({ now: () => now });
  const event = engine.eventsFor('cell:4:5', now)[0];
  engine.startEncounter({ eventId: event.id, region: event.region, at: now });

  const accepted = engine.resolve({
    eventId: event.id,
    region: event.region,
    clueType: event.clueType,
    actions: ['skill'],
    moteId: 'sprite',
    at: now,
  });

  assert.equal(accepted.receipt.moteId, 'sprite');
  assert.equal(engine.getReceipt(event.id).moteId, 'sprite');
  const replay = engine.resolve({ eventId: event.id, region: event.region, clueType: event.clueType, actions: ['dodge'], moteId: 'ghost', at: now });
  assert.equal(replay.receipt.moteId, 'sprite');
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
  assert.throws(() => engine.setLoadout(['item_31']), /not owned/i);
  engine.state.inventory.item_30 = 1;
  assert.throws(() => engine.setLoadout(['item_30']), /not equipment/i);
  engine.state.inventory.item_31 = 1;
  assert.deepEqual(engine.setLoadout(['item_31']).loadout, ['item_31']);
});

test('habitat catalog supplies explicit visual and interaction profiles', () => {
  const decorations = new RealityEngine().catalog().decorations;
  assert.equal(decorations.length, 30);
  assert.ok(decorations.every(item => item.visualType && item.interaction));
  assert.ok(new Set(decorations.map(item => item.visualType)).size >= 10);
});

test('versioned habitat layout migrates legacy placements and supports move, remove and reset', () => {
  let saved = null;
  const engine = new RealityEngine({
    persistence: {
      load: () => ({ version: 3, habitat: { decorations: ['decor_01', 'decor_03', 'unknown'], comfort: 5 } }),
      save: (_key, state) => { saved = structuredClone(state); },
    },
  });

  let habitat = engine.snapshot().habitat;
  assert.equal(habitat.layoutVersion, 2);
  assert.deepEqual(habitat.decorations, ['decor_01', 'decor_03']);
  assert.deepEqual(habitat.layout.map(item => item.id), habitat.decorations);
  assert.equal(habitat.layout[0].x >= 0 && habitat.layout[0].x <= 1, true);

  habitat = engine.updateHabitat({ action: 'move', decorationId: 'decor_03', x: 0.42, y: 0.58, rotation: 24 });
  assert.deepEqual(habitat.habitat.layout[1], { id: 'decor_03', x: 0.42, y: 0.58, rotation: 24 });
  assert.throws(() => engine.updateHabitat({ action: 'move', decorationId: 'decor_03', x: 1.2, y: 0.5 }), /position/i);
  assert.equal(engine.snapshot().habitat.layout[1].x, 0.42);

  habitat = engine.updateHabitat({ action: 'remove', decorationId: 'decor_01' });
  assert.deepEqual(habitat.habitat.decorations, ['decor_03']);
  habitat = engine.updateHabitat({ action: 'reset' });
  assert.deepEqual(habitat.habitat.decorations, ['decor_03']);
  assert.deepEqual(habitat.habitat.layout[0], { id: 'decor_03', x: 0.16, y: 0.79, rotation: 0 });
  assert.deepEqual(saved.habitat, habitat.habitat);
});

test('habitat layout rejects unknown, duplicate and over-capacity decorations', () => {
  const engine = new RealityEngine();
  assert.throws(() => engine.updateHabitat({ action: 'move', decorationId: 'missing', x: 0.5, y: 0.5 }), /decoration/i);
  for (let index = 1; index <= 12; index += 1) engine.decorate(`decor_${String(index).padStart(2, '0')}`);
  assert.throws(() => engine.decorate('decor_13'), /12/);
  assert.equal(engine.snapshot().habitat.decorations.length, 12);
});

test('twelve selected inventory items expose actionable use or equipment effect descriptions', () => {
  const selected = ITEMS.filter(item => item.useEffect || item.equipmentEffect);
  assert.equal(selected.length, 12);
  assert.ok(selected.every(item => item.effectLabel && item.effectDescription));
  assert.ok(selected.filter(item => item.useEffect).every(item => item.kind === 'boost'));
  assert.ok(selected.filter(item => item.equipmentEffect).every(item => item.kind === 'equipment'));
});

test('recipe preview reports owned materials, gaps and clue sources without consuming inventory', () => {
  const engine = new RealityEngine({ now: () => 1_700_000_000_000 });
  engine.state.inventory.item_01 = 2;
  const preview = engine.previewRecipe('recipe_01');
  assert.equal(preview.canCraft, false);
  assert.deepEqual(preview.ingredients[0], {
    itemId: 'item_01', name: ITEMS[0].name, required: 1, owned: 2, missing: 0,
    sources: ['地点线索'],
  });
  assert.deepEqual(preview.missing, ['item_08']);
  assert.equal(engine.snapshot().inventory.item_01, 2);
  engine.state.inventory.item_08 = 1;
  assert.equal(engine.previewRecipe('recipe_01').canCraft, true);
  assert.throws(() => engine.previewRecipe('missing-recipe'), /recipe not found/i);
});

test('using consumables is owned-only, replay-safe, rollback-safe and applies expiring effects', () => {
  let now = 1_700_000_000_000;
  const engine = new RealityEngine({ now: () => now });
  assert.throws(() => engine.useItem({ eventId: 'use-unowned', itemId: 'item_21' }), /not owned/i);
  engine.state.inventory.item_21 = 1;
  const used = engine.useItem({ eventId: 'use-focus', itemId: 'item_21' });
  assert.equal(used.duplicate, false);
  assert.equal(used.state.inventory.item_21, 0);
  assert.equal(used.receipt.effect.label, '专注增益');
  const repeated = engine.useItem({ eventId: 'use-focus', itemId: 'item_21' });
  assert.equal(repeated.duplicate, true);
  assert.equal(engine.snapshot().inventory.item_21, 0);
  assert.throws(() => engine.useItem({ eventId: 'use-focus', itemId: 'item_22' }), /eventId.*reused/i);

  const event = engine.eventsFor('cell:7:8', now)[0];
  const boosted = engine.resolve({ eventId: event.id, region: event.region, clueType: event.clueType, at: now });
  assert.equal(boosted.reward.multiplier, 1.5);
  assert.equal(boosted.reward.xp, Math.round(boosted.reward.baseXp * 1.5));
  now += 31 * 60 * 1000;
  const next = engine.eventsFor('cell:7:8', now)[0];
  const expired = engine.resolve({ eventId: next.id, region: next.region, clueType: next.clueType, at: now });
  assert.equal(expired.reward.multiplier, 1);

  const failed = new RealityEngine({
    persistence: { load: () => null, save: () => { throw new Error('disk full'); } }, now: () => now,
  });
  failed.state.inventory.item_21 = 1;
  assert.throws(() => failed.useItem({ eventId: 'use-rollback', itemId: 'item_21' }), /disk full/);
  assert.equal(failed.snapshot().inventory.item_21, 1);
  assert.equal(failed.snapshot().itemUseReceipts['use-rollback'], undefined);
});

test('equipped items have actual reward and home effects; non-equipment is rejected', () => {
  const engine = new RealityEngine({ now: () => 1_700_000_000_000 });
  engine.state.inventory.item_30 = 1;
  assert.throws(() => engine.setLoadout(['item_30']), /not equipment/i);
  engine.state.inventory.item_31 = 1;
  engine.state.inventory.item_33 = 1;
  engine.state.inventory.item_35 = 1;
  engine.setLoadout(['item_31', 'item_33', 'item_35']);
  assert.deepEqual(engine.equipmentEffects(), { xpBonus: 2, extraItem: 1, homeComfort: 3, actionXpBonus: 0, lightXpBonus: 0, encounterXpBonus: 0 });
  const event = engine.eventsFor('cell:8:9')[0];
  const result = engine.resolve({ eventId: event.id, region: event.region, clueType: event.clueType });
  assert.equal(result.reward.baseXp, event.difficulty + event.xp + 2);
  assert.equal(result.reward.xp, result.reward.baseXp);
  assert.equal(result.reward.amount, 2);
  assert.equal(engine.snapshot().inventory[result.reward.itemId], 2);
  assert.equal(engine.effectiveHabitatComfort(), 3);
});

test('finished daily activities record XP, relationship and source once; interruptions never create growth', () => {
  const engine = new RealityEngine({ now: () => 1_700_000_000_000 });
  const result = engine.recordActivityCompletion({ eventId: 'routine:focus-complete', routineId: 'focus-timer', elapsedSeconds: 600 });
  assert.equal(result.receipt.xpDelta, 4);
  assert.equal(result.receipt.relationshipDelta, 1);
  assert.equal(result.receipt.source, 'daily_activity');
  const replay = engine.recordActivityCompletion({ eventId: 'routine:focus-complete', routineId: 'focus-timer', elapsedSeconds: 600 });
  assert.equal(replay.duplicate, true);
  assert.equal(engine.snapshot().xp, 4);
  assert.equal(engine.snapshot().growthRecords.length, 1);
  assert.throws(() => engine.recordActivityCompletion({ eventId: 'routine:focus-complete', routineId: 'walk-observation', elapsedSeconds: 600 }), /reused/i);
  assert.equal(engine.snapshot().growthRecords.some(record => record.routineId === 'interrupted'), false);
});

test('crafting event IDs make response-loss retries idempotent and reject changed recipe payloads', () => {
  const engine = new RealityEngine({ now: () => 1_700_000_000_000 });
  engine.state.inventory.item_01 = 1;
  engine.state.inventory.item_08 = 1;
  const first = engine.craft('recipe_01', 'craft-event-1');
  assert.equal(first.duplicate, false);
  const replay = engine.craft('recipe_01', 'craft-event-1');
  assert.equal(replay.duplicate, true);
  assert.equal(engine.snapshot().inventory.item_21, 1);
  assert.throws(() => engine.craft('recipe_02', 'craft-event-1'), /reused/i);
});
