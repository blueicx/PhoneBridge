'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { DeviceHealthStore, overallStatus } = require('./device-health');

test('device health keeps connection, sensors and outbox independent', () => {
  let tick = 0;
  const store = new DeviceHealthStore({}, () => `2026-08-31T00:00:0${++tick}.000Z`);
  store.update({ bridge: 'online', node: 'online' });
  store.update({ camera: 'active', microphone: 'inactive', outboxPending: 3, lastAckAt: '2026-08-31T00:00:01.000Z' });
  const state = store.snapshot();
  assert.equal(state.bridge, 'online');
  assert.equal(state.camera, 'active');
  assert.equal(state.microphone, 'inactive');
  assert.equal(state.outboxPending, 3);
  assert.equal(state.overall, 'degraded');
});

test('auth failure is expired and normalizes unsafe values', () => {
  const store = new DeviceHealthStore({ bridge: 'auth_failed', authorization: 'expired', targetUrl: 'x'.repeat(900) });
  const state = store.snapshot();
  assert.equal(state.overall, 'expired');
  assert.equal(state.targetUrl.length, 512);
  assert.equal(overallStatus({ bridge: 'online', outbox: 'error' }), 'error');
});
