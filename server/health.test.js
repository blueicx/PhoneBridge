const test = require('node:test');
const assert = require('node:assert/strict');
const { HealthChecks } = require('./health');

test('HealthChecks exposes liveness and readiness with dependency details', () => {
  const checks = new HealthChecks({
    dependencies: {
      persistence: () => ({ ok: true, detail: 'loaded' }),
      workspace: () => ({ ok: false, detail: 'recovering' }),
    },
  });
  assert.deepEqual(checks.liveness(), { ok: true, status: 'alive' });
  const readiness = checks.readiness();
  assert.equal(readiness.ok, false);
  assert.equal(readiness.status, 'not_ready');
  assert.equal(readiness.checks.workspace.ok, false);
  assert.equal(readiness.checks.persistence.ok, true);
});
