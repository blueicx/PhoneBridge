const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  RUNTIME_SCHEMA_VERSION,
  RuntimePersistence,
  migrateState,
  validateEnvelope,
  checksumFor,
} = require('./runtime-persistence');

function tempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-persistence-'));
}

test('RuntimePersistence saves checksummed envelopes atomically and restores them', () => {
  const dir = tempDir();
  const persistence = new RuntimePersistence({ dir, maxBackups: 2 });
  persistence.save('workspace-state', { tasks: [{ id: 't1' }] });

  const envelope = JSON.parse(fs.readFileSync(path.join(dir, 'workspace-state.json'), 'utf8'));
  assert.equal(envelope.schemaVersion, RUNTIME_SCHEMA_VERSION);
  assert.equal(typeof envelope.checksum, 'string');
  assert.deepEqual(persistence.load('workspace-state').tasks, [{ id: 't1' }]);
  assert.equal(validateEnvelope(envelope).ok, true);
});

test('RuntimePersistence migrates legacy raw state and preserves a backup on replacement', () => {
  const dir = tempDir();
  fs.writeFileSync(path.join(dir, 'runtime-state.json'), JSON.stringify({ tasks: [], petState: { level: 2 } }));
  const persistence = new RuntimePersistence({ dir, maxBackups: 2 });

  const result = persistence.loadWithMeta('runtime-state', {});
  assert.equal(result.migrated, true);
  assert.equal(result.state.schemaVersion, RUNTIME_SCHEMA_VERSION);
  assert.equal(result.state.petState.level, 2);
  assert.equal(fs.existsSync(path.join(dir, 'runtime-state.json.bak.1')), false);
  persistence.save('runtime-state', { schemaVersion: RUNTIME_SCHEMA_VERSION, marker: 'new' });
  assert.equal(fs.existsSync(path.join(dir, 'runtime-state.json.bak.1')), true);
});

test('RuntimePersistence quarantines corruption and recovers the last backup', () => {
  const dir = tempDir();
  const persistence = new RuntimePersistence({ dir, maxBackups: 2 });
  persistence.save('bundle', { value: 'first' });
  persistence.save('bundle', { value: 'second' });
  fs.writeFileSync(path.join(dir, 'bundle.json'), '{broken');

  const result = persistence.loadWithMeta('bundle', { value: 'default' });
  assert.equal(result.recovered, true);
  assert.equal(result.state.value, 'first');
  assert.ok(result.quarantinedPath && fs.existsSync(result.quarantinedPath));
});

test('RuntimePersistence rejects a tampered checksum instead of trusting the snapshot', () => {
  const dir = tempDir();
  const persistence = new RuntimePersistence({ dir, maxBackups: 2 });
  persistence.save('tampered', { value: 'safe' });
  const file = path.join(dir, 'tampered.json');
  const envelope = JSON.parse(fs.readFileSync(file, 'utf8'));
  envelope.state.value = 'tampered';
  fs.writeFileSync(file, JSON.stringify(envelope));
  const result = persistence.loadWithMeta('tampered', { value: 'default' });
  assert.equal(result.state.value, 'default');
  assert.ok(result.quarantinedPath && fs.existsSync(result.quarantinedPath));
});

test('RuntimePersistence migrates a valid schema v2 envelope to v3', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-schema-v2-'));
  const state = { marker: 'legacy', aiSettings: { activeProviderId: 'local' } };
  fs.writeFileSync(path.join(dir, 'legacy.json'), JSON.stringify({
    schemaVersion: 2,
    savedAt: new Date(0).toISOString(),
    checksum: checksumFor(state),
    state,
  }));
  const persistence = new RuntimePersistence({ dir });
  const result = persistence.loadWithMeta('legacy', {});
  assert.equal(result.migrated, true);
  assert.equal(result.state.schemaVersion, RUNTIME_SCHEMA_VERSION);
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir, 'legacy.json'), 'utf8')).schemaVersion, RUNTIME_SCHEMA_VERSION);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('migrateState upgrades old runtime fields without leaking credentials', () => {
  const migrated = migrateState({ apiKey: 'do-not-persist', providerSettings: { activeProviderId: 'local' } });
  assert.equal(migrated.schemaVersion, RUNTIME_SCHEMA_VERSION);
  assert.equal(migrated.providerSettings.activeProviderId, 'local');
  assert.equal('apiKey' in migrated, false);
});
