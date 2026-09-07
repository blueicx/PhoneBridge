const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { WorkspaceStore, createEventEnvelope } = require('./workspace-core');

test('protocol fixture uses the shared event envelope', () => {
  const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'protocol-fixtures', 'workspace-event.json'), 'utf8'));
  const store = new WorkspaceStore();
  assert.equal(store.acceptEvent(fixture).accepted, true);
  assert.equal(fixture.type, 'workspace.message');
  assert.equal(fixture.payload.role, 'user');
});

test('creates sessions with per-session model settings and appends messages', () => {
  const store = new WorkspaceStore({ now: () => 1000 });
  const session = store.createSession({ title: '研究', providerId: 'local', model: 'small-cn' });

  assert.equal(session.providerId, 'local');
  assert.equal(session.model, 'small-cn');
  assert.equal(store.appendMessage(session.id, { role: 'user', text: '你好' }).text, '你好');
  assert.equal(store.getSession(session.id).messages.length, 1);
});

test('accepts an event once and deduplicates repeated origin sequences', () => {
  const store = new WorkspaceStore({ now: () => 2000 });
  const event = createEventEnvelope({ origin: 'phone-a', sequence: 1, type: 'chat.message', payload: { text: 'hi' }, now: 2000 });

  assert.equal(store.acceptEvent(event).accepted, true);
  assert.equal(store.acceptEvent({ ...event, eventId: 'different-id' }).accepted, false);
  assert.equal(store.events().length, 1);
});

test('restored event log still deduplicates event ids when loading an older snapshot', () => {
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-event-restore-'));
  const snapshotPath = path.join(runtimeDir, 'workspace-state.json');
  try {
    const firstStore = new WorkspaceStore({ snapshotPath });
    const event = createEventEnvelope({ origin: 'phone', sequence: 4, type: 'workspace.message', payload: {} });
    assert.equal(firstStore.acceptEvent(event).accepted, true);

    const legacySnapshot = JSON.parse(fs.readFileSync(snapshotPath, 'utf8'));
    delete legacySnapshot.eventKeys;
    fs.writeFileSync(snapshotPath, JSON.stringify(legacySnapshot));

    const restoredStore = new WorkspaceStore({ snapshotPath });
    assert.equal(restoredStore.acceptEvent({ ...event, sequence: 5 }).accepted, false);
  } finally {
    fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
});

test('requires an armed session for registered tool invocation and records an audit entry', () => {
  const store = new WorkspaceStore({ now: () => 3000 });
  const session = store.createSession({ title: '工具' });
  store.registerTool({ id: 'device.camera', title: '相机', invoke: args => ({ action: args.action || 'camera_on' }) });

  assert.throws(() => store.invokeTool(session.id, 'device.camera', { action: 'camera_on' }), /authorization/i);
  store.armSession(session.id, 60_000);
  const result = store.invokeTool(session.id, 'device.camera', { action: 'camera_on' });
  assert.deepEqual(result.result, { action: 'camera_on' });
  assert.equal(store.auditLog().length, 1);
});

test('expires authorization after idle timeout and supports emergency stop', () => {
  let now = 4000;
  const store = new WorkspaceStore({ now: () => now });
  const session = store.createSession({ title: '安全' });
  store.registerTool({ id: 'readonly.status', title: '状态', invoke: () => ({ ok: true }), readOnly: true });
  store.armSession(session.id, 1000);
  assert.equal(store.isSessionArmed(session.id), true);
  now = 6001;
  assert.equal(store.isSessionArmed(session.id), false);
  store.armSession(session.id, 10_000);
  store.emergencyStop('test');
  assert.equal(store.isSessionArmed(session.id), false);
  assert.equal(store.emergencyStopState().active, true);
});

test('tracks unified task lifecycle and creates automation run records', () => {
  const store = new WorkspaceStore({ now: () => 5000 });
  const task = store.createTask({ source: 'conversation', title: '执行检查' });
  assert.equal(store.updateTask(task.id, { state: 'running', progress: 25 }).progress, 25);
  assert.equal(store.updateTask(task.id, { state: 'succeeded', progress: 100 }).state, 'succeeded');

  const automation = store.createAutomation({ name: '低电量提醒', trigger: { type: 'device', field: 'battery', op: 'lt', value: 20 }, actions: [] });
  const runs = store.evaluateAutomations({ battery: 10 });
  assert.equal(runs.length, 1);
  assert.equal(runs[0].automationId, automation.id);
});

test('runs scheduled automations only after their interval elapses', () => {
  let now = 10_000;
  const store = new WorkspaceStore({ now: () => now });
  const automation = store.createAutomation({ name: '定时巡检', trigger: { type: 'schedule', intervalMs: 5_000 }, actions: [] });
  assert.equal(store.evaluateAutomations({}).length, 1);
  now = 12_000;
  assert.equal(store.evaluateAutomations({}).length, 0);
  now = 15_001;
  assert.equal(store.evaluateAutomations({}).length, 1);
  assert.equal(store.automationRuns().filter(run => run.automationId === automation.id).length, 2);
});

test('persists attention items and autonomy policies while deduplicating by dedupe key', () => {
  let now = 20_000;
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-workspace-core-'));
  const snapshotPath = path.join(runtimeDir, 'workspace-state.json');
  const store = new WorkspaceStore({ now: () => now, snapshotPath });
  const session = store.createSession({ title: '提醒流' });

  store.updateSessionPolicy(session.id, {
    level: 'observe',
    allowedTools: ['device.telemetry'],
    continuousMic: false,
    confirmationRules: [{ toolId: 'device.camera', requireConfirmation: true }],
  });
  const first = store.upsertAttentionItem({
    source: 'proactive',
    severity: 'medium',
    title: '低电量提醒',
    summary: '电量低于 15%',
    relatedSessionId: session.id,
    dedupeKey: 'battery:low',
  });
  now = 21_000;
  const second = store.upsertAttentionItem({
    source: 'proactive',
    severity: 'high',
    title: '低电量提醒',
    summary: '再次提醒',
    relatedSessionId: session.id,
    dedupeKey: 'battery:low',
  });

  assert.equal(second.id, first.id);
  assert.equal(store.listAttentionItems().length, 1);
  assert.equal(store.getSessionPolicy(session.id).level, 'observe');
  assert.equal(store.getSessionPolicy(session.id).continuousMic, false);

  const restored = new WorkspaceStore({ now: () => now, snapshotPath });
  assert.equal(restored.listAttentionItems().length, 1);
  assert.equal(restored.listAttentionItems()[0].summary, '再次提醒');
  assert.equal(restored.getSessionPolicy(session.id).allowedTools[0], 'device.telemetry');
});

test('records sanitized action runs, links task-producing tools, and enforces autonomy policy gates', () => {
  let now = 30_000;
  const store = new WorkspaceStore({ now: () => now });
  const session = store.createSession({ title: '策略' });
  store.registerTool({ id: 'device.telemetry', title: '状态', readOnly: true, invoke: () => ({ ok: true }) });
  store.registerTool({ id: 'device.listen', title: '麦克风', invoke: ({ enabled = true }) => ({ enabled: Boolean(enabled) }) });
  store.registerTool({
    id: 'task.create',
    title: '创建任务',
    invoke: ({ title, detail = '' }, context) => context.store.createTask({ title, detail, source: 'tool' }),
  });
  store.armSession(session.id, 60_000);
  store.updateSessionPolicy(session.id, {
    level: 'observe',
    allowedTools: ['device.telemetry', 'task.create', 'device.listen'],
    continuousMic: false,
  });

  const telemetry = store.invokeTool(session.id, 'device.telemetry', { token: 'secret-token-value' });
  assert.equal(telemetry.result.ok, true);

  assert.throws(
    () => store.invokeTool(session.id, 'device.listen', { enabled: true, authorization: 'Bearer super-secret' }),
    /policy|observe|continuous/i,
  );

  store.updateSessionPolicy(session.id, {
    level: 'reversible',
    allowedTools: ['device.telemetry', 'task.create', 'device.listen'],
    continuousMic: true,
  });
  const created = store.invokeTool(session.id, 'task.create', { title: '巡检任务', detail: '不要泄露 password=123456' });
  const actionRuns = store.listActionRuns();
  const taskRun = actionRuns.find(run => run.id === created.actionRunId);
  const blockedRun = actionRuns.find(run => run.toolId === 'device.listen');

  assert.equal(taskRun.taskId, created.result.id);
  assert.equal(blockedRun.state, 'blocked');
  assert.match(blockedRun.argsSummary, /REDACTED/);
  assert.doesNotMatch(blockedRun.argsSummary, /super-secret|123456/);

  const failedTask = store.updateTask(created.result.id, { state: 'failed', error: '执行失败' });
  assert.equal(failedTask.state, 'failed');
  assert.ok(store.listAttentionItems().some(item => item.relatedTaskId === created.result.id && item.source === 'task'));
});

test('blocks automation actions only for hard-stop conditions, not ordinary offline execution', () => {
  let now = 40_000;
  const store = new WorkspaceStore({ now: () => now });
  const automation = store.createAutomation({ name: '自动巡检', actions: [] });
  store.registerTool({ id: 'device.camera', title: '相机', invoke: ({ action = 'camera_on' }) => ({ action, online: false }) });
  store.updateAutomationPolicy(automation.id, { level: 'reversible', allowedTools: ['device.camera'] });

  const first = store.invokeAutomationTool(automation.id, 'device.camera', { action: 'camera_on' });
  assert.equal(first.result.online, false);
  assert.equal(store.getActionRun(first.actionRunId).state, 'succeeded');

  store.updateAutomationPolicy(automation.id, { expiresAt: new Date(now - 1).toISOString() });
  assert.throws(
    () => store.invokeAutomationTool(automation.id, 'device.camera', { action: 'camera_back' }),
    /expired|policy/i,
  );

  store.updateAutomationPolicy(automation.id, { expiresAt: null });
  store.updateAutomation(automation.id, { enabled: false });
  assert.throws(
    () => store.invokeAutomationTool(automation.id, 'device.camera', { action: 'camera_front' }),
    /disabled|automation/i,
  );

  store.updateAutomation(automation.id, { enabled: true });
  store.emergencyStop('manual');
  assert.throws(
    () => store.invokeAutomationTool(automation.id, 'device.camera', { action: 'camera_off' }, { expiresAt: new Date(now - 1).toISOString() }),
    /emergency|expired/i,
  );
  assert.ok(store.listActionRuns().some(run => run.origin === 'automation' && (run.state === 'blocked' || run.state === 'cancelled')));
});
