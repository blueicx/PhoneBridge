'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EXPLORATION_ENCOUNTERS, describeExplorationEncounter, resolveExplorationEncounter } = require('./exploration-encounters');

test('twelve authored encounters give four distinct non-punitive action outcomes', () => {
  assert.equal(EXPLORATION_ENCOUNTERS.length, 12);
  assert.equal(new Set(EXPLORATION_ENCOUNTERS.map(item => item.id)).size, 12);
  for (const encounter of EXPLORATION_ENCOUNTERS) {
    assert.ok(encounter.title);
    assert.ok(encounter.prompt);
    assert.deepEqual(Object.keys(encounter.outcomes).sort(), ['dodge', 'observe', 'skill', 'soothe']);
    assert.equal(new Set(Object.values(encounter.outcomes)).size, 4, encounter.id);
  }
});

test('encounter, action result and bounded bonus are deterministic and Mote-aware', () => {
  const first = resolveExplorationEncounter({
    eventId: 'reality-lens:v2:2026-10-14:camera:object:sample-1',
    clueType: 'object',
    action: 'skill',
    encounterId: 'encounter_01',
    moteId: 'sprite',
  });
  const replay = resolveExplorationEncounter({
    eventId: 'reality-lens:v2:2026-10-14:camera:object:sample-1',
    clueType: 'object',
    action: 'skill',
    encounterId: 'encounter_01',
    moteId: 'sprite',
  });

  assert.deepEqual(first, replay);
  assert.equal(first.id, 'echo-beacon');
  assert.equal(first.title, '回声信标');
  assert.equal(first.actionLabel, '使用 Mote 特长');
  assert.equal(first.quality, '默契契合');
  assert.equal(first.moteName, '叶狐');
  assert.equal(first.xpBonus, 2);
  assert.ok(first.outcome.length > 0);
  assert.ok(first.prompt.length > 0);
  assert.equal(resolveExplorationEncounter({ eventId: 'stable', action: 'observe', encounterId: 'encounter_01' }).xpBonus, 1);
});

test('starting an encounter reveals its authored clue without leaking an unselected outcome', () => {
  const description = describeExplorationEncounter({ encounterId: 'encounter_01', moteId: 'sprite' });

  assert.deepEqual(description, {
    id: 'echo-beacon',
    title: '回声信标',
    prompt: '断续的脉冲正从边缘传来，先判断它在回应什么。',
    moteId: 'sprite',
    moteName: '叶狐',
  });
  assert.equal(Object.hasOwn(description, 'outcome'), false);
});

test('unknown Mote/action input falls back safely and cannot inject narrative or bonus', () => {
  const result = resolveExplorationEncounter({
    eventId: 'stable-event',
    action: 'delete',
    encounterId: 'encounter_01',
    moteId: 'attacker-controlled',
    xpBonus: 99999,
    outcome: 'PRIVATE-INJECTION',
  });

  assert.equal(result.action, 'observe');
  assert.equal(result.moteId, 'mote');
  assert.equal(result.moteName, '星核');
  assert.equal(result.xpBonus, 1);
  assert.equal(JSON.stringify(result).includes('PRIVATE-INJECTION'), false);
  assert.equal(JSON.stringify(result).includes('99999'), false);
});
