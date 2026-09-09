const test = require('node:test');
const assert = require('node:assert/strict');
const { WorkspaceStore, createEventEnvelope } = require('./workspace-core');

test('returns a delta sync window and asks for reset when the cursor is too old', () => {
  const store = new WorkspaceStore({ eventRetention: 2 });
  store.acceptEvent(createEventEnvelope({ origin: 'phone', sequence: 1, type: 'workspace.sync_state', payload: { value: 1 } }));
  store.acceptEvent(createEventEnvelope({ origin: 'phone', sequence: 2, type: 'workspace.sync_state', payload: { value: 2 } }));
  store.acceptEvent(createEventEnvelope({ origin: 'phone', sequence: 3, type: 'workspace.sync_state', payload: { value: 3 } }));
  const delta = store.syncState(2);
  assert.equal(delta.resetRequired, false);
  assert.equal(delta.fromRevision, 2);
  assert.equal(delta.toRevision, 3);
  assert.equal(delta.events.length, 1);
  const reset = store.syncState(0);
  assert.equal(reset.resetRequired, true);
  assert.equal(reset.mode, 'snapshot');
});
