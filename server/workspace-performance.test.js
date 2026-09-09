const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { RevisionSnapshotCache, BroadcastCoalescer, PersistenceScheduler } = require('./workspace-performance');
const { WorkspaceStore } = require('./workspace-core');

test('RevisionSnapshotCache serializes each revision once and separates summary view', () => {
  let revision = 7;
  let builds = 0;
  const cache = new RevisionSnapshotCache({ getRevision: () => revision, build: () => { builds += 1; return { full: JSON.stringify({ revision }), summary: JSON.stringify({ revision, view: 'summary' }) }; } });
  assert.equal(cache.get('full'), cache.get('full'));
  assert.equal(JSON.parse(cache.get('summary')).view, 'summary');
  assert.equal(builds, 1);
  revision = 8;
  assert.equal(JSON.parse(cache.get('full')).revision, 8);
  assert.equal(builds, 2);
});

test('BroadcastCoalescer emits only the newest payload in a burst', async () => {
  const sent = [];
  const coalescer = new BroadcastCoalescer({ send: payload => sent.push(payload), delayMs: 5 });
  coalescer.enqueue({ revision: 1 });
  coalescer.enqueue({ revision: 2 });
  await coalescer.flush();
  assert.deepEqual(sent, [{ revision: 2 }]);
});

test('PersistenceScheduler coalesces writes and supports immediate flush', async () => {
  let writes = 0;
  const scheduler = new PersistenceScheduler({ write: () => { writes += 1; }, delayMs: 5 });
  scheduler.schedule(); scheduler.schedule(); await scheduler.flush(); assert.equal(writes, 1);
  scheduler.schedule(); scheduler.flushNow(); assert.equal(writes, 2);
});

test('WorkspaceStore can defer ordinary snapshot writes until an explicit flush', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-workspace-'));
  const snapshotPath = path.join(root, 'state.json');
  const store = new WorkspaceStore({ snapshotPath, persistDebounceMs: 5 });
  store.createSession({ id: 'session-performance', title: '性能测试' });
  assert.equal(fs.existsSync(snapshotPath), false);
  await store.flushPersistence();
  assert.equal(JSON.parse(fs.readFileSync(snapshotPath, 'utf8')).sessions[0].id, 'session-performance');
  fs.rmSync(root, { recursive: true, force: true });
});
