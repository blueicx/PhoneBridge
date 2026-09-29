const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { WorkspaceStore, createEventEnvelope, shouldApplyWorkspaceEvent } = require('./workspace-core');
const privacyEventRevisionFixture = require('../protocol-fixtures/privacy-event-revision.json');

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
  store.updateAutonomyPolicy({ allowedTools: ['device.camera'] });
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

test('shared privacy revision fixture has the same server acceptance outcomes', () => {
  for (const fixtureCase of privacyEventRevisionFixture.cases) {
    const revisions = fixtureCase.categoryRevisions;
    const privacy = {
      categoryRevision: category => revisions[category] || 0,
      isMigrationRequired: () => false,
      observeCategoryRevision: (category, revision) => { revisions[category] = Math.max(revisions[category] || 0, revision); },
    };
    const accepted = new WorkspaceStore().acceptEvent(fixtureCase.event, privacy);
    assert.equal(accepted.status || 'accepted', fixtureCase.expectedStatus, fixtureCase.id);
  }
});

test('event envelopes preserve only normalized non-negative privacy revisions', () => {
  const event = createEventEnvelope({
    type: 'workspace.message',
    privacyRevisions: { conversations: 2, progress: 0, invalid: -1, fractional: 1.5, nested: '3' },
  });
  assert.deepEqual(event.privacyRevisions, { conversations: 2, progress: 0 });
  assert.equal(Object.hasOwn(createEventEnvelope({ type: 'device.state' }), 'privacyRevisions'), false);
});

test('privacy category revisions admit legacy events only before the first deletion', () => {
  const revisions = { conversations: 0 };
  const privacy = {
    categoryRevision: category => revisions[category] || 0,
    isMigrationRequired: () => false,
    observeCategoryRevision: (category, revision) => { revisions[category] = Math.max(revisions[category] || 0, revision); },
  };
  const store = new WorkspaceStore();
  const makeMessage = (eventId, sequence, privacyRevisions) => ({
    eventId, origin: 'phone', sequence, type: 'workspace.message', payload: { text: 'message' },
    ...(privacyRevisions ? { privacyRevisions } : {}),
  });

  assert.equal(store.acceptEvent(makeMessage('legacy-at-zero', 1), privacy).accepted, true);
  revisions.conversations = 1;
  const missing = store.acceptEvent(makeMessage('missing-revision', 2), privacy);
  assert.equal(missing.status, 'privacy_revision_required');
  assert.equal(missing.category, 'conversations');
  assert.equal(store.acceptEvent(makeMessage('stale-revision', 3, { conversations: 0 }), privacy).status, 'privacy_revision_stale');
  assert.equal(store.acceptEvent(makeMessage('current-revision', 4, { conversations: 1 }), privacy).accepted, true);
  assert.equal(store.events().some(event => event.eventId === 'missing-revision'), false);
});

test('equal or newer revisions are accepted, newer revisions advance the persisted authority, and event ids stay idempotent', () => {
  const revisions = { conversations: 2 };
  const privacy = {
    categoryRevision: category => revisions[category] || 0,
    isMigrationRequired: () => false,
    observeCategoryRevision: (category, revision) => { revisions[category] = Math.max(revisions[category] || 0, revision); },
  };
  const store = new WorkspaceStore();
  const event = { eventId: 'revision-idempotency', origin: 'phone', sequence: 1, type: 'workspace.message', privacyRevisions: { conversations: 3 }, payload: {} };
  const accepted = store.acceptEvent(event, privacy);
  assert.equal(accepted.accepted, true);
  assert.equal(revisions.conversations, 3);
  const replay = store.acceptEvent({ ...event, sequence: 2 }, privacy);
  assert.equal(replay.duplicateBy, 'event_id');
  assert.equal(store.events().filter(item => item.eventId === event.eventId).length, 1);
});

test('privacy fencing maps conversation, task, attention, action and Mote events while exempting device state', () => {
  const check = (categories, type, payload = {}) => {
    const revisions = Object.fromEntries(categories.map(category => [category, 1]));
    const store = new WorkspaceStore();
    const result = store.acceptEvent({ type, origin: 'phone', sequence: 1, payload }, {
      categoryRevision: key => revisions[key] || 0,
      isMigrationRequired: () => false,
    });
    assert.equal(result.status, 'privacy_revision_required', `${type} should map to ${categories.join('+')}`);
    assert.equal(result.category, categories[0]);
  };
  check(['conversations'], 'workspace.message');
  check(['progress'], 'mote.exploration');
  check(['tasks'], 'workspace.task.progress');
  check(['conversations', 'tasks'], 'workspace.task.finished', { source: 'conversation' });
  check(['tasks'], 'workspace.attention');
  check(['conversations', 'tasks'], 'workspace.attention', { relatedSessionId: 'session-1' });
  check(['tasks'], 'workspace.action_run');
  check(['conversations', 'tasks'], 'workspace.action_run', { metadata: { messageId: 'message-1' } });

  const store = new WorkspaceStore();
  const state = store.acceptEvent({ type: 'device.state', origin: 'phone', sequence: 1, payload: { battery: 42 } }, {
    categoryRevision: () => 1,
    isMigrationRequired: () => false,
  });
  assert.equal(state.accepted, true);
  assert.equal(store.acceptEvent({ type: 'workspace.sync_state', origin: 'phone', sequence: 2, payload: {} }).accepted, true);
  assert.equal(store.acceptEvent({ type: 'workspace.policy', origin: 'phone', sequence: 3, payload: {} }).accepted, true);
  assert.equal(store.acceptEvent({ type: 'autonomy.approval', origin: 'phone', sequence: 4, payload: {} }).accepted, true);
});

test('conversation-linked task events require both category revisions and respect task-only deletion', () => {
  const store = new WorkspaceStore();
  const privacy = {
    categoryRevision: category => category === 'tasks' ? 1 : 0,
    isMigrationRequired: () => false,
  };
  const oldOutbox = store.acceptEvent({
    eventId: 'linked_job-old-task-revision', origin: 'phone', sequence: 1,
    type: 'workspace.task.progress', payload: { source: 'conversation', metadata: { sessionId: 's1' } },
    privacyRevisions: { conversations: 0 },
  }, privacy);
  assert.equal(oldOutbox.status, 'privacy_revision_required');
  assert.equal(oldOutbox.category, 'tasks');

  const currentOutbox = store.acceptEvent({
    eventId: 'linked_job-current-both-revisions', origin: 'phone', sequence: 2,
    type: 'workspace.task.progress', payload: { source: 'conversation', metadata: { sessionId: 's1' } },
    privacyRevisions: { conversations: 0, tasks: 1 },
  }, privacy);
  assert.equal(currentOutbox.accepted, true);
});

test('unknown workspace mutations fail closed and unresolved legacy migration blocks scoped sync', () => {
  const store = new WorkspaceStore();
  const unknown = store.acceptEvent({ type: 'workspace.private_blob', origin: 'phone', sequence: 1, payload: {} });
  assert.equal(unknown.status, 'unclassified_personal_event');
  const migration = store.acceptEvent({
    eventId: 'legacy-migration-pending', type: 'workspace.message', origin: 'phone', sequence: 2,
    privacyRevisions: { conversations: 1 }, payload: {},
  }, {
    categoryRevision: () => 1,
    isMigrationRequired: category => category === 'conversations',
  });
  assert.equal(migration.status, 'privacy_migration_required');
  assert.equal(store.events().length, 0);
});

test('business ACK exposes stable privacy revision and classification rejection reasons', () => {
  const { workspaceBusinessAck } = require('./workspace-core');
  assert.deepEqual(workspaceBusinessAck({ status: 'privacy_revision_required', category: 'progress' }), {
    businessStatus: 'rejected', reason: 'privacy_revision_required:progress',
  });
  assert.deepEqual(workspaceBusinessAck({ status: 'privacy_revision_stale', category: 'tasks' }), {
    businessStatus: 'rejected', reason: 'privacy_revision_stale:tasks',
  });
  assert.deepEqual(workspaceBusinessAck({ status: 'privacy_migration_required', category: 'conversations' }), {
    businessStatus: 'rejected', reason: 'privacy_migration_required:conversations',
  });
  assert.equal(workspaceBusinessAck({ status: 'unclassified_personal_event' }).reason, 'unclassified_personal_event');
});

test('replays duplicate exploration events for per-ledger recovery but skips non-idempotent duplicates', () => {
  assert.equal(shouldApplyWorkspaceEvent({ type: 'mote.exploration' }, { accepted: true, status: 'accepted' }), true);
  assert.equal(shouldApplyWorkspaceEvent({ type: 'mote.exploration' }, { accepted: false, status: 'duplicate', duplicateBy: 'event_id' }), true);
  assert.equal(shouldApplyWorkspaceEvent({ type: 'mote.exploration' }, { accepted: false, status: 'duplicate', duplicateBy: 'origin_sequence' }), false);
  assert.equal(shouldApplyWorkspaceEvent({ type: 'workspace.message' }, { accepted: false, status: 'duplicate' }), false);
  assert.equal(shouldApplyWorkspaceEvent({ type: 'workspace.message' }, { accepted: true, status: 'accepted' }), true);
});

test('global autonomy defaults to read-only tools and never allows hard-denied tools', () => {
  const store = new WorkspaceStore();
  store.registerTool({ id: 'safe.read', title: 'Safe read', readOnly: true, invoke: () => ({ ok: true }) });
  store.registerTool({ id: 'workspace.reversible', title: 'Reversible', invoke: () => ({ ok: true }) });
  const initial = store.getAutonomyPolicy();
  assert.equal(initial.level, 'whitelist');
  assert.deepEqual(initial.allowedTools, ['safe.read']);
  assert.throws(() => store.updateAutonomyPolicy({ allowedTools: ['workspace.reversible', 'shell.exec', 'delete.all'] }), /hard-denied|forbidden/i);
  const updated = store.updateAutonomyPolicy({ allowedTools: ['safe.read', 'workspace.reversible'], expiresAt: '2099-01-01T00:00:00.000Z' });
  assert.deepEqual(updated.allowedTools, ['safe.read', 'workspace.reversible']);
});

test('global autonomy expires and emergency stop blocks invocation', () => {
  let now = Date.parse('2026-01-01T00:00:00.000Z');
  const store = new WorkspaceStore({ now: () => now });
  store.registerTool({ id: 'safe.read', title: 'Safe read', readOnly: true, invoke: () => ({ ok: true }) });
  store.updateAutonomyPolicy({ allowedTools: ['safe.read'], expiresAt: '2026-01-01T00:00:01.000Z' });
  assert.equal(store.invokeRegisteredTool('safe.read').result.ok, true);
  now += 2_000;
  assert.throws(() => store.invokeRegisteredTool('safe.read'), /expired/i);
  store.updateAutonomyPolicy({ expiresAt: null });
  assert.equal(store.invokeRegisteredTool('safe.read').result.ok, true);
  store.emergencyStop('test');
  assert.throws(() => store.invokeRegisteredTool('safe.read'), /emergency/i);
});

test('task workbench creates attention for confirmation state and archives later', () => {
  const store = new WorkspaceStore();
  const task = store.createTask({ title: '需要确认的任务' });
  const waiting = store.updateTask(task.id, { state: 'needs_confirmation', detail: '等待用户选择' });
  assert.equal(waiting.state, 'needs_confirmation');
  assert.ok(store.getLatestAttentionForTask(task.id)?.title.includes('待确认'));
  assert.equal(store.updateTask(task.id, { state: 'archived' }).state, 'archived');
});

test('privacy cleanup clears conversations and task audit without retaining payload text', () => {
  const store = new WorkspaceStore();
  const unrelatedTaskEvent = store.acceptEvent({
    eventId: 'unrelated-task-event', origin: 'automation', sequence: 1, type: 'workspace.task.progress',
    payload: { id: 'automation-task', source: 'automation', title: 'keep automation task' },
  }).event;
  const unrelatedProgressEvent = store.acceptEvent({
    eventId: 'unrelated-progress-event', origin: 'phone', sequence: 1, type: 'mote.exploration',
    payload: { eventId: 'keep-progress-event', clueType: 'light' },
  }).event;
  const deletedMessageEvent = store.acceptEvent({
    eventId: 'private-message-event-key', origin: 'phone', sequence: 2, type: 'workspace.message',
    payload: { text: 'private conversation body' },
  }).event;
  const session = store.createSession({ id: 'privacy-session', title: 'private title' });
  store.appendMessage(session.id, { id: 'privacy-message', text: 'private conversation body' });
  const task = store.createTask({ id: 'privacy-task', source: 'conversation', title: 'private task', detail: 'private prompt', metadata: { sessionId: session.id } });
  store.updateTask(task.id, { state: 'running' });
  store.updateTask(task.id, { state: 'succeeded', result: 'private output' });

  const result = store.clearConversationData();
  assert.equal(result.deleted.sessions, 1);
  assert.equal(result.deleted.messages, 1);
  assert.equal(result.deleted.linkedTasks, 1);
  assert.equal(store.listSessions().length, 0);
  assert.equal(store.listTasks().length, 0);
  assert.equal(JSON.stringify(store.events()).includes('private conversation body'), false);
  assert.equal(store.events().some(event => event.eventId === unrelatedTaskEvent.eventId), true);
  assert.equal(store.events().some(event => event.eventId === unrelatedProgressEvent.eventId), true);
  assert.equal(store.eventKeys.has(`event:${deletedMessageEvent.eventId}`), false);
  assert.equal(store.eventKeys.has(`phone:${deletedMessageEvent.sequence}`), false);
  assert.equal(store.acceptEvent(unrelatedProgressEvent).status, 'duplicate');
  assert.equal(store.syncState(unrelatedProgressEvent.revision).resetRequired, true);
});

test('progress privacy deletion removes both Mote and Reality event payloads and dedupe keys', () => {
  const store = new WorkspaceStore();
  const mote = store.acceptEvent({
    eventId: 'progress-mote-event', origin: 'phone', sequence: 1, type: 'mote.exploration', payload: { clueType: 'object' },
  }).event;
  const reality = store.acceptEvent({
    eventId: 'progress-reality-event', origin: 'phone', sequence: 2, type: 'reality.clue', payload: { clueType: 'light' },
  }).event;

  store.clearProgressEvents();

  assert.deepEqual(store.events().map(event => event.type), ['privacy.purged']);
  for (const event of [mote, reality]) {
    assert.equal(store.eventKeys.has(`event:${event.eventId}`), false);
    assert.equal(store.eventKeys.has(`${event.origin}:${event.sequence}`), false);
  }
});

test('privacy deletion fences reject delayed conversation and progress outbox events', () => {
  let now = Date.parse('2026-09-29T12:00:00.000Z');
  const store = new WorkspaceStore({ now: () => now });
  const session = store.createSession({ id: 'offline-delete-session' });
  const linkedTask = store.createTask({ id: 'offline-delete-task', metadata: { sessionId: session.id } });
  store.clearConversationData();
  store.clearTaskHistory();
  store.clearProgressEvents();

  const staleMessage = store.acceptEvent({
    eventId: 'offline-message-before-delete', origin: 'phone', sequence: 40, type: 'workspace.message',
    createdAt: new Date(now - 1).toISOString(), payload: { text: 'must not return' },
  });
  const staleProgress = store.acceptEvent({
    eventId: 'offline-progress-before-delete', origin: 'phone', sequence: 41, type: 'mote.exploration',
    createdAt: new Date(now - 1).toISOString(), payload: { clueType: 'light' },
  });

  assert.equal(staleMessage.status, 'privacy_deleted');
  assert.equal(staleProgress.status, 'privacy_deleted');
  const invalidTimestamp = store.acceptEvent({
    eventId: 'offline-event-with-invalid-timestamp', origin: 'phone', sequence: 44, type: 'workspace.message',
    createdAt: 'not-a-timestamp', payload: { text: 'must not bypass deletion fence' },
  });
  assert.equal(invalidTimestamp.status, 'privacy_deleted');
  const staleAttention = store.acceptEvent({
    eventId: 'offline-attention-before-delete', origin: 'phone', sequence: 43, type: 'workspace.attention',
    createdAt: new Date(now - 1).toISOString(), payload: { relatedTaskId: linkedTask.id, title: 'stale approval' },
  });
  assert.equal(staleAttention.status, 'privacy_deleted');
  assert.equal(staleAttention.category, 'conversations');
  assert.equal(store.events().some(event => event.eventId === staleMessage.event.eventId), false);
  assert.equal(store.events().some(event => event.eventId === staleProgress.event.eventId), false);

  now += 1;
  const newMessage = store.acceptEvent({
    eventId: 'new-message-after-delete', origin: 'phone', sequence: 42, type: 'workspace.message',
    createdAt: new Date(now + 1).toISOString(), payload: { text: 'allowed new conversation' },
  });
  assert.equal(newMessage.accepted, true);
});

test('privacy event fences survive process restart without retaining deleted event payloads', () => {
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-privacy-fence-'));
  const snapshotPath = path.join(runtimeDir, 'workspace-state.json');
  const deletedAt = Date.parse('2026-09-29T12:00:00.000Z');
  try {
    const first = new WorkspaceStore({ now: () => deletedAt, snapshotPath });
    first.clearConversationData();
    const restored = new WorkspaceStore({ now: () => deletedAt + 10_000, snapshotPath });
    const stale = restored.acceptEvent({
      eventId: 'offline-after-restart', origin: 'phone', sequence: 99, type: 'workspace.message',
      createdAt: new Date(deletedAt - 1).toISOString(), payload: { text: 'not persisted in fence' },
    });
    assert.equal(stale.status, 'privacy_deleted');
    assert.equal(fs.readFileSync(snapshotPath, 'utf8').includes('not persisted in fence'), false);
  } finally {
    fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
});

test('privacy cleanup clears task records but preserves autonomy settings', () => {
  const fixedNow = () => Date.parse('2026-09-30T00:00:00.000Z');
  const store = new WorkspaceStore({ now: fixedNow });
  const task = store.createTask({ title: 'task to erase' });
  store.updateTask(task.id, { state: 'running' });
  store.updateTask(task.id, { state: 'succeeded' });
  store.registerTool({ id: 'safe.read', readOnly: true, invoke: () => true });
  const beforePolicy = store.getAutonomyPolicy();

  const result = store.clearTaskHistory();
  assert.equal(result.deleted, 1);
  assert.equal(store.listTasks().length, 0);
  assert.deepEqual(store.getAutonomyPolicy(), beforePolicy);
  assert.equal(JSON.stringify(store.events()).includes('task to erase'), false);
});
