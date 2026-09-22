'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { certificateFingerprint, loadTlsOptions, pairingTransport, buildPairingQrPayload } = require('./tls-config');

test('certificate fingerprint is deterministic and normalized', () => {
  const expected = crypto.createHash('sha256').update('certificate-bytes').digest('hex');
  assert.equal(certificateFingerprint(Buffer.from('certificate-bytes')), `sha256:${expected}`);
});

test('TLS configuration requires both key and certificate and returns a derived fingerprint', () => {
  const fakeFs = {
    readFileSync(file) {
      return Buffer.from(file === 'key.pem' ? 'private-key' : 'certificate');
    },
  };
  const result = loadTlsOptions({ keyPath: 'key.pem', certPath: 'cert.pem', fsImpl: fakeFs });
  assert.deepEqual(result.options, { key: Buffer.from('private-key'), cert: Buffer.from('certificate') });
  assert.match(result.fingerprint, /^sha256:[0-9a-f]{64}$/);
  assert.throws(() => loadTlsOptions({ keyPath: 'key.pem', certPath: '', fsImpl: fakeFs }), /both/i);
});

test('pairing transport advertises secure websocket only when TLS is enabled', () => {
  assert.deepEqual(pairingTransport({ tls: false, host: '127.0.0.1', port: 9501 }), { scheme: 'ws', url: 'ws://127.0.0.1:9501' });
  assert.deepEqual(pairingTransport({ tls: true, host: '192.168.1.9', port: 9503, fingerprint: 'sha256:abc' }), { scheme: 'wss', url: 'wss://192.168.1.9:9503', fingerprint: 'sha256:abc' });
});

test('pairing QR payload is versioned and contains only claim material', () => {
  const payload = JSON.parse(buildPairingQrPayload({
    pairingId: 'pair_1',
    code: '123456',
    nonce: 'nonce',
    expiresAt: 2_000,
    transport: { scheme: 'wss', url: 'wss://192.168.1.9:9503', fingerprint: 'sha256:abc' },
  }));
  assert.deepEqual(payload, {
    version: 1,
    pairingId: 'pair_1',
    code: '123456',
    nonce: 'nonce',
    expiresAt: 2_000,
    endpoint: 'wss://192.168.1.9:9503',
    scheme: 'wss',
    fingerprint: 'sha256:abc',
  });
});
