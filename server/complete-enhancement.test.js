const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { WorkspaceStore } = require('./workspace-core');
const { deriveMoteBehavior } = require('./mote-profiles');

function store() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-complete-'));
  return new WorkspaceStore({ snapshotPath: path.join(dir, 'state.json'), now: () => 1_700_000_000_000 });
}

test('task actions enforce transitions, are idempotent, and expose audit history', () => {
  const workspace = store();
  const task = workspace.createTask({ title: '同步任务' });
  assert.equal(workspace.applyTaskAction(task.id, 'start', { idempotencyKey: 'start-1' }).state, 'running');
  assert.throws(() => workspace.applyTaskAction(task.id, 'archive'), /cannot archive/i);
  const paused = workspace.applyTaskAction(task.id, 'pause', { idempotencyKey: 'pause-1' });
  assert.equal(paused.state, 'paused');
  assert.deepEqual(workspace.applyTaskAction(task.id, 'pause', { idempotencyKey: 'pause-1' }), paused);
  assert.equal(workspace.listTaskAudit(task.id).length, 2);
});

test('task list supports state filters and bounded audit records', () => {
  const workspace = store();
  const task = workspace.createTask({ title: '筛选任务' });
  workspace.applyTaskAction(task.id, 'start');
  assert.equal(workspace.listTasks({ state: 'running' }).length, 1);
  assert.equal(workspace.listTasks({ state: 'failed' }).length, 0);
  assert.equal(workspace.listTaskAudit(task.id)[0].toState, 'running');
});

test('autonomy guards arguments, revisions, and one-shot leases', () => {
  const workspace = store();
  let calls = 0;
  workspace.registerTool({
    id: 'safe.note',
    readOnly: false,
    argumentSchema: { allowedKeys: ['text'], required: ['text'], types: { text: 'string' }, maxStringLength: 30 },
    invoke: () => { calls += 1; return { ok: true }; },
  });
  const policy = workspace.updateAutonomyPolicy({ allowedTools: ['safe.note'], usesRemaining: 1 });
  assert.equal(policy.revision, 1);
  assert.throws(() => workspace.updateAutonomyPolicy({ revision: 0, allowedTools: [] }), /revision/i);
  assert.throws(() => workspace.invokeTool('missing-session', 'safe.note', { text: 'missing session' }), /session (not found|authorization)/i);
  assert.throws(() => workspace.invokeGlobalTool('safe.note', { extra: true }), /argument/i);
  workspace.invokeGlobalTool('safe.note', { text: 'ok' });
  assert.equal(calls, 1);
  assert.throws(() => workspace.invokeGlobalTool('safe.note', { text: 'again' }), /lease|uses/i);
});

test('event revisions support resumable sync and duplicate acknowledgements', () => {
  const workspace = store();
  const first = workspace.acceptEvent({ eventId: 'event-a', origin: 'phone', sequence: 1, type: 'device.state', payload: {} });
  const second = workspace.acceptEvent({ eventId: 'event-b', origin: 'phone', sequence: 2, type: 'device.state', payload: {} });
  assert.equal(first.event.revision, 1);
  assert.equal(second.event.revision, 2);
  assert.equal(workspace.eventsAfter(1).length, 1);
  assert.equal(workspace.acceptEvent({ eventId: 'event-a', origin: 'phone', sequence: 1, type: 'device.state', payload: {} }).accepted, false);
});

test('mote behavior is deterministic and safe for unknown profiles', () => {
  const behavior = deriveMoteBehavior({ profileId: 'ember_sprig', taskState: 'running', deviceHealth: 'degraded', interaction: 'tap', explorationActive: true, mood: 0.5 });
  assert.equal(behavior.profileId, 'ember_sprig');
  assert.ok(behavior.motionIntensity > 0.5);
  assert.equal(deriveMoteBehavior({ profileId: 'unknown', taskState: 'idle' }).profileId, 'mote');
});
