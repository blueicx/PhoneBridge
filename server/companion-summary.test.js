'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildCompanionSummary } = require('./companion-summary');

test('companion summary projects task, attention, mote, health and AI state without secrets', () => {
  const summary = buildCompanionSummary({
    generatedAt: 123,
    snapshot: {
      tasks: [
        { id: 't1', state: 'running', progress: 42, title: '同步' },
        { id: 't2', state: 'needs_confirmation', progress: 10, title: '发布' },
        { id: 't3', state: 'succeeded', progress: 100, title: '完成' },
        { id: 't4', state: 'failed', progress: 30, title: '失败' },
      ],
      workspace: { attention: [{ id: 'a1', severity: 'high', status: 'open', title: '确认' }] },
      motes: {
        state: { activeId: 'ember_sprig' },
        roster: [{ id: 'ember_sprig', name: '焰芽', active: true, unlocked: true }],
        relationship: { level: 3, xp: 18 },
        behavior: { gaze: 'focused', reminderStrength: 0.7 },
      },
      deviceHealth: { connected: true, battery: 76, temperature: 32.5 },
      autonomy: { level: 'whitelist', emergencyStop: { active: false } },
    },
    ai: {
      activeProviderId: 'local',
      providers: [{ id: 'local', name: '本地离线规则', status: 'ready', isActive: true }],
      budgetRemaining: 1200,
      degradationCount: 2,
      apiKey: 'must-not-appear',
    },
    memory: { count: 7, revision: 4 },
    reality: {
      region: 'cell:1:2',
      events: [{ id: 'e1' }, { id: 'e2' }],
      state: { level: 2, xp: 33, inventory: { item_01: 2 }, seenEventIds: ['e0'] },
    },
  });

  assert.deepEqual(summary, {
    version: 1,
    generatedAt: 123,
    connection: { online: true, battery: 76, temperature: 32.5 },
    tasks: { total: 4, pending: 0, running: 1, needsConfirmation: 1, completed: 1, failed: 1, activeId: 't1' },
    attention: { open: 1, highestSeverity: 'high' },
    mote: { id: 'ember_sprig', name: '焰芽', level: 3, xp: 18, gaze: 'focused', reminderStrength: 0.7 },
    reality: { region: 'cell:1:2', eventCount: 2, level: 2, xp: 33, inventoryCount: 2, seenEventCount: 1 },
    ai: { providerId: 'local', providerName: '本地离线规则', status: 'ready', budgetRemaining: 1200, degradationCount: 2, memoryCount: 7, memoryRevision: 4 },
    safety: { autonomyLevel: 'whitelist', emergencyStop: false },
  });
  assert.equal(JSON.stringify(summary).includes('must-not-appear'), false);
});

test('companion summary handles empty or malformed projections safely', () => {
  const summary = buildCompanionSummary({ snapshot: null, ai: null, memory: null, reality: null });
  assert.equal(summary.version, 1);
  assert.equal(summary.connection.online, false);
  assert.equal(summary.tasks.total, 0);
  assert.equal(summary.mote.id, 'rimuru');
  assert.equal(summary.ai.providerId, 'local');
});
