const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { decryptArchive } = require('./privacy-center');

const PORT = 19642;
const TOKEN = 'enhancement-api-test-token-1234567890';
const BASE = `http://127.0.0.1:${PORT}`;

async function request(p, options = {}) {
  const headers = { 'x-phonebridge-token': TOKEN, ...(options.headers || {}) };
  const response = await fetch(BASE + p, { ...options, headers });
  const text = await response.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch (_) {
    body = text;
  }
  return { response, body, headers: response.headers };
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

function beginPartialRoutineRequest(pathname, payload) {
  const body = Buffer.from(JSON.stringify(payload));
  let resolveResponse;
  const response = new Promise(resolve => { resolveResponse = resolve; });
  const req = http.request(`${BASE}${pathname}`, {
    method: 'POST',
    headers: {
      'x-phonebridge-token': TOKEN,
      'content-type': 'application/json',
      'content-length': body.length,
    },
  }, res => {
    let text = '';
    res.setEncoding('utf8');
    res.on('data', chunk => { text += chunk; });
    res.on('end', () => {
      let parsed;
      try { parsed = JSON.parse(text); } catch (_) { parsed = text; }
      resolveResponse({ status: res.statusCode, body: parsed });
    });
  });
  req.on('error', error => resolveResponse({ transportError: error }));
  req.flushHeaders();
  const splitAt = Math.max(1, Math.floor(body.length / 2));
  const bodyFlushed = new Promise((resolve, reject) => {
    req.write(body.subarray(0, splitAt), error => error ? reject(error) : resolve());
  });
  let finished = false;
  return {
    response,
    bodyFlushed,
    finish() {
      if (finished) return;
      finished = true;
      req.end(body.subarray(splitAt));
    },
  };
}

async function waitForPendingPrivacyDelete(runtimeDir, requestId) {
  const deadline = Date.now() + 5000;
  const file = path.join(runtimeDir, 'privacy-audit.json');
  while (Date.now() < deadline) {
    try {
      const envelope = JSON.parse(fs.readFileSync(file, 'utf8'));
      const receipt = envelope.state?.receipts?.find(item => item.requestId === requestId);
      if (receipt?.status === 'pending') return;
      if (receipt?.status === 'completed') throw new Error('privacy deletion completed before the held routine write finished');
    } catch (error) {
      if (error.message.includes('completed before')) throw error;
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error('privacy deletion did not enter its pending state');
}

test('enhancement endpoints: timeline, diagnostics, and AI provider APIs', { timeout: 25_000 }, async () => {
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-enhancement-test-'));
  const child = spawn(process.execPath, ['index.js'], {
    cwd: path.join(__dirname),
    env: {
      ...process.env,
      PHONEBRIDGE_PORT: String(PORT),
      PHONEBRIDGE_BIND: '127.0.0.1',
      PHONEBRIDGE_TOKEN: TOKEN,
      PHONEBRIDGE_RUNTIME_DIR: runtimeDir,
      PHONEBRIDGE_LOCK_FILE: path.join(runtimeDir, 'test.lock'),
      PHONEBRIDGE_ENABLE_SIMULATOR: '1',
      PHONEBRIDGE_SIMULATOR_SEED: 'api-test',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  try {
    await waitForServer(child);

    // 1. Test GET /api/workspace/timeline
    const timelineRes = await request('/api/workspace/timeline?cursor=0&includeSnapshot=true');
    assert.equal(timelineRes.response.status, 200);
    assert.equal(timelineRes.body.ok, true);
    assert.equal(timelineRes.body.mode, 'snapshot');
    assert.ok(timelineRes.body.snapshot, 'must include snapshot when cursor is 0');
    assert.ok(Array.isArray(timelineRes.body.events), 'must include events array');

    // ETag and 304 test for timeline
    const etag = timelineRes.headers.get('etag');
    assert.ok(etag, 'timeline should return ETag');
    const notModifiedRes = await request('/api/workspace/timeline?cursor=0', {
      headers: { 'if-none-match': etag }
    });
    assert.equal(notModifiedRes.response.status, 304);

    // 2. Test GET /api/diagnostics
    const diagRes = await request('/api/diagnostics');
    assert.equal(diagRes.response.status, 200);
    assert.equal(diagRes.body.ok, true);
    assert.equal(typeof diagRes.body.startupDurationMs, 'number');
    assert.equal(typeof diagRes.body.syncLatencyMs, 'number');
    assert.equal(typeof diagRes.body.eventBacklog, 'number');
    assert.equal(diagRes.body.performance.fpsTarget, 30);
    assert.equal(diagRes.body.performance.throttlingStrategy, 'background_reduced');

    const exportRes = await request('/api/diagnostics/export');
    assert.equal(exportRes.response.status, 200);
    assert.equal(exportRes.body.formatVersion, 1);
    assert.ok(exportRes.body.readiness);
    assert.ok(exportRes.body.companionSummary);
    assert.equal(exportRes.body.privacy.preciseLocation, false);
    assert.equal(exportRes.body.privacy.originalFrames, false);
    assert.equal(JSON.stringify(exportRes.body).includes(TOKEN), false);

    // 2b. Test the compact cross-client companion summary and conditional cache
    const companionRes = await request('/api/companion/summary');
    assert.equal(companionRes.response.status, 200);
    assert.equal(companionRes.body.ok, true);
    assert.equal(companionRes.body.summary.version, 1);
    assert.equal(typeof companionRes.body.summary.tasks.total, 'number');
    assert.equal(Object.prototype.hasOwnProperty.call(companionRes.body.summary.ai, 'apiKey'), false);
    const companionEtag = companionRes.headers.get('etag');
    assert.ok(companionEtag, 'companion summary should return ETag');
    const companion304 = await request('/api/companion/summary', { headers: { 'if-none-match': companionEtag } });
    assert.equal(companion304.response.status, 304);

    // 3. Test GET /api/ai/providers
    const providersRes = await request('/api/ai/providers');
    assert.equal(providersRes.response.status, 200);
    assert.equal(providersRes.body.ok, true);
    assert.ok(Array.isArray(providersRes.body.providers));
    const localProvider = providersRes.body.providers.find(p => p.id === 'local');
    assert.ok(localProvider, 'local fallback provider must be present');

    // 4. Test GET & PATCH /api/ai/settings
    const settingsRes = await request('/api/ai/settings');
    assert.equal(settingsRes.response.status, 200);
    assert.equal(settingsRes.body.ok, true);
    assert.equal(settingsRes.body.settings.fallbackToLocal, true);

    const patchSettingsRes = await request('/api/ai/settings', {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        timeoutMs: 9000,
        providers: {
          openai: { apiKey: ['sk', '-testkey1234567890'].join('') }
        }
      })
    });
    assert.equal(patchSettingsRes.response.status, 200);
    assert.equal(patchSettingsRes.body.settings.timeoutMs, 9000);
    const patchedOpenAi = patchSettingsRes.body.settings.providers.find(p => p.id === 'openai');
    assert.ok(patchedOpenAi.apiKey.includes('***'), 'apiKey must be redacted in response');

    // 5. Test POST /api/ai/providers/:id/probe
    const probeRes = await request('/api/ai/providers/local/probe', {
      method: 'POST'
    });
    assert.equal(probeRes.response.status, 200);
    assert.equal(probeRes.body.ok, true);
    assert.equal(probeRes.body.providerId, 'local');
    assert.ok(typeof probeRes.body.latencyMs === 'number');

    // Probe invalid provider returns 400
    const probeInvalidRes = await request('/api/ai/providers/invalid_provider/probe', {
      method: 'POST'
    });
    assert.equal(probeInvalidRes.response.status, 400);
    assert.equal(probeInvalidRes.body.ok, false);

    const capabilitiesRes = await request('/api/ai/capabilities');
    assert.equal(capabilitiesRes.response.status, 200);
    assert.ok(capabilitiesRes.body.providers.some(provider => provider.id === 'local' && provider.capabilities.includes('offline')));

    const memoryRes = await request('/api/memories', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'api-memory', text: '喜欢安静提醒', source: 'test' })
    });
    assert.equal(memoryRes.response.status, 201);
    const memories = await request('/api/memories?query=安静');
    assert.equal(memories.body.memories[0].id, 'api-memory');

    const reality = await request('/api/reality/events?region=cell:1:2');
    assert.equal(reality.response.status, 200);
    assert.equal(reality.body.events.length, 8);
    const event = reality.body.events[0];
    const resolved = await request(`/api/reality/events/${encodeURIComponent(event.id)}/resolve`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ region: 'cell:1:2', clueType: event.clueType, actions: ['observe'] })
    });
    assert.equal(resolved.response.status, 201, JSON.stringify(resolved.body));
    const duplicate = await request(`/api/reality/events/${encodeURIComponent(event.id)}/resolve`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ region: 'cell:1:2', clueType: event.clueType })
    });
    assert.equal(duplicate.body.duplicate, true);

    const realityLog = await request('/api/reality/log?limit=10');
    assert.equal(realityLog.response.status, 200);
    assert.equal(realityLog.body.entries.length, 1);
    assert.equal(realityLog.body.entries[0].eventId, event.id);
    assert.equal(realityLog.body.entries[0].status, 'confirmed');
    assert.equal(realityLog.body.entries[0].coarseRegion, 'cell:1:2');
    assert.equal(realityLog.body.entries[0].clueType, event.clueType);
    assert.deepEqual(Object.keys(realityLog.body.entries[0]).sort(), [
      'eventId', 'status', 'occurredAt', 'coarseRegion', 'clueType', 'moteId', 'observation', 'reward',
    ].sort());
    const badRealityCursor = await request('/api/reality/log?cursor=not-a-valid-cursor');
    assert.equal(badRealityCursor.response.status, 400);
    assert.equal(badRealityCursor.body.ok, false);

    const simulator = await request('/api/dev/simulator');
    assert.equal(simulator.response.status, 200);
    assert.equal(simulator.body.state.seed, 'api-test');

    const privacyBefore = await request('/api/privacy/overview');
    assert.equal(privacyBefore.response.status, 200);
    assert.equal(privacyBefore.body.categories.memories.count, 1);
    assert.equal(privacyBefore.body.categories.conversations.count, 0);
    assert.equal(privacyBefore.body.categories.routines.count, 0);
    assert.ok(privacyBefore.body.excluded.includes('Provider 密钥'));

    const routineCatalog = await request('/api/routines');
    assert.equal(routineCatalog.response.status, 200);
    assert.deepEqual(routineCatalog.body.catalog.map(item => item.id), ['focus-timer', 'walk-observation', 'bedtime-review']);
    assert.ok(routineCatalog.body.catalog.every(item => item.permissionRequired === false));
    const routineAt = Date.now();
    const routineEvent = (eventId, action, offsetSeconds, extra = {}) => ({
      eventId, action, occurredAt: new Date(routineAt + offsetSeconds * 1000).toISOString(), ...extra
    });
    const focusStart = routineEvent('api-routine-focus-start', 'start', 0);
    const focusCreated = await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(focusStart)
    });
    assert.equal(focusCreated.response.status, 201, JSON.stringify(focusCreated.body));
    assert.equal(focusCreated.body.entry.status, 'active');
    const focusReplay = await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(focusStart)
    });
    assert.equal(focusReplay.response.status, 200);
    assert.equal(focusReplay.body.duplicate, true);
    assert.equal(focusReplay.body.entry.id, focusCreated.body.entry.id);
    const focusPaused = await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-focus-pause', 'pause', 10, { elapsedSeconds: 60 }))
    });
    assert.equal(focusPaused.body.entry.status, 'paused');
    await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-focus-resume', 'resume', 20, { elapsedSeconds: 60 }))
    });
    const focusFinished = await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-focus-finish', 'finish', 30, { elapsedSeconds: 120 }))
    });
    assert.equal(focusFinished.body.entry.status, 'finished');

    await request('/api/routines/bedtime-review/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-bed-start', 'start', 40))
    });
    const bedtimeReflection = '今天由我决定是否记录的睡前回顾。';
    const bedtimeFinished = await request('/api/routines/bedtime-review/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-bed-finish', 'finish', 50, { reflection: bedtimeReflection }))
    });
    assert.equal(bedtimeFinished.body.entry.reflection, bedtimeReflection);
    assert.equal((await request(`/api/memories?query=${encodeURIComponent(bedtimeReflection)}`)).body.memories.length, 0);
    const routineHistory = await request('/api/routines?cursor=0&limit=1');
    assert.equal(routineHistory.body.history.length, 1);
    assert.equal(routineHistory.body.nextCursor, 1);
    assert.equal(routineHistory.body.current.length, 0);

    const invalidRoutineTransition = await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-illegal-pause', 'pause', 60))
    });
    assert.equal(invalidRoutineTransition.response.status, 409);
    const invalidRoutine = await request('/api/routines/not-a-routine/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-unknown', 'start', 60))
    });
    assert.equal(invalidRoutine.response.status, 404);

    const routinesPrivacy = await request('/api/privacy/overview');
    assert.equal(routinesPrivacy.body.categories.routines.count, 2);
    const routinesExport = await request('/api/privacy/export', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ categories: ['routines'], passphrase: 'phonebridge test passphrase' })
    });
    assert.equal(routinesExport.response.status, 200);
    assert.equal(JSON.stringify(routinesExport.body).includes(bedtimeReflection), false);
    const routinesArchive = decryptArchive(routinesExport.body.archive, 'phonebridge test passphrase');
    assert.ok(routinesArchive.data.routines.entries.some(entry => entry.reflection === bedtimeReflection));
    const heldRoutineEvent = {
      ...routineEvent('api-routine-held-during-delete', 'start', 55),
      privacyRevision: routinesPrivacy.body.categories.routines.revision,
    };
    const heldRoutineWrite = beginPartialRoutineRequest('/api/routines/focus-timer/events', heldRoutineEvent);
    let deletionPromise;
    try {
      await heldRoutineWrite.bodyFlushed;
      await new Promise(resolve => setTimeout(resolve, 75));
      deletionPromise = request('/api/privacy/delete', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ requestId: 'delete-routine-001', categories: ['routines'], confirmation: 'DELETE SELECTED DATA' })
      });
      await waitForPendingPrivacyDelete(runtimeDir, 'delete-routine-001');
      const blockedRoutineWrite = await request('/api/routines/walk-observation/events', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ ...routineEvent('api-routine-blocked-during-delete', 'start', 56), privacyRevision: 0 })
      });
      assert.equal(blockedRoutineWrite.response.status, 409);
      assert.equal(blockedRoutineWrite.body.retryable, true);
    } finally {
      heldRoutineWrite.finish();
    }
    const [heldRoutineResponse, deletedRoutinesResult] = await Promise.all([heldRoutineWrite.response, deletionPromise]);
    assert.equal(heldRoutineResponse.transportError, undefined, String(heldRoutineResponse.transportError || ''));
    assert.equal(heldRoutineResponse.status, 409, JSON.stringify(heldRoutineResponse.body));
    assert.equal(heldRoutineResponse.body.code, 'privacy_revision_stale:routines');
    assert.equal(deletedRoutinesResult.response.status, 200);
    assert.equal(deletedRoutinesResult.body.receipt.categoryRevisions.routines, 1);
    assert.equal((await request('/api/routines')).body.history.length, 0);
    assert.equal((await request('/api/privacy/overview')).body.categories.routines.count, 0);

    const staleRoutineReplay = await request('/api/routines/focus-timer/events', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(heldRoutineEvent)
    });
    assert.equal(staleRoutineReplay.response.status, 409);
    assert.equal(staleRoutineReplay.body.code, 'privacy_revision_stale:routines');
    const missingRoutineRevision = await request('/api/routines/walk-observation/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(routineEvent('api-routine-missing-revision', 'start', 57))
    });
    assert.equal(missingRoutineRevision.response.status, 409);
    assert.equal(missingRoutineRevision.body.code, 'privacy_revision_required:routines');
    const currentRoutineEvent = {
      ...routineEvent('api-routine-current-revision', 'start', 58),
      privacyRevision: 1,
    };
    const currentRoutineWrite = await request('/api/routines/walk-observation/events', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(currentRoutineEvent)
    });
    assert.equal(currentRoutineWrite.response.status, 201);
    assert.equal((await request('/api/routines')).body.privacyRevision, 1);

    const privacySession = await request('/api/workspace/sessions', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'privacy-session', title: 'private session' })
    });
    assert.equal(privacySession.response.status, 201);
    await request('/api/workspace/sessions/privacy-session/messages', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: 'privacy-message', role: 'user', text: 'private conversation body', runModel: false })
    });
    await request('/api/handoff', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ state: { revision: 100, notes: 'private handoff note' } })
    });
    await request('/api/motes/relationship', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ eventId: 'privacy-test-progress', amount: 7 })
    });
    const populatedOverview = await request('/api/privacy/overview');
    assert.equal(populatedOverview.body.categories.conversations.count, 2);
    assert.equal(populatedOverview.body.categories.tasks.count, 1);
    assert.ok(populatedOverview.body.categories.progress.count > 0);

    const encryptedExport = await request('/api/privacy/export', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ categories: ['memories'], passphrase: 'phonebridge test passphrase' })
    });
    assert.equal(encryptedExport.response.status, 200);
    assert.equal(JSON.stringify(encryptedExport.body).includes('喜欢安静提醒'), false);
    assert.equal(decryptArchive(encryptedExport.body.archive, 'phonebridge test passphrase').data.memories[0].text, '喜欢安静提醒');

    const rejectedDelete = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'memory-delete-001', categories: ['memories'], confirmation: 'no' })
    });
    assert.equal(rejectedDelete.response.status, 400);
    const deleted = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'memory-delete-001', categories: ['memories'], confirmation: 'DELETE SELECTED DATA' })
    });
    assert.equal(deleted.response.status, 200);
    assert.equal(deleted.body.receipt.status, 'completed');
    assert.ok((await request('/api/privacy/overview')).body.recentDeletions.some(item => item.requestId === 'memory-delete-001'));
    const deletedReplay = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'memory-delete-001', categories: ['memories'], confirmation: 'DELETE SELECTED DATA' })
    });
    assert.equal(deletedReplay.body.receipt.duplicate, true);
    assert.equal((await request('/api/memories')).body.count, 0);

    const deletedConversations = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'conversation-delete-001', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' })
    });
    assert.equal(deletedConversations.response.status, 200);
    assert.equal(deletedConversations.body.receipt.deletedCounts.conversations, 4);
    assert.equal(deletedConversations.body.receipt.categoryRevisions.conversations, 1);
    assert.equal((await request('/api/workspace/sessions')).body.sessions.length, 0);
    assert.equal((await request('/api/workspace')).body.tasks.length, 0);
    assert.equal((await request('/api/handoff')).body.state.notes, '');
    const timelineAfterConversationDelete = await request('/api/workspace/timeline?cursor=0');
    assert.equal(timelineAfterConversationDelete.body.snapshot.messages.length, 0);
    assert.equal(timelineAfterConversationDelete.body.snapshot.tasks.length, 0);
    const workspaceEventCursor = (await request('/api/workspace/events?since=0')).body.revision;
    const staleConversationReplay = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'offline-message-before-privacy-delete', origin: 'phone-offline', sequence: 1,
        type: 'workspace.message', createdAt: '2020-01-01T00:00:00.000Z', payload: { text: 'must not resurrect' }
      } })
    });
    assert.equal(staleConversationReplay.body.businessStatus, 'rejected');
    assert.equal(staleConversationReplay.body.reason, 'privacy_revision_required:conversations');
    assert.equal(staleConversationReplay.body.businessReason, staleConversationReplay.body.reason);
    const eventLogAfterRejectedReplay = await request(`/api/workspace/events?since=${workspaceEventCursor}`);
    assert.equal(eventLogAfterRejectedReplay.body.events.some(event => event.eventId === 'offline-message-before-privacy-delete'), false);
    assert.equal(JSON.stringify((await request('/api/workspace/timeline?cursor=0')).body).includes('must not resurrect'), false);

    const explicitlyStaleConversationReplay = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'offline-message-with-old-revision', origin: 'phone-offline', sequence: 3,
        type: 'workspace.message', privacyRevisions: { conversations: 0 }, payload: { text: 'old category revision' }
      } })
    });
    assert.equal(explicitlyStaleConversationReplay.body.reason, 'privacy_revision_stale:conversations');

    const acceptedCurrentRevision = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'new-message-with-current-revision', origin: 'phone-online', sequence: 1,
        type: 'workspace.message', privacyRevisions: { conversations: 1 },
        createdAt: new Date(Date.now() + 1000).toISOString(), payload: { text: 'new conversation after deletion' }
      } })
    });
    assert.equal(acceptedCurrentRevision.body.businessAccepted, true);
    assert.equal(acceptedCurrentRevision.body.reason, 'event_applied');

    const deletionTimeline = await request(`/api/workspace/timeline?cursor=${(await request('/api/workspace/timeline?cursor=0')).body.headRevision - 1}&limit=20`);
    const privacyDeletionEvent = deletionTimeline.body.events.find(event => event.eventId === 'privacy-conversation-delete-001');
    assert.deepEqual(privacyDeletionEvent.payload.categoryRevisions, { conversations: 1 });

    const linkedTaskAfterConversationDelete = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'new-linked_job-with-both-revisions', origin: 'phone-online', sequence: 2,
        type: 'workspace.task.progress', privacyRevisions: { conversations: 1, tasks: 0 },
        createdAt: new Date(Date.now() + 1000).toISOString(),
        payload: { source: 'conversation', metadata: { sessionId: 'new-session' }, progress: 20 }
      } })
    });
    assert.equal(linkedTaskAfterConversationDelete.body.businessAccepted, true);
    const deletedTasks = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'tasks-delete-after-conversation', categories: ['tasks'], confirmation: 'DELETE SELECTED DATA' })
    });
    assert.equal(deletedTasks.body.receipt.categoryRevisions.tasks, 1);
    const staleLinkedTask = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'linked_job-replayed-after-task-delete', origin: 'phone-offline', sequence: 5,
        type: 'workspace.task.progress', privacyRevisions: { conversations: 1, tasks: 0 },
        createdAt: new Date(Date.now() + 1000).toISOString(), payload: { source: 'conversation', metadata: { sessionId: 'new-session' } }
      } })
    });
    assert.equal(staleLinkedTask.body.reason, 'privacy_revision_stale:tasks');

    const deletedProgress = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'progress-delete-001', categories: ['progress'], confirmation: 'DELETE SELECTED DATA' })
    });
    assert.equal(deletedProgress.response.status, 200);
    assert.equal(deletedProgress.body.receipt.categoryRevisions.progress, 1);
    assert.equal((await request('/api/motes/relationship')).body.relationship.xp, 0);
    const realityLogAfterProgressDelete = await request('/api/reality/log');
    assert.equal(realityLogAfterProgressDelete.response.status, 200);
    assert.deepEqual(realityLogAfterProgressDelete.body.entries, []);
    assert.equal(realityLogAfterProgressDelete.body.nextCursor, null);
    const staleProgressReplay = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'offline-progress-before-privacy-delete', origin: 'phone-offline', sequence: 2,
        type: 'mote.exploration', createdAt: '2020-01-01T00:00:00.000Z', payload: { clueType: 'light' }
      } })
    });
    assert.equal(staleProgressReplay.body.businessStatus, 'rejected');
    assert.equal(staleProgressReplay.body.reason, 'privacy_revision_required:progress');
    const staleProgressWithRevision = await request('/api/workspace/events', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: {
        eventId: 'offline-progress-before-privacy-delete-with-revision', origin: 'phone-offline', sequence: 4,
        type: 'mote.exploration', createdAt: '2020-01-01T00:00:00.000Z',
        privacyRevisions: { progress: 1 }, payload: { clueType: 'light' }
      } })
    });
    assert.equal(staleProgressWithRevision.body.reason, 'privacy_data_deleted:progress');

    const initialGoals = await request('/api/goals');
    assert.equal(initialGoals.response.status, 200);
    assert.equal(initialGoals.body.goals.length, 0);
    assert.equal(initialGoals.body.privacyRevision, 0);
    assert.equal(initialGoals.body.taskPrivacyRevision, 1);
    const goalCreated = await request('/api/goals', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '完成个人作品集', description: '按自己的节奏推进', privacyRevision: 0 })
    });
    assert.equal(goalCreated.response.status, 201, JSON.stringify(goalCreated.body));
    const goalId = goalCreated.body.goal.id;
    const goalEdited = await request(`/api/goals/${encodeURIComponent(goalId)}`, {
      method: 'PATCH', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '完成一个小型作品集', privacyRevision: 0 })
    });
    assert.equal(goalEdited.body.goal.title, '完成一个小型作品集');
    assert.equal(goalEdited.body.goal.status, 'active');

    const localGoalProvider = await request('/api/ai/settings', {
      method: 'PATCH', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ activeProviderId: 'local' })
    });
    assert.equal(localGoalProvider.body.settings.activeProviderId, 'local');
    const goalsBeforeDraft = await request('/api/goals');
    const draft = await request(`/api/goals/${encodeURIComponent(goalId)}/draft`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ context: '拆分成轻量步骤', privacyRevision: 0 })
    });
    assert.equal(draft.response.status, 200, JSON.stringify(draft.body));
    assert.ok(draft.body.steps.length > 0 && draft.body.steps.length <= 8);
    assert.equal(draft.body.providerAudit.providerId, 'local');
    assert.equal(draft.body.fallbackReason, null);
    assert.deepEqual((await request('/api/tasks?source=goal')).body.tasks, []);
    assert.deepEqual((await request('/api/goals')).body.goals, goalsBeforeDraft.body.goals);
    const rejectedDraft = await request(`/api/goals/${encodeURIComponent(goalId)}/draft`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ context: 'x'.repeat(1001), privacyRevision: 0 })
    });
    assert.equal(rejectedDraft.response.status, 400);

    const goalArchiveResponse = await request('/api/privacy/export', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ categories: ['goals'], passphrase: 'phonebridge test passphrase' })
    });
    assert.equal(goalArchiveResponse.response.status, 200);
    const goalArchive = decryptArchive(goalArchiveResponse.body.archive, 'phonebridge test passphrase');
    assert.equal(goalArchive.data.goals.goals[0].title, '完成一个小型作品集');
    assert.equal(JSON.stringify(goalArchiveResponse.body).includes('完成一个小型作品集'), false);

    const invalidGoalAccept = await request(`/api/goals/${encodeURIComponent(goalId)}/accept`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ eventId: 'goal-api-empty-steps', steps: [], privacyRevision: 0, taskPrivacyRevision: 1 })
    });
    assert.equal(invalidGoalAccept.response.status, 400);
    assert.deepEqual((await request('/api/tasks?source=goal')).body.tasks, []);
    const acceptedGoalSteps = {
      eventId: 'goal-api-accept-001', privacyRevision: 0, taskPrivacyRevision: 1,
      steps: [
        { title: '完成首页草图', description: '先确认需要展示的内容' },
        { title: '完成可浏览版本', description: '保持范围足够小' },
      ],
    };
    const acceptedGoal = await request(`/api/goals/${encodeURIComponent(goalId)}/accept`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(acceptedGoalSteps)
    });
    assert.equal(acceptedGoal.response.status, 201, JSON.stringify(acceptedGoal.body));
    assert.equal(acceptedGoal.body.tasks.length, 2);
    assert.ok(acceptedGoal.body.tasks.every(task => task.source === 'goal'));
    const acceptedGoalReplay = await request(`/api/goals/${encodeURIComponent(goalId)}/accept`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(acceptedGoalSteps)
    });
    assert.equal(acceptedGoalReplay.body.duplicate, true);
    assert.deepEqual(acceptedGoalReplay.body.tasks.map(task => task.id), acceptedGoal.body.tasks.map(task => task.id));
    const conflictingGoalReplay = await request(`/api/goals/${encodeURIComponent(goalId)}/accept`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ...acceptedGoalSteps, steps: [{ title: 'different content' }] })
    });
    assert.equal(conflictingGoalReplay.response.status, 409);
    assert.equal((await request('/api/tasks?source=goal')).body.tasks.length, 2);

    const firstGoalTask = acceptedGoal.body.tasks[0];
    const runningGoalTask = await request(`/api/tasks/${encodeURIComponent(firstGoalTask.id)}`, {
      method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ state: 'running' })
    });
    assert.equal(runningGoalTask.response.status, 200);
    const blockedGoalDelete = await request(`/api/goals/${encodeURIComponent(goalId)}`, {
      method: 'DELETE', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ privacyRevision: 0 })
    });
    assert.equal(blockedGoalDelete.response.status, 409);
    assert.ok((await request(`/api/goals/${encodeURIComponent(goalId)}`)).body.goal);
    const completedGoalTask = await request(`/api/tasks/${encodeURIComponent(firstGoalTask.id)}`, {
      method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ state: 'succeeded', progress: 100 })
    });
    assert.equal(completedGoalTask.response.status, 200);
    assert.equal((await request('/api/goals')).body.goals[0].milestones[0].status, 'completed');
    const goalDeleted = await request(`/api/goals/${encodeURIComponent(goalId)}`, {
      method: 'DELETE', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ privacyRevision: 0 })
    });
    assert.equal(goalDeleted.response.status, 200, JSON.stringify(goalDeleted.body));
    assert.equal(goalDeleted.body.privacyRevision, 1);
    assert.equal((await request('/api/goals')).body.goals.length, 0);
    assert.deepEqual((await request('/api/tasks?source=goal')).body.tasks, []);

    const retainedGoalCreated = await request('/api/goals', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '任务删除后保留目标', description: '里程碑文本保留', privacyRevision: 1 })
    });
    assert.equal(retainedGoalCreated.response.status, 201);
    const retainedGoalId = retainedGoalCreated.body.goal.id;
    const retainedGoalSteps = {
      eventId: 'goal-api-task-privacy-001', privacyRevision: 1, taskPrivacyRevision: 1,
      steps: [{ title: '被任务隐私删除解绑', description: '目标内容继续保留' }],
    };
    const retainedGoalAccept = await request(`/api/goals/${encodeURIComponent(retainedGoalId)}/accept`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(retainedGoalSteps)
    });
    assert.equal(retainedGoalAccept.response.status, 201);
    const taskPrivacyDelete = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'tasks-delete-goal-unbind', categories: ['tasks'], confirmation: 'DELETE SELECTED DATA' })
    });
    assert.equal(taskPrivacyDelete.response.status, 200);
    assert.equal(taskPrivacyDelete.body.receipt.categoryRevisions.tasks, 2);
    const goalAfterTaskDelete = (await request('/api/goals')).body.goals.find(item => item.id === retainedGoalId);
    assert.equal(goalAfterTaskDelete.title, '任务删除后保留目标');
    assert.equal(goalAfterTaskDelete.milestones[0].title, '被任务隐私删除解绑');
    assert.equal(goalAfterTaskDelete.milestones[0].taskId, null);
    assert.equal(goalAfterTaskDelete.milestones[0].status, 'pending');
    const staleGoalTaskReplay = await request(`/api/goals/${encodeURIComponent(retainedGoalId)}/accept`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(retainedGoalSteps)
    });
    assert.equal(staleGoalTaskReplay.response.status, 409);
    assert.equal(staleGoalTaskReplay.body.code, 'privacy_revision_stale:tasks');
    const currentGoalTaskReplay = await request(`/api/goals/${encodeURIComponent(retainedGoalId)}/accept`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ...retainedGoalSteps, taskPrivacyRevision: 2 })
    });
    assert.equal(currentGoalTaskReplay.body.duplicate, true);
    assert.deepEqual(currentGoalTaskReplay.body.tasks, []);
    assert.deepEqual((await request('/api/tasks?source=goal')).body.tasks, []);

    const deletedGoals = await request('/api/privacy/delete', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ requestId: 'goals-privacy-delete-001', categories: ['goals'], confirmation: 'DELETE SELECTED DATA' })
    });
    assert.equal(deletedGoals.response.status, 200);
    assert.equal(deletedGoals.body.receipt.categoryRevisions.goals, 2);
    assert.equal((await request('/api/goals')).body.goals.length, 0);
    assert.equal((await request('/api/privacy/overview')).body.categories.goals.count, 0);
    const staleGoalCreate = await request('/api/goals', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '陈旧客户端不得重建', privacyRevision: 1 })
    });
    assert.equal(staleGoalCreate.response.status, 409);
    assert.equal(staleGoalCreate.body.code, 'privacy_revision_stale:goals');

  } finally {
    child.kill('SIGTERM');
    try { fs.rmSync(runtimeDir, { recursive: true, force: true }); } catch (_) {}
  }
});
