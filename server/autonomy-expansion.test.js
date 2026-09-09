const test = require('node:test');
const assert = require('node:assert/strict');
const { WorkspaceStore } = require('./workspace-core');

test('restricted tools create a single-use approval and reject replay', () => {
  let now = 1000;
  const store = new WorkspaceStore({ now: () => now });
  store.registerTool({ id: 'device.safe', title: 'safe', autonomyLevel: 'reversible', invoke: args => args });
  store.updateAutonomyPolicy({ level: 'whitelist', allowedTools: ['device.safe'], confirmationRules: [{ toolId: 'device.safe', requireConfirmation: true }] });
  const pending = store.requestToolApproval({ toolId: 'device.safe', args: { value: 1 }, expiresAt: new Date(5000).toISOString() });
  assert.equal(pending.state, 'needs_confirmation');
  const approved = store.approveToolApproval(pending.id);
  assert.equal(approved.state, 'approved');
  const result = store.invokeApprovedTool(pending.id);
  assert.deepEqual(result.result, { value: 1 });
  assert.throws(() => store.invokeApprovedTool(pending.id), /consumed|replay/i);
  now = 6000;
  const expired = store.requestToolApproval({ toolId: 'device.safe', args: {}, expiresAt: new Date(7000).toISOString() });
  now = 8000;
  assert.throws(() => store.approveToolApproval(expired.id), /expired/i);
});

test('approval refuses sensitive arguments instead of persisting them', () => {
  const store = new WorkspaceStore();
  store.registerTool({ id: 'device.safe', title: 'safe', autonomyLevel: 'reversible', invoke: args => args });
  assert.throws(() => store.requestToolApproval({ toolId: 'device.safe', args: { token: 'secret-value' } }), /sensitive/i);
  assert.equal(store.listToolApprovals().length, 0);
});
