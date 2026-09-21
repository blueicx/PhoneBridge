'use strict';

const SEVERITY_RANK = Object.freeze({ critical: 4, high: 3, medium: 2, low: 1 });

function list(value) {
  return Array.isArray(value) ? value : [];
}

function object(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function number(value, fallback = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function taskState(task) {
  return String(task?.state || task?.status || 'pending').toLowerCase();
}

function buildCompanionSummary({
  generatedAt = Date.now(),
  snapshot = {},
  ai = {},
  memory = {},
  reality = {},
} = {}) {
  const root = object(snapshot);
  const tasks = list(root.tasks);
  const attention = list(root.attention || root.workspace?.attention);
  const moteRoot = object(root.motes);
  const moteState = object(moteRoot.state);
  const roster = list(moteRoot.roster);
  const relationship = object(moteRoot.relationship);
  const behavior = object(moteRoot.behavior);
  const health = object(root.deviceHealth || root.health);
  const autonomy = object(root.autonomy);
  const aiState = object(ai);
  const realityRoot = object(reality);
  const memoryState = object(memory);
  const selectedProviderId = String(aiState.activeProviderId || 'local');
  const providers = list(aiState.providers);
  const provider = providers.find(item => String(item?.id) === selectedProviderId) || providers.find(item => item?.isActive) || providers[0] || {};
  const activeMoteId = String(moteState.activeId || roster.find(item => item?.active)?.id || 'rimuru');
  const activeMote = roster.find(item => String(item?.id) === activeMoteId) || {};
  const activeTasks = tasks.filter(task => ['pending', 'queued', 'running', 'paused', 'needs_confirmation'].includes(taskState(task)));
  const openAttention = attention.filter(item => !['resolved', 'dismissed', 'ignored', 'archived'].includes(String(item?.status || 'open').toLowerCase()));
  const highestSeverity = openAttention
    .map(item => String(item?.severity || 'low').toLowerCase())
    .sort((a, b) => (SEVERITY_RANK[b] || 0) - (SEVERITY_RANK[a] || 0))[0] || 'none';
  const realityState = object(realityRoot.state);
  const inventory = object(realityState.inventory);
  const emergencyStop = autonomy.emergencyStop?.active === true || autonomy.emergencyStop === true;

  return {
    version: 1,
    generatedAt: number(generatedAt),
    connection: {
      online: Boolean(
        health.connected ||
        ['connected', 'online', 'active'].includes(String(health.bridge || '').toLowerCase()) ||
        ['connected', 'online', 'active'].includes(String(health.node || '').toLowerCase())
      ),
      battery: number(health.battery),
      temperature: number(health.temperature),
    },
    tasks: {
      total: tasks.length,
      pending: tasks.filter(task => ['pending', 'queued'].includes(taskState(task))).length,
      running: tasks.filter(task => ['running', 'paused'].includes(taskState(task))).length,
      needsConfirmation: tasks.filter(task => taskState(task) === 'needs_confirmation').length,
      completed: tasks.filter(task => ['succeeded', 'done', 'completed'].includes(taskState(task))).length,
      failed: tasks.filter(task => ['failed', 'error', 'cancelled'].includes(taskState(task))).length,
      activeId: activeTasks[0]?.id || null,
    },
    attention: {
      open: openAttention.length,
      highestSeverity,
    },
    mote: {
      id: activeMoteId,
      name: String(activeMote.name || '利姆鲁'),
      level: Math.max(1, number(relationship.level, number(moteState.level, 1))),
      xp: Math.max(0, number(relationship.xp, number(moteState.xp))),
      gaze: String(behavior.gaze || 'ambient'),
      reminderStrength: Math.max(0, Math.min(1, number(behavior.reminderStrength))),
    },
    reality: {
      region: String(realityRoot.region || realityState.region || ''),
      eventCount: list(realityRoot.events).length,
      level: Math.max(1, number(realityState.level, 1)),
      xp: Math.max(0, number(realityState.xp)),
      inventoryCount: Object.values(inventory).reduce((sum, value) => sum + Math.max(0, number(value)), 0),
      seenEventCount: list(realityState.seenEventIds).length,
    },
    ai: {
      providerId: selectedProviderId,
      providerName: String(provider.name || selectedProviderId),
      status: String(provider.status || 'unknown'),
      budgetRemaining: Math.max(0, number(aiState.budgetRemaining)),
      degradationCount: Math.max(0, number(aiState.degradationCount)),
      memoryCount: Math.max(0, number(memoryState.count)),
      memoryRevision: Math.max(0, number(memoryState.revision)),
    },
    safety: {
      autonomyLevel: String(autonomy.level || 'whitelist'),
      emergencyStop,
    },
  };
}

module.exports = { buildCompanionSummary };
