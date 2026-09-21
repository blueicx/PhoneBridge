const test = require('node:test');
const assert = require('node:assert/strict');
const { DeviceSimulator, coarseRegion } = require('./device-simulator');

test('coarseRegion is stable and never exposes raw coordinates', () => {
  const region = coarseRegion(31.2304, 121.4737);
  assert.equal(region, 'cell:1561:6073');
  assert.doesNotMatch(region, /31\.2304|121\.4737/);
});

test('simulator produces deterministic reality events for the same seed and time', () => {
  const now = () => 1700000000000;
  const a = new DeviceSimulator({ now, seed: 'fixed', region: 'cell:1:2' });
  const b = new DeviceSimulator({ now, seed: 'fixed', region: 'cell:1:2' });
  assert.deepEqual(a.realityEvent(), b.realityEvent());
  assert.equal(a.apply({ type: 'network', online: false }).network, 'offline');
  assert.equal(a.apply({ type: 'sensor', camera: true }).camera, true);
  assert.equal(a.apply({ type: 'telemetry', fps: 18 }).fps, 18);
});

test('simulator validates commands and normalizes bounded values', () => {
  const sim = new DeviceSimulator({ seed: 'bounds' });
  assert.equal(sim.apply({ type: 'region', bearing: -1, distanceBand: 'invalid', clueType: 'invalid' }).bearing, 359);
  assert.throws(() => sim.apply({ type: 'unknown' }), /unsupported simulator command/);
  assert.throws(() => coarseRegion('x', 1), /valid latitude/);
});
