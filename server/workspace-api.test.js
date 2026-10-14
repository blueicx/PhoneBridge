const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { spawn } = require('node:child_process');
const { WebSocket } = require('ws');

const PORT = 19641;
const TOKEN = 'workspace-api-test-token-1234567890';
const BASE = `http://127.0.0.1:${PORT}`;

async function getAvailablePort() {
  const probe = http.createServer();
  await new Promise((resolve, reject) => {
    probe.once('error', reject);
    probe.listen(0, '127.0.0.1', resolve);
  });
  const { port } = probe.address();
  await new Promise((resolve, reject) => probe.close(error => error ? reject(error) : resolve()));
  return port;
}

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

    const unauthorizedFlush = await fetch(`${BASE}/api/runtime/flush`, { method: 'POST' });
    assert.equal(unauthorizedFlush.status, 401);
    const firstSequenceEvent = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'sequence-collision-first', origin: 'sequence-collision-test', sequence: 1,
        type: 'test.sequence', payload: {},
      } }),
    });
    assert.equal(firstSequenceEvent.body.businessAccepted, true);
    const sequenceCollision = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'sequence-collision-second', origin: 'sequence-collision-test', sequence: 1,
        type: 'test.sequence', payload: {},
      } }),
    });
    assert.equal(sequenceCollision.body.duplicateBy, 'origin_sequence');
    assert.equal(sequenceCollision.body.businessStatus, 'rejected');
    assert.equal(sequenceCollision.body.businessAccepted, false);

    const queuedEvent = await fetch(`${BASE}/api/workspace/events`, {
      method: 'POST',
      headers: { 'x-phonebridge-token': TOKEN, 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'runtime-flush-pending-event', origin: 'flush-test', sequence: 1,
        type: 'test.flush', payload: { marker: 'persist-before-ack' },
      } }),
    });
    assert.equal(queuedEvent.status, 202);
    const flushResponse = await fetch(`${BASE}/api/runtime/flush`, {
      method: 'POST', headers: { 'x-phonebridge-token': TOKEN, 'content-type': 'application/json' }, body: '{}',
    });
    assert.equal(flushResponse.status, 200);
    const flushed = await flushResponse.json();
    assert.equal(flushed.ok, true);
    assert.equal(typeof flushed.flushedAt, 'string');
    assert.equal(flushed.persistence.schemaVersion, 3);
    assert.equal(fs.existsSync(path.join(runtimeDir, 'runtime-state.json')), true);
    const persistedWorkspace = JSON.parse(fs.readFileSync(path.join(runtimeDir, 'workspace-state.json'), 'utf8'));
    assert.equal(persistedWorkspace.state.eventLog.some(event => event.eventId === 'runtime-flush-pending-event'), true);

    const health = await request('/api/device/health');
    assert.equal(health.response.status, 200);
    assert.equal(health.body.ok, true);
    assert.equal(typeof health.body.health.overall, 'string');

    const liveness = await fetch(`${BASE}/health/live`);
    assert.equal(liveness.status, 200);
    assert.deepEqual(await liveness.json(), { ok: true, status: 'alive' });
    const readiness = await fetch(`${BASE}/health/ready`);
    assert.equal(readiness.status, 200);
    assert.equal((await readiness.json()).status, 'ready');

    const page = await fetch(`${BASE}/`, { headers: { 'x-phonebridge-token': TOKEN } });
    const html = await page.text();
    assert.match(html, /If-None-Match/);
    assert.match(html, /status\s*===\s*304/);
    assert.match(html, /privacyMigration/);
    assert.match(html, /privacy\/migration\/resolve/);
    assert.match(html, /privacyCategoryIds/);
    for (const match of html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)) {
      assert.doesNotThrow(() => new vm.Script(match[1]), 'embedded Web script must parse');
    }

    const summary = await request('/api/state?view=summary');
    assert.equal(summary.response.status, 200);
    assert.equal(summary.body.view, 'summary');
    assert.ok(summary.response.headers.get('etag'));
    const diagnostics = await request('/api/diagnostics');
    assert.equal(diagnostics.response.status, 200);
    assert.ok(diagnostics.body.snapshotCache.viewBuilds.summary >= 1);
    assert.equal(diagnostics.body.snapshotCache.viewBuilds.full, 0);

    const cachedSummary = await fetch(`${BASE}/api/state?view=summary`, {
      headers: { 'x-phonebridge-token': TOKEN, 'if-none-match': summary.response.headers.get('etag') },
    });
    assert.equal(cachedSummary.status, 304);
    assert.equal(await cachedSummary.text(), '');

    const startedRoutine = await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ eventId: 'stage-routine-start', action: 'start', occurredAt: new Date().toISOString(), privacyRevision: 0 }),
    });
    assert.equal(startedRoutine.response.status, 201);
    const activitySummary = await request('/api/state?view=summary');
    assert.deepEqual(activitySummary.body.workspace.activities[0], {
      routineId: 'focus-timer', title: '专注计时', status: 'active', elapsedSeconds: 0,
      updatedAt: startedRoutine.body.entry.updatedAt,
    });
    assert.equal(Object.hasOwn(activitySummary.body.workspace.activities[0], 'reflection'), false);

    const proactive = await request('/api/proactive');
    assert.equal(proactive.response.status, 200);
    assert.equal(proactive.body.policy.maxPerHour, 1);
    assert.equal(proactive.body.policy.maxPerDay, 6);
    const focus = await request('/api/proactive', {
      method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ focusActive: true }),
    });
    assert.equal(focus.body.policy.focusActive, true);
    const explanation = await request('/api/proactive/explain?key=workspace-test');
    assert.equal(explanation.body.explanation.reason, 'focus_mode');
    await request('/api/proactive', {
      method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ focusActive: false }),
    });

    const candidateMemory = await request('/api/memories', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'api-memory-candidate', text: '可能喜欢低亮度', source: 'auto_extract', status: 'candidate' }),
    });
    assert.equal(candidateMemory.response.status, 201);
    assert.equal(candidateMemory.body.entry.status, 'candidate');
    const confirmedMemory = await request('/api/memories/api-memory-candidate/confirm', { method: 'POST' });
    assert.equal(confirmedMemory.response.status, 200);
    assert.equal(confirmedMemory.body.entry.status, 'confirmed');
    const positivePreference = await request('/api/memories', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'memory-positive-preference', text: '我喜欢安静的夜间提醒', source: 'user', explicit: true }),
    });
    assert.equal(positivePreference.response.status, 201);
    const conflictCandidate = await request('/api/memories', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'memory-negative-candidate', text: '我不喜欢安静的夜间提醒', source: 'auto_extract', status: 'candidate' }),
    });
    assert.deepEqual(conflictCandidate.body.conflicts.map(item => item.id), ['memory-positive-preference']);
    const batchConfirmed = await request('/api/memories/confirm-batch', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ids: ['memory-negative-candidate', 'api-memory-candidate', 'unknown-memory'] }),
    });
    assert.equal(batchConfirmed.response.status, 200);
    assert.deepEqual(batchConfirmed.body.confirmed.map(item => item.id), ['memory-negative-candidate']);
    assert.equal(batchConfirmed.body.revision >= 1, true);

    await request('/api/memories', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'memory-merge-target', text: '晚上提醒希望柔和', source: 'auto_extract', status: 'candidate' }),
    });
    await request('/api/memories', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'memory-merge-source', text: '夜间通知亮度偏低', source: 'conversation_extract', status: 'candidate' }),
    });
    const mergedMemories = await request('/api/memories/merge', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ targetId: 'memory-merge-target', candidateIds: ['memory-merge-source'], text: '用户希望夜间提醒保持柔和低亮度' }),
    });
    assert.equal(mergedMemories.response.status, 200);
    assert.deepEqual(mergedMemories.body.entry.provenance, ['auto_extract', 'conversation_extract']);
    assert.deepEqual(mergedMemories.body.removedIds, ['memory-merge-source']);
    const candidateListing = await request('/api/memories?status=candidate');
    assert.equal(candidateListing.body.memories.some(item => item.id === 'memory-merge-target'), true);
    assert.equal(candidateListing.body.memories.find(item => item.id === 'memory-merge-target').provenance.length, 2);
    const excludedMemory = await request('/api/memories/api-memory-candidate', {
      method: 'PATCH', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ excludedFromRecall: true }),
    });
    assert.equal(excludedMemory.response.status, 200);
    assert.equal(excludedMemory.body.entry.excludedFromRecall, true);

    const initialSummary = await request('/api/state?view=summary');
    const initialETag = initialSummary.response.headers.get('etag');

    const createdFilterTask = await request('/api/tasks', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '筛选测试任务', detail: 'test' }),
    });

    const secondSummary = await fetch(BASE + '/api/state?view=summary', {
      headers: { 'x-phonebridge-token': TOKEN, 'if-none-match': initialETag },
    });
    assert.equal(secondSummary.status, 200);
    assert.notEqual(secondSummary.headers.get('etag'), initialETag);

    const pendingTasks = await request('/api/tasks?state=pending');
    assert.ok(pendingTasks.body.tasks.some(t => t.id === createdFilterTask.body.task.id));

    await request('/api/tasks/' + createdFilterTask.body.task.id + '/actions', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ action: 'start' }),
    });
    const runningTasks = await request('/api/tasks?state=running');
    assert.ok(runningTasks.body.tasks.some(t => t.id === createdFilterTask.body.task.id));

    const filterTaskAudit = await request('/api/tasks/' + createdFilterTask.body.task.id + '/audit');
    assert.equal(filterTaskAudit.body.audit.length >= 1, true);
    assert.equal(filterTaskAudit.body.audit[0].action, 'start');
    assert.equal(filterTaskAudit.body.audit[0].actor, 'web');

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
    assert.equal(motes.body.roster.length, 20);
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
    const relationship = await request('/api/motes/relationship', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ eventId: 'api-tap-1', kind: 'tap', amount: 10 }),
    });
    assert.equal(relationship.response.status, 201);
    const relationshipDuplicate = await request('/api/motes/relationship', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ eventId: 'api-tap-1', kind: 'tap', amount: 10 }),
    });
    assert.equal(relationshipDuplicate.body.duplicate, true);
    const quests = await request('/api/motes/quests');
    assert.equal(quests.response.status, 200);
    const questClaim = await request('/api/motes/quests/daily-observer/claim', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ eventId: 'api-quest-1' }),
    });
    assert.equal(questClaim.response.status, 201);
    const relationshipAfterQuest = await request('/api/motes/relationship');
    assert.equal(relationshipAfterQuest.body.relationship.xp, 15);
    const questDuplicate = await request('/api/motes/quests/daily-observer/claim', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ eventId: 'api-quest-1' }),
    });
    assert.equal(questDuplicate.body.duplicate, true);
    const relationshipAfterDuplicate = await request('/api/motes/relationship');
    assert.equal(relationshipAfterDuplicate.body.relationship.xp, 15);
    const approvalRequest = await request('/api/autonomy/approvals', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ toolId: 'device.telemetry', args: {} }),
    });
    assert.equal(approvalRequest.response.status, 202);
    const approvalId = approvalRequest.body.approval.id;
    const approved = await request(`/api/autonomy/approvals/${approvalId}/approve`, { method: 'POST' });
    assert.equal(approved.response.status, 200);
    const invoked = await request(`/api/autonomy/approvals/${approvalId}/invoke`, { method: 'POST' });
    assert.equal(invoked.response.status, 200);
    const replay = await request(`/api/autonomy/approvals/${approvalId}/invoke`, { method: 'POST' });
    assert.equal(replay.response.status, 403);
    const sync = await request('/api/workspace/events?since=0');
    assert.equal(sync.response.status, 200);
    assert.equal(typeof sync.body.resetRequired, 'boolean');
    const opened = await openSocket();
    const socket = opened.socket;
    try {
      assert.equal(opened.firstMessage.type, 'snapshot');
      const firstChatPromise = nextSocketMessage(socket, message => message.type === 'chat' && message.requestId === 'stage-chat-original');
      socket.send(JSON.stringify({ type: 'chat', requestId: 'stage-chat-original', source: 'web-stage', text: '检查聊天历史幂等', remember: true }));
      const firstChat = await firstChatPromise;
      assert.equal(firstChat.historyRequestId, 'stage-chat-original');
      const retriedChatPromise = nextSocketMessage(socket, message => message.type === 'chat' && message.requestId === 'stage-chat-retry');
      socket.send(JSON.stringify({ type: 'chat', requestId: 'stage-chat-retry', retryOfRequestId: 'stage-chat-original', source: 'web-stage', text: '检查聊天历史幂等', remember: true }));
      const retriedChat = await retriedChatPromise;
      assert.equal(retriedChat.historyRequestId, 'stage-chat-original');
      const chatSnapshotPromise = nextSocketMessage(socket, message => message.type === 'snapshot' && Array.isArray(message.chat));
      socket.send(JSON.stringify({ type: 'snapshot', view: 'full' }));
      const chatSnapshot = await chatSnapshotPromise;
      assert.deepEqual(chatSnapshot.chat.filter(item => item.requestId === 'stage-chat-original').map(item => item.role), ['user', 'assistant']);

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

      const sequenceFirstAckPromise = nextSocketMessage(socket, message => message.type === 'workspace.ack');
      socket.send(JSON.stringify({
        type: 'workspace.event', eventId: 'ws-sequence-first', origin: 'ws-sequence-collision-test',
        sequence: 1, eventType: 'test.sequence', payload: {},
      }));
      const sequenceFirstAck = await sequenceFirstAckPromise;
      assert.equal(sequenceFirstAck.businessAccepted, true);
      const sequenceCollisionAckPromise = nextSocketMessage(socket, message => message.type === 'workspace.ack');
      socket.send(JSON.stringify({
        type: 'workspace.event', eventId: 'ws-sequence-second', origin: 'ws-sequence-collision-test',
        sequence: 1, eventType: 'test.sequence', payload: {},
      }));
      const sequenceCollisionAck = await sequenceCollisionAckPromise;
      assert.equal(sequenceCollisionAck.businessStatus, 'rejected');
      assert.equal(sequenceCollisionAck.businessAccepted, false);

      const chatCancelAckPromise = nextSocketMessage(socket, message => message.type === 'chat_cancelled');
      socket.send(JSON.stringify({ type: 'chat_cancel', requestId: 'chat_nonexistent' }));
      const chatCancelAck = await chatCancelAckPromise;
      assert.equal(chatCancelAck.requestId, 'chat_nonexistent');
      assert.equal(chatCancelAck.cancelled, false);
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
    assert.equal(message.body.task.metadata.sessionId, sessionId);
    assert.equal(message.body.task.metadata.messageId, message.body.message.id);
    const resumedMessages = await request(`/api/workspace/sessions/${sessionId}/messages`);
    assert.equal(resumedMessages.body.resume.lastUserMessage, '只保存，不调用模型');
    const resumedSession = await request(`/api/workspace/sessions/${sessionId}`);
    assert.equal(resumedSession.body.resume.lastUserMessage, '只保存，不调用模型');

    const tasks = await request('/api/tasks');
    assert.ok(tasks.body.tasks.some(task => task.id === message.body.task.id));

    const controlTask = await request('/api/tasks', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '可恢复任务', detail: '验证任务动作' }),
    });
    const startedTask = await request(`/api/tasks/${controlTask.body.task.id}/actions`, {
      method: 'POST', headers: { 'content-type': 'application/json', 'idempotency-key': 'api-start-1' },
      body: JSON.stringify({ action: 'start' }),
    });
    assert.equal(startedTask.body.task.state, 'running');
    const repeatedStart = await request(`/api/tasks/${controlTask.body.task.id}/actions`, {
      method: 'POST', headers: { 'content-type': 'application/json', 'idempotency-key': 'api-start-1' },
      body: JSON.stringify({ action: 'start' }),
    });
    assert.equal(repeatedStart.body.task.state, 'running');
    const taskAudit = await request(`/api/tasks/${controlTask.body.task.id}/audit`);
    assert.equal(taskAudit.body.audit.length, 1);
    const behavior = await request('/api/motes/behavior?taskState=running&deviceHealth=degraded');
    assert.equal(behavior.body.behavior.version, 1);
    assert.equal(behavior.body.behavior.visualPreset, 'leaf-fox');
    assert.deepEqual(behavior.body.behavior.taskAffinity, ['exploration', 'creative']);
    const story = await request('/api/motes/story');
    assert.equal(story.response.status, 200);
    assert.equal(story.body.story.length, 32);
    assert.equal(story.body.story.find(item => item.id === 'first-awakening').completed, true);
    assert.equal(story.body.story.find(item => item.id === 'exclusive-mote').branches.length, 2);
    const unknownStoryBranch = await request('/api/motes/story/not-a-story/branch', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ choiceId: 'go-further' }),
    });
    assert.equal(unknownStoryBranch.response.status, 409);
    const claimedStory = await request('/api/motes/story/first-awakening/claim', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ claimId: 'api-story-1' }),
    });
    assert.equal(claimedStory.response.status, 201);
    assert.equal(claimedStory.body.relationship.duplicate, false);
    const storyXpAfterClaim = claimedStory.body.relationship.xp;
    const replayedStory = await request('/api/motes/story/first-awakening/claim', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ claimId: 'api-story-1' }),
    });
    assert.equal(replayedStory.response.status, 200);
    assert.equal(replayedStory.body.duplicate, true);
    assert.equal(replayedStory.body.relationship.duplicate, true);
    assert.equal(replayedStory.body.relationship.xp, storyXpAfterClaim);

    const realityEvents = await request('/api/reality/events?region=cell:7:8');
    assert.equal(realityEvents.response.status, 200);
    const realityEvent = realityEvents.body.events[0];
    const realityResolved = await request(`/api/reality/events/${encodeURIComponent(realityEvent.id)}/resolve`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ region: 'cell:7:8', clueType: realityEvent.clueType, actions: ['observe'] }),
    });
    assert.equal(realityResolved.response.status, 201);
    assert.equal(realityResolved.body.growth.businessStatus, 'accepted');
    assert.equal(typeof realityResolved.body.growth.revision, 'number');
    const progress = await request('/api/reality/progress');
    assert.equal(progress.response.status, 200);
    assert.equal(progress.body.progress.growth.revision, realityResolved.body.growth.revision);
    const receipt = await request(`/api/reality/receipts/${encodeURIComponent(realityEvent.id)}`);
    assert.equal(receipt.response.status, 200);
    assert.equal(receipt.body.receipt.businessStatus, 'accepted');

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
    assert.equal(typeof workspace.body.eventRevision, 'number');

    const privacySocketOpened = await openSocket();
    const privacySocket = privacySocketOpened.socket;
    try {
      assert.equal(privacySocketOpened.firstMessage.type, 'snapshot');
      const privacyDeletedPromise = nextSocketMessage(privacySocket, event => event.type === 'privacy.deleted');
      const privacyDelete = await request('/api/privacy/delete', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ requestId: 'ws-privacy-revision-delete', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' }),
      });
      assert.equal(privacyDelete.response.status, 200);
      const privacyDeleted = await privacyDeletedPromise;
      assert.equal(privacyDeleted.categoryRevisions.conversations, privacyDelete.body.receipt.categoryRevisions.conversations);

      const ackPromise = nextSocketMessage(privacySocket, message => message.type === 'workspace.ack' && message.eventId === 'ws-after-delete-without-revision');
      privacySocket.send(JSON.stringify({
        type: 'workspace.event', eventId: 'ws-after-delete-without-revision', origin: 'phone-ws', sequence: 100,
        eventType: 'workspace.message', payload: { role: 'user', text: 'should wait for revision sync' },
      }));
      const ack = await ackPromise;
      assert.equal(ack.accepted, false);
      assert.equal(ack.businessStatus, 'rejected');
      assert.equal(ack.reason, 'privacy_revision_required:conversations');
    } finally {
      privacySocket.close();
    }

    const historyBeforeIndependentDeletes = await request('/api/reality/log?limit=50');
    assert.equal(historyBeforeIndependentDeletes.response.status, 200);
    assert.ok(historyBeforeIndependentDeletes.body.entries.some(entry => entry.eventId === realityEvent.id));
    for (const category of ['routines', 'goals']) {
      const deletion = await request('/api/privacy/delete', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          requestId: `reality-log-delete-${category}`,
          categories: [category],
          confirmation: 'DELETE SELECTED DATA',
        }),
      });
      assert.equal(deletion.response.status, 200);
      const stillPresent = await request('/api/reality/log?limit=50');
      assert.ok(stillPresent.body.entries.some(entry => entry.eventId === realityEvent.id), `${category} deletion must not clear exploration history`);
    }
    const progressDeletion = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'reality-log-delete-progress', categories: ['progress'], confirmation: 'DELETE SELECTED DATA' }),
    });
    assert.equal(progressDeletion.response.status, 200);
    const historyAfterProgressDelete = await request('/api/reality/log?limit=50');
    assert.deepEqual(historyAfterProgressDelete.body.entries, []);
    const deletedReceipt = await request(`/api/reality/receipts/${encodeURIComponent(realityEvent.id)}`);
    assert.equal(deletedReceipt.response.status, 404);
  } finally {
    child.kill();
    if (child.exitCode === null) {
      await new Promise(resolve => child.once('exit', resolve));
    }
    fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
});

test('configured provider streams live chat deltas and remember=false excludes memory and summary context', { timeout: 25_000 }, async () => {
  const chatPort = await getAvailablePort();
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-stream-test-'));
  const providerBodies = [];
  const upstream = http.createServer((req, res) => {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      providerBodies.push(JSON.parse(body));
      res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8' });
      res.write('data: {"choices":[{"delta":{"content":"真实"}}]}\n\n');
      setTimeout(() => {
        if (res.destroyed) return;
        res.write('data: {"choices":[{"delta":{"content":"流式"}}]}\n\n');
        res.write('data: [DONE]\n\n');
        res.end();
      }, 130);
    });
  });
  await new Promise((resolve, reject) => {
    upstream.once('error', reject);
    upstream.listen(0, '127.0.0.1', resolve);
  });
  const providerPort = upstream.address().port;
  const child = spawn(process.execPath, ['index.js'], {
    cwd: __dirname,
    env: {
      ...process.env,
      PHONEBRIDGE_PORT: String(chatPort),
      PHONEBRIDGE_TOKEN: TOKEN,
      PHONEBRIDGE_RUNTIME_DIR: runtimeDir,
      PHONEBRIDGE_LOCK_FILE: path.join(runtimeDir, 'node.lock')
    },
    stdio: 'ignore',
    windowsHide: true,
  });
  const chatRequest = async (path, options = {}) => {
    const response = await fetch(`http://127.0.0.1:${chatPort}${path}`, {
      ...options,
      headers: { 'x-phonebridge-token': TOKEN, ...(options.headers || {}) }
    });
    return { response, body: await response.json() };
  };
  try {
    const started = Date.now();
    let serverReady = false;
    while (Date.now() - started < 12_000) {
      if (child.exitCode !== null) throw new Error(`stream test server exited with ${child.exitCode}`);
      try {
        if ((await chatRequest('/api/ai/settings')).response.ok) {
          serverReady = true;
          break;
        }
      } catch (_) {}
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    assert.equal(serverReady, true, 'stream test server should listen on its dynamically allocated port');
    const configured = await chatRequest('/api/ai/settings', {
      method: 'PATCH', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        activeProviderId: 'openai',
        providers: { openai: { endpoint: `http://127.0.0.1:${providerPort}/v1`, apiKey: 'local-test-key-12345', model: 'sse-test' } }
      })
    });
    assert.equal(configured.response.status, 200);
    await chatRequest('/api/memories', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'stream-memory', text: '我喜欢安静的夜间提醒', source: 'user-confirmed', explicit: true })
    });

    const socket = new WebSocket(`ws://127.0.0.1:${chatPort}?token=${encodeURIComponent(TOKEN)}`);
    await new Promise((resolve, reject) => {
      socket.once('open', resolve);
      socket.once('error', reject);
    });
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('stream socket snapshot timed out')), 5000);
      socket.once('message', data => {
        clearTimeout(timer);
        try {
          assert.equal(JSON.parse(data.toString()).type, 'snapshot');
          resolve();
        } catch (error) { reject(error); }
      });
    });

    const runChat = async (requestId, remember) => new Promise((resolve, reject) => {
      const events = [];
      const timer = setTimeout(() => {
        socket.off('message', onMessage);
        reject(new Error(`stream chat timed out: ${requestId}`));
      }, 8000);
      const onMessage = data => {
        const message = JSON.parse(data.toString());
        if (message.requestId !== requestId) return;
        events.push(message);
        if (message.type !== 'chat') return;
        clearTimeout(timer);
        socket.off('message', onMessage);
        resolve(events);
      };
      socket.on('message', onMessage);
      socket.send(JSON.stringify({
        type: 'chat', requestId, text: '我喜欢安静的夜间提醒', remember, source: 'stream-integration-test'
      }));
    });

    const privateTurn = await runChat('stream-private-turn', false);
    const privateDeltas = privateTurn.filter(item => item.type === 'chat_delta');
    assert.equal(privateDeltas.length >= 2, true);
    assert.equal(privateDeltas.filter(item => !item.terminal).map(item => item.text).join(''), '真实流式');
    assert.equal(privateDeltas.at(-1).terminal, true);
    assert.equal(privateDeltas.at(-1).text, '真实流式');
    assert.equal(providerBodies[0].messages.some(item => item.content.includes('安静的夜间提醒') && item.role === 'system'), false);
    assert.equal(providerBodies[0].messages.some(item => item.content === '我喜欢安静的夜间提醒' && item.role === 'user'), true);
    assert.equal(providerBodies[0].messages.some(item => item.content.includes('早期会话摘要')), false);

    await runChat('stream-memory-turn', true);
    assert.equal(providerBodies[1].messages.some(item => item.role === 'system' && item.content.includes('用户确认的长期记忆')), true);
    const usedMemory = await chatRequest('/api/memories?query=安静');
    assert.equal(usedMemory.body.memories.find(item => item.id === 'stream-memory').lastUsedReason, 'conversation_context');
    socket.close();
  } finally {
    child.kill();
    if (child.exitCode === null) await new Promise(resolve => child.once('exit', resolve));
    await new Promise(resolve => upstream.close(resolve));
    fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
});
