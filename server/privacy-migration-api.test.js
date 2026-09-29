const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { RuntimePersistence } = require('./runtime-persistence');

const PORT = 19643;
const TOKEN = 'privacy-migration-api-test-token-123456';
const BASE = `http://127.0.0.1:${PORT}`;

async function request(route, options = {}) {
  const response = await fetch(`${BASE}${route}`, {
    ...options,
    headers: { 'x-phonebridge-token': TOKEN, ...(options.headers || {}) },
  });
  return { response, body: await response.json() };
}

test('privacy migration API persists clear/keep choices, returns revisions, and rejects conflicts', { timeout: 20_000 }, async () => {
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-privacy-migration-'));
  const persistence = new RuntimePersistence({ dir: runtimeDir });
  persistence.save('privacy-audit', {
    version: 1,
    receipts: Array.from({ length: 100 }, (_, index) => ({
      requestId: `legacy-delete-${String(index).padStart(4, '0')}`,
      categories: ['conversations'],
      status: 'completed',
      completedAt: new Date(1_700_000_000_000 + index).toISOString(),
    })),
  });
  const child = spawn(process.execPath, ['index.js'], {
    cwd: __dirname,
    env: {
      ...process.env,
      PHONEBRIDGE_PORT: String(PORT),
      PHONEBRIDGE_TOKEN: TOKEN,
      PHONEBRIDGE_RUNTIME_DIR: runtimeDir,
      PHONEBRIDGE_LOCK_FILE: path.join(runtimeDir, 'node.lock'),
    },
    stdio: 'ignore',
    windowsHide: true,
  });

  try {
    const started = Date.now();
    let overview;
    while (Date.now() - started < 12_000) {
      if (child.exitCode !== null) throw new Error(`server exited with ${child.exitCode}`);
      try {
        overview = await request('/api/privacy/overview');
        if (overview.response.ok) break;
      } catch (_) {}
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    assert.equal(overview?.body?.migration?.status, 'required');
    assert.equal(overview.body.categories.routines.count, 0);
    assert.equal(overview.body.categories.goals.count, 0);
    const denied = await fetch(`${BASE}/api/privacy/migration/resolve`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ category: 'conversations', decision: 'keep' }),
    });
    assert.equal(denied.status, 401);

    const payload = { category: 'conversations', decision: 'keep' };
    const first = await request('/api/privacy/migration/resolve', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload),
    });
    assert.equal(first.response.status, 200);
    assert.equal(first.body.migration.decisions.conversations, 'keep');
    assert.equal(first.body.migration.categoryRevisions.conversations, 101);

    const replay = await request('/api/privacy/migration/resolve', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload),
    });
    assert.equal(replay.response.status, 200);
    assert.equal(replay.body.migration.duplicate, true);
    assert.equal(replay.body.migration.categoryRevisions.conversations, 101);

    const conflict = await request('/api/privacy/migration/resolve', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ category: 'conversations', decision: 'clear' }),
    });
    assert.equal(conflict.response.status, 409);

    const refreshed = await request('/api/privacy/overview');
    assert.equal(refreshed.body.categories.conversations.migrationRequired, false);
    assert.equal(refreshed.body.categories.conversations.revision, 101);
    assert.equal(refreshed.body.migration.status, 'required');
  } finally {
    child.kill();
    if (child.exitCode === null) await new Promise(resolve => child.once('exit', resolve));
    fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
});
