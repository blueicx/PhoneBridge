const test = require('node:test');
const assert = require('node:assert/strict');
const { createStructuredLogger, redact } = require('./structured-log');

test('structured logger emits JSON and redacts secret-shaped fields', () => {
  const lines = [];
  const logger = createStructuredLogger({ sink: line => lines.push(line), now: () => 1700000000000 });
  logger.warn('token.rotated', { token: 'super-secret-token', nested: { password: 'pw' }, count: 2 });
  assert.equal(lines.length, 1);
  const record = JSON.parse(lines[0]);
  assert.equal(record.level, 'warn');
  assert.equal(record.event, 'token.rotated');
  assert.equal(record.data.token, '[REDACTED]');
  assert.equal(record.data.nested.password, '[REDACTED]');
  assert.equal(record.data.count, 2);
  assert.equal(redact('Bearer abc123'), '[REDACTED]');
});
