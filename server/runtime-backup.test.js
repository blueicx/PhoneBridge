'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { RUNTIME_SCHEMA_VERSION, checksumFor, migrateState, validateEnvelope } = require('./runtime-persistence');
const { sanitizeRuntimeState } = require('./runtime-backup');

function envelope(state) {
  const normalized = migrateState(state);
  return {
    schemaVersion: RUNTIME_SCHEMA_VERSION,
    savedAt: '2026-09-26T00:00:00.000Z',
    checksum: checksumFor(normalized),
    state: normalized,
  };
}

test('runtime backup preserves conversations while removing logs, precise coordinates and raw media', () => {
  const input = envelope({
    logs: [{ message: 'Bearer do-not-back-up' }],
    chatHistory: [{ role: 'user', text: 'private conversation' }],
    frameCount: 7,
    tasks: [{ id: 'task_1', title: 'restore me' }],
    nested: { logs: ['also excluded'], safe: true },
    reality: {
      region: 'cell:coarse-42',
      latitude: 31.2304,
      longitude: 121.4737,
      camera: { imageData: 'raw-photo-payload' },
    },
  });
  const result = sanitizeRuntimeState(JSON.stringify(input));

  assert.equal(validateEnvelope(result).ok, true);
  assert.equal(result.state.frameCount, 7);
  assert.equal(result.state.tasks[0].id, 'task_1');
  assert.equal(result.state.logs, undefined);
  assert.deepEqual(result.state.chatHistory, [{ role: 'user', text: 'private conversation' }]);
  assert.equal(result.state.nested.logs, undefined);
  assert.equal(result.state.nested.safe, true);
  assert.equal(result.state.reality.region, 'cell:coarse-42');
  assert.equal(result.state.reality.latitude, undefined);
  assert.equal(result.state.reality.longitude, undefined);
  assert.equal(result.state.reality.camera.imageData, undefined);
  assert.equal(JSON.stringify(result).includes('do-not-back-up'), false);
  assert.equal(JSON.stringify(result).includes('private conversation'), true);
  assert.equal(JSON.stringify(result).includes('raw-photo-payload'), false);
});

test('runtime backup migrates legacy raw state and recalculates its checksum', () => {
  const result = sanitizeRuntimeState(JSON.stringify({ logs: ['discard'], frameCount: 3 }));
  assert.equal(result.schemaVersion, RUNTIME_SCHEMA_VERSION);
  assert.equal(validateEnvelope(result).ok, true);
  assert.equal(result.state.frameCount, 3);
  assert.equal(result.state.logs, undefined);
});

test('runtime backup refuses a corrupt persisted envelope', () => {
  const input = envelope({ frameCount: 2 });
  input.checksum = '0'.repeat(64);
  assert.throws(() => sanitizeRuntimeState(JSON.stringify(input)), /checksum/i);
});
