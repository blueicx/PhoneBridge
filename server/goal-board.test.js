'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { WorkspaceStore } = require('./workspace-core');
const { AiProviderManager } = require('./ai-provider');

let GoalBoardService = null;
let normalizeSteps = null;
try { ({ GoalBoardService, normalizeSteps } = require('./goal-board')); }
catch (error) { if (error.code !== 'MODULE_NOT_FOUND') throw error; }

test('exports a dedicated goal board domain service', () => {
  assert.equal(typeof GoalBoardService, 'function');
});

test('shared Android goal and draft fixtures create ordinary milestone-linked tasks', () => {
  const fixtureDir = path.join(__dirname, '..', 'protocol-fixtures');
  const boardFixture = JSON.parse(fs.readFileSync(path.join(fixtureDir, 'goal-board.json'), 'utf8'));
  const draftFixture = JSON.parse(fs.readFileSync(path.join(fixtureDir, 'goal-draft.json'), 'utf8'));
  const steps = normalizeSteps(draftFixture.steps);
  const { workspaceStore, service } = createBoard();
  const goal = service.createGoal({ title: boardFixture.goals[0].title, description: boardFixture.goals[0].description });
  const result = service.acceptDraft(goal.id, { eventId: 'goal-fixture-accept-001', steps });

  assert.equal(draftFixture.goalId, boardFixture.goals[0].id);
  assert.equal(result.tasks.length, steps.length);
  assert.equal(result.goal.milestones.length, steps.length);
  assert.ok(result.tasks.every(task => task.metadata.goalId === goal.id && task.metadata.milestoneId));
  assert.equal(workspaceStore.listTasks().length, steps.length);
});

function createBoard({ providerManager = null, isTaskActive = () => false, recordProviderAudit = () => {} } = {}) {
  let now = Date.parse('2026-09-30T12:00:00.000Z');
  const workspaceStore = new WorkspaceStore({ now: () => now });
  const service = new GoalBoardService({
    workspaceStore,
    providerManager,
    now: () => now,
    isTaskActive,
    recordProviderAudit,
  });
  return { workspaceStore, service, setNow: value => { now = value; } };
}

test('creates and edits bounded goal text without accepting unrelated fields', () => {
  const { service } = createBoard();
  const goal = service.createGoal({ title: '学会基础日语', description: '每周安排两次短练习' });
  const updated = service.updateGoal(goal.id, { title: '完成日语入门', description: '以自定节奏推进' });

  assert.equal(updated.id, goal.id);
  assert.equal(updated.status, 'active');
  assert.equal(updated.title, '完成日语入门');
  assert.equal(updated.description, '以自定节奏推进');
  assert.equal(updated.createdAt, goal.createdAt);
  assert.ok(Date.parse(updated.updatedAt) >= Date.parse(goal.updatedAt));
  assert.throws(() => service.updateGoal(goal.id, { title: '非法', status: 'completed' }), /unsupported goal field/);
  assert.throws(() => service.createGoal({ title: '  ' }), /title/);
});

test('accepts user-edited steps once and returns the ordinary workspace tasks', () => {
  const { workspaceStore, service } = createBoard();
  const goal = service.createGoal({ title: '完成一个小项目' });
  assert.equal(workspaceStore.listTasks().length, 0);

  const payload = {
    eventId: 'goal-accept-service-001',
    steps: [
      { title: '列出最小需求', description: '只保留第一版需要的内容' },
      { title: '完成可运行版本', description: '先做一条完整路径' },
    ],
  };
  const accepted = service.acceptDraft(goal.id, payload);
  const replay = service.acceptDraft(goal.id, payload);

  assert.equal(accepted.duplicate, false);
  assert.equal(accepted.tasks.length, 2);
  assert.equal(accepted.milestones.length, 2);
  assert.equal(accepted.tasks[0].metadata.goalId, goal.id);
  assert.equal(accepted.tasks[0].metadata.milestoneId, accepted.milestones[0].id);
  assert.equal(replay.duplicate, true);
  assert.deepEqual(replay.tasks.map(item => item.id), accepted.tasks.map(item => item.id));
  assert.equal(workspaceStore.listTasks().length, 2);
  assert.throws(() => service.acceptDraft(goal.id, {
    ...payload,
    steps: [{ title: '重复 ID 不得绑定不同内容' }],
  }), /eventId.*different input/);
});

test('rejects empty, invalid, and excessive accepted steps before any task is created', () => {
  const { workspaceStore, service } = createBoard();
  const goal = service.createGoal({ title: '受限步骤' });

  for (const steps of [[], [{ title: ' ' }], Array.from({ length: 9 }, (_, index) => ({ title: `第 ${index + 1} 步` }))]) {
    assert.throws(() => service.acceptDraft(goal.id, { eventId: `goal-invalid-${steps.length}-001`, steps }));
  }
  assert.equal(workspaceStore.listTasks().length, 0);
  assert.equal(service.getGoal(goal.id).milestones.length, 0);
});

test('blocks goal deletion while its task runner record is active', () => {
  let active = true;
  const { workspaceStore, service } = createBoard({ isTaskActive: () => active });
  const goal = service.createGoal({ title: '稍后再删' });
  const accepted = service.acceptDraft(goal.id, {
    eventId: 'goal-active-delete-001', steps: [{ title: '运行任务' }],
  });

  assert.throws(() => service.deleteGoal(goal.id), error => error.statusCode === 409 && /active goal task/.test(error.message));
  assert.ok(workspaceStore.getGoal(goal.id));
  active = false;
  assert.equal(service.deleteGoal(goal.id).deleted, true);
  assert.equal(workspaceStore.getTask(accepted.tasks[0].id), null);
});

test('checks active goal tasks even when they fall outside the task list projection limit', () => {
  let now = Date.parse('2026-09-30T12:00:00.000Z');
  const workspaceStore = new WorkspaceStore({ now: () => now++ });
  const goal = workspaceStore.createGoal({ title: '旧目标任务' });
  const activeTask = workspaceStore.createTask({
    id: 'goal-active-task-outside-list', source: 'goal', title: '仍在执行', metadata: { goalId: goal.id },
  });
  const service = new GoalBoardService({
    workspaceStore,
    isTaskActive: taskId => taskId === activeTask.id,
  });
  for (let index = 0; index < 205; index += 1) {
    workspaceStore.createTask({ id: `newer-task-${String(index).padStart(3, '0')}`, title: `较新任务 ${index}` });
  }

  assert.equal(workspaceStore.listTasks({ limit: 200 }).some(task => task.id === activeTask.id), false);
  assert.throws(() => service.deleteGoal(goal.id), error => error.statusCode === 409 && /active goal task/.test(error.message));
  assert.ok(workspaceStore.getGoal(goal.id));
});

test('calls only the selected provider on explicit draft request and uses local fallback', async () => {
  let primaryCalls = 0;
  let backupCalls = 0;
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    adapters: {
      openai: { chat: async () => { primaryCalls += 1; throw new Error('private prompt and sk-secret'); } },
      gemini: { chat: async () => { backupCalls += 1; return { reply: '{"steps":[]}' }; } },
      local: { chat: async () => ({ reply: '本地离线回复，不是可用计划 JSON' }) },
    },
  });
  const recorded = [];
  const persistenceValues = new Map();
  const persistence = {
    load(key, fallback) { return persistenceValues.has(key) ? structuredClone(persistenceValues.get(key)) : fallback; },
    save(key, value) { persistenceValues.set(key, structuredClone(value)); },
  };
  const workspaceStore = new WorkspaceStore({ persistence });
  const service = new GoalBoardService({
    workspaceStore,
    providerManager: manager,
    now: () => Date.parse('2026-09-30T12:00:00.000Z'),
    recordProviderAudit: item => recorded.push(item),
  });
  const goal = service.createGoal({ title: '搭建个人作品集', description: '只用离线回退' });
  const beforeSnapshot = structuredClone(persistenceValues.get('workspace-state'));
  assert.equal(primaryCalls, 0, 'draft generation must not happen before an explicit request');

  const draft = await service.draftGoal(goal.id, { context: '不需要外部联网备用 provider' });

  assert.equal(primaryCalls, 1);
  assert.equal(backupCalls, 0);
  assert.ok(draft.steps.length > 0 && draft.steps.length <= 8);
  assert.equal(draft.providerId, 'openai');
  assert.equal(draft.fallbackProvider, 'local');
  assert.equal(workspaceStore.listTasks().length, 0);
  assert.deepEqual(workspaceStore.getGoal(goal.id), goal);
  assert.deepEqual(persistenceValues.get('workspace-state'), beforeSnapshot, 'draft content must not be persisted');
  assert.deepEqual(recorded, [draft.providerAudit]);
  assert.deepEqual(Object.keys(draft.providerAudit).sort(), ['elapsedMs', 'fallbackReason', 'providerId', 'resultStatus'].sort());
  assert.equal(JSON.stringify(draft.providerAudit).includes('搭建个人作品集'), false);
  assert.equal(JSON.stringify(draft.providerAudit).includes('sk-secret'), false);
  assert.equal(JSON.stringify(service.listDraftAudits()).includes('private prompt'), false);
});

test('falls back locally when selected provider output violates the draft schema', async () => {
  let backupCalls = 0;
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    adapters: {
      openai: { chat: async () => ({ reply: '{"steps":[]}' }) },
      gemini: { chat: async () => { backupCalls += 1; return { reply: '{"steps":[{"title":"backup"}]}' }; } },
      local: { chat: async () => ({ reply: 'local text' }) },
    },
  });
  const { service } = createBoard({ providerManager: manager });
  const goal = service.createGoal({ title: '目标' });
  const draft = await service.draftGoal(goal.id);

  assert.equal(backupCalls, 0);
  assert.equal(draft.fallbackProvider, 'local');
  assert.equal(draft.providerAudit.fallbackReason, 'provider_output_invalid');
  assert.ok(draft.steps.length > 0);
});

test('rejects overlong draft context before contacting any provider', async () => {
  let calls = 0;
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    adapters: { openai: { chat: async () => { calls += 1; return { reply: '{}' }; } } },
  });
  const { service } = createBoard({ providerManager: manager });
  const goal = service.createGoal({ title: '目标' });

  await assert.rejects(service.draftGoal(goal.id, { context: 'x'.repeat(1001) }), /context/);
  assert.equal(calls, 0);
});
