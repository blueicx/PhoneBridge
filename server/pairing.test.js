const test = require('node:test');
const assert = require('node:assert/strict');
const { PairingManager } = require('./pairing');

test('pairing codes are one-time and expire', () => {
  let now = 1000;
  const manager = new PairingManager({ now: () => now, ttlMs: 30000 });
  const request = manager.start({ host: '127.0.0.1', port: 9501 });
  assert.equal(manager.claim({ id: request.id, code: request.code, nonce: request.nonce }).paired, true);
  assert.throws(() => manager.claim({ id: request.id, code: request.code, nonce: request.nonce }), /expired|not found/);
  const expired = manager.start();
  now += 30001;
  assert.throws(() => manager.claim({ id: expired.id, code: expired.code, nonce: expired.nonce }), /expired|not found/);
});

test('wrong nonce or code never consumes a valid pairing', () => {
  const manager = new PairingManager({ now: () => 1000 });
  const request = manager.start();
  assert.throws(() => manager.claim({ id: request.id, code: '000000', nonce: request.nonce }), /invalid/);
  assert.equal(manager.claim({ id: request.id, code: request.code, nonce: request.nonce }).paired, true);
});

test('pairing offer keeps one-time claims bounded and does not persist plaintext state', () => {
  const manager = new PairingManager({ now: () => 1000 });
  const offer = manager.start({ host: '192.168.1.9', port: 9503, fingerprint: 'sha256:test' });
  assert.equal(manager.pending.get(offer.id).code, undefined);
  assert.equal(manager.pending.get(offer.id).nonce, undefined);
  assert.equal(offer.fingerprint, 'sha256:test');
});
