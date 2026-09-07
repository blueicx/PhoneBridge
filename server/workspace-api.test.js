const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { WebSocket } = require('ws');

const PORT = 19641;
const TOKEN = 'workspace-api-test-token-1234567890';
const BASE = `http://127.0.0.1:${PORT}`;

async function request(path, options = {}) {
  const headers = { 'x-phonebridge-token': TOKEN, ...(options.headers || {}) };
  const response = await fetch(BASE + path, { ...options, headers });
  return { response, body: await response.json() };
}

async function waitForServer(child) {
  const started = Date.now();
  while (Date.now() - started < 12_000) {
    if (child.exitCode !== null) throw new Error(`server exited with ${child.exitCode}`);
    try {
      const result = await request('/api/tools');
      if (result.response.ok) return;
    } catch (_) {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('server did not start');
}

function openSocket() {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${BASE.replace('http', 'ws')}?token=${encodeURIComponent(TOKEN)}`);
    socket.once('open', () => socket.once('message', data => resolve({ socket, firstMessage: JSON.parse(data.toString()) })));
    socket.once('error', reject);
  });
}

function nextSocketMessage(socket, predicate = () => true) {
  return new Promise((resolve, reject) => {
    const seen = [];
    const onMessage = data => {
      const message = JSON.parse(data.toString());
      seen.push(message.type);
      if (!predicate(message)) return;
      cleanup();
      resolve(message);
    };
    const onError = error => { cleanup(); reject(error); };
    const timer = setTimeout(() => { cleanup(); reject(new Error(`socket message timeout; seen=${seen.join(',')}`)); }, 5_000);
    const cleanup = () => { clearTimeout(timer); socket.off('message', onMessage); socket.off('error', onError); };
    socket.on('message', onMessage);
    socket.on('error', onError);
  });
}

test('workspace APIs preserve auth and close the session-to-task loop', { timeout: 20_000 }, async () => {
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-workspace-test-'));
  const child = spawn(process.execPath, ['index.js'], {
    cwd: __dirname,
    env: { ...process.env, PHONEBRIDGE_PORT: String(PORT), PHONEBRIDGE_TOKEN: TOKEN, PHONEBRIDGE_RUNTIME_DIR: runtimeDir, PHONEBRIDGE_LOCK_FILE: path.join(runtimeDir, 'node.lock') },
    stdio: 'ignore',
    windowsHide: true,
  });
  try {
    await waitForServer(child);
    const denied = await fetch(`${BASE}/api/tools`);
    assert.equal(denied.status, 401);

    const health = await request('/api/device/health');
    assert.equal(health.response.status, 200);
    assert.equal(health.body.ok, true);
    assert.equal(typeof health.body.health.overall, 'string');

    const created = await request('/api/workspace/sessions', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: 'API 测试', providerId: 'codex', model: 'test-model' }),
    });
    assert.equal(created.response.status, 201);
    const sessionId = created.body.session.id;
    assert.equal(created.body.session.model, 'test-model');

    const authorized = await request(`/api/workspace/sessions/${sessionId}/authorize`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ durationMs: 60_000 }),
    });
    assert.equal(authorized.response.status, 200);

    const sessionPolicy = await request(`/api/workspace/sessions/${sessionId}/policy`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ level: 'observe', allowedTools: ['device.telemetry'], continuousMic: false }),
    });
    assert.equal(sessionPolicy.response.status, 200);
    assert.equal(sessionPolicy.body.policy.level, 'observe');

    const readPolicy = await request(`/api/workspace/sessions/${sessionId}/policy`);
    assert.equal(readPolicy.response.status, 200);
    assert.equal(readPolicy.body.policy.allowedTools[0], 'device.telemetry');

    const tools = await request('/api/tools');
    assert.ok(tools.body.tools.some(tool => tool.id === 'device.telemetry'));
    const autonomy = await request('/api/autonomy');
    assert.equal(autonomy.response.status, 200);
    assert.equal(autonomy.body.policy.level, 'whitelist');
    assert.ok(autonomy.body.policy.allowedTools.includes('device.telemetry'));
    assert.ok(autonomy.body.policy.allowedTools.includes('session.memory'));
    const unsafeAutonomy = await request('/api/autonomy', {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ allowedTools: ['shell.exec'] }),
    });
    assert.equal(unsafeAutonomy.response.status, 400);

    const motes = await request('/api/motes');
    assert.equal(motes.response.status, 200);
    assert.equal(motes.body.roster.length, 10);
    const activeMote = await request('/api/motes/active', {
      method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ id: 'sprite' }),
    });
    assert.equal(activeMote.body.profile.id, 'sprite');
    const target = await request('/api/motes/exploration', {
      method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ targetId: 'ember_sprig' }),
    });
    assert.equal(target.body.state.exploration.targetId, 'ember_sprig');
    for (const clueType of ['location', 'object', 'light']) {
      const clue = await request('/api/motes/exploration/clues', {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ eventId: `api-${clueType}`, clueType }),
      });
      assert.equal(clue.response.status, clueType === 'light' ? 201 : 201);
    }
    const duplicateClue = await request('/api/motes/exploration/clues', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ eventId: 'api-light', clueType: 'light' }),
    });
    assert.equal(duplicateClue.body.duplicate, true);
    const opened = await openSocket();
    const socket = opened.socket;
    try {
      assert.equal(opened.firstMessage.type, 'snapshot');
      const invocationRunPromise = nextSocketMessage(socket, message => message.type === 'action.run');
      const invocationResultPromise = nextSocketMessage(socket, message => message.type === 'action.result');
      const invocation = await request('/api/tools/invoke', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ sessionId, toolId: 'device.telemetry', args: {} }),
      });
      assert.equal(invocation.response.status, 200);
      assert.equal(invocation.body.ok, true);
      const actionRunEvent = await invocationRunPromise;
      const actionResultEvent = await invocationResultPromise;
      assert.equal(actionRunEvent.actionRun.toolId, 'device.telemetry');
      assert.equal(actionResultEvent.actionRun.state, 'succeeded');

      const deniedInvocation = await request('/api/tools/invoke', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ sessionId, toolId: 'device.listen', args: { enabled: true, token: 'should-not-leak' } }),
      });
      assert.equal(deniedInvocation.response.status, 403);
      assert.match(deniedInvocation.body.error, /policy|observe|continuous/i);

      const event = { type: 'workspace.event', eventId: 'api-event-1', origin: 'api-test', sequence: 1, eventType: 'workspace.message', payload: { sessionId, messageId: 'api-message-1', role: 'user', text: '来自 WS' } };
      const ackPromise = nextSocketMessage(socket, message => message.type === 'workspace.ack');
      socket.send(JSON.stringify(event));
      const ack = await ackPromise;
      assert.equal(ack.type, 'workspace.ack');
      assert.equal(ack.accepted, true);
      const duplicateAckPromise = nextSocketMessage(socket, message => message.type === 'workspace.ack');
      socket.send(JSON.stringify(event));
      const duplicateAck = await duplicateAckPromise;
      assert.equal(duplicateAck.accepted, false);
    } finally {
      socket.close();
    }

    const actionRuns = await request('/api/action-runs');
    assert.equal(actionRuns.response.status, 200);
    assert.ok(actionRuns.body.actionRuns.length >= 2);
    const blockedRun = actionRuns.body.actionRuns.find(run => run.toolId === 'device.listen');
    assert.equal(blockedRun.state, 'blocked');
    assert.doesNotMatch(blockedRun.argsSummary, /should-not-leak/);

    const patchedRun = await request(`/api/action-runs/${blockedRun.id}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ state: 'cancelled', error: 'user_cancelled' }),
    });
    assert.equal(patchedRun.response.status, 200);
    assert.equal(patchedRun.body.actionRun.state, 'cancelled');

    const message = await request(`/api/workspace/sessions/${sessionId}/messages`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text: '只保存，不调用模型', runModel: false }),
    });
    assert.equal(message.response.status, 202);
    assert.equal(message.body.task.source, 'conversation');

    const tasks = await request('/api/tasks');
    assert.ok(tasks.body.tasks.some(task => task.id === message.body.task.id));

    const failedTask = await request(`/api/tasks/${message.body.task.id}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ state: 'failed', error: 'api failure' }),
    });
    assert.equal(failedTask.response.status, 200);

    const attention = await request('/api/attention');
    assert.equal(attention.response.status, 200);
    assert.ok(attention.body.attention.some(item => item.relatedTaskId === message.body.task.id));
    const taskAttention = attention.body.attention.find(item => item.relatedTaskId === message.body.task.id);

    const attentionPatch = await request(`/api/attention/${taskAttention.id}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ read: true, snoozedUntil: '2099-09-01T00:00:00.000Z' }),
    });
    assert.equal(attentionPatch.response.status, 200);
    assert.equal(attentionPatch.body.attention.status, 'snoozed');

    const automation = await request('/api/automations', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ name: 'API 自动巡检', trigger: { type: 'manual' }, actions: [{ toolId: 'device.telemetry', args: {} }] }),
    });
    assert.equal(automation.response.status, 201);
    const automationId = automation.body.automation.id;

    const automationPolicy = await request(`/api/automations/${automationId}/policy`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ level: 'observe', allowedTools: ['device.telemetry'] }),
    });
    assert.equal(automationPolicy.response.status, 200);

    const automationPolicyRead = await request(`/api/automations/${automationId}/policy`);
    assert.equal(automationPolicyRead.response.status, 200);
    assert.equal(automationPolicyRead.body.policy.level, 'observe');

    const workspace = await request('/api/workspace');
    assert.equal(workspace.response.status, 200);
    assert.ok(Array.isArray(workspace.body.attention));
    assert.ok(Array.isArray(workspace.body.policies));
    assert.ok(Array.isArray(workspace.body.actionRuns));
  } finally {
    child.kill();
    fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
});
