const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
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

    const simulator = await request('/api/dev/simulator');
    assert.equal(simulator.response.status, 200);
    assert.equal(simulator.body.state.seed, 'api-test');

    const privacyBefore = await request('/api/privacy/overview');
    assert.equal(privacyBefore.response.status, 200);
    assert.equal(privacyBefore.body.categories.memories.count, 1);
    assert.equal(privacyBefore.body.categories.conversations.count, 0);
    assert.ok(privacyBefore.body.excluded.includes('Provider 密钥'));

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

  } finally {
    child.kill('SIGTERM');
    try { fs.rmSync(runtimeDir, { recursive: true, force: true }); } catch (_) {}
  }
});
