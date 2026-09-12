const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

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

  } finally {
    child.kill('SIGTERM');
    try { fs.rmSync(runtimeDir, { recursive: true, force: true }); } catch (_) {}
  }
});
