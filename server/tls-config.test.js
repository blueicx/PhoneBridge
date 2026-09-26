'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const tls = require('node:tls');
const QRCode = require('qrcode');
const { certificateFingerprint, loadTlsOptions, pairingTransport, pairingAvailabilityError, buildPairingQrPayload } = require('./tls-config');

test('certificate fingerprint is deterministic and normalized', () => {
  const pem = Buffer.from(tls.rootCertificates[0]);
  const expected = crypto.createHash('sha256').update(new crypto.X509Certificate(pem).raw).digest('hex');
  assert.equal(certificateFingerprint(pem), `sha256:${expected}`);
  assert.throws(() => certificateFingerprint(Buffer.from('not a certificate')));
});

test('TLS configuration requires both key and certificate and returns a derived fingerprint', () => {
  const fakeFs = {
    readFileSync(file) {
      return Buffer.from(file === 'key.pem' ? 'private-key' : tls.rootCertificates[0]);
    },
  };
  const result = loadTlsOptions({ keyPath: 'key.pem', certPath: 'cert.pem', fsImpl: fakeFs });
  assert.deepEqual(result.options, { key: Buffer.from('private-key'), cert: Buffer.from(tls.rootCertificates[0]) });
  assert.match(result.fingerprint, /^sha256:[0-9a-f]{64}$/);
  assert.throws(() => loadTlsOptions({ keyPath: 'key.pem', certPath: '', fsImpl: fakeFs }), /both/i);
});

test('pairing transport advertises secure websocket only when TLS is enabled', () => {
  assert.deepEqual(pairingTransport({ tls: false, host: '127.0.0.1', port: 9501 }), { scheme: 'ws', url: 'ws://127.0.0.1:9501' });
  assert.deepEqual(pairingTransport({ tls: true, host: '192.168.1.9', port: 9503, fingerprint: 'sha256:abc' }), { scheme: 'wss', url: 'wss://192.168.1.9:9503', fingerprint: 'sha256:abc' });
});

test('pairing QR payload declares the DER certificate fingerprint format', () => {
  const fingerprint = 'sha256:' + 'a'.repeat(64);
  const payload = JSON.parse(buildPairingQrPayload({
    pairingId: 'pair_1',
    code: '123456',
    nonce: 'nonce',
    expiresAt: 2_000,
    transport: { scheme: 'wss', url: 'wss://192.168.1.9:9503', fingerprint },
  }));
  assert.deepEqual(payload, {
    version: 2,
    pairingId: 'pair_1',
    code: '123456',
    nonce: 'nonce',
    expiresAt: 2_000,
    endpoint: 'wss://192.168.1.9:9503',
    scheme: 'wss',
    fingerprint,
    fingerprintType: 'x509-der-sha256',
  });
  assert.throws(() => buildPairingQrPayload({
    pairingId: 'pair_2', code: '123456', nonce: 'nonce', expiresAt: 2_000,
    transport: { scheme: 'wss', url: 'wss://192.168.1.9:9503', fingerprint: 'sha256:abc' },
  }), /fingerprint/);
});

test('phone pairing requires a reachable TLS listener and concrete pairing host', () => {
  assert.match(pairingAvailabilityError({ bindHost: '127.0.0.1', pairingHost: '192.168.1.9', tlsEnabled: true }), /LAN interface/i);
  assert.match(pairingAvailabilityError({ bindHost: '0.0.0.0', pairingHost: '0.0.0.0', tlsEnabled: true }), /PHONEBRIDGE_PAIRING_HOST/i);
  assert.match(pairingAvailabilityError({ bindHost: '0.0.0.0', pairingHost: '192.168.1.9', tlsEnabled: false }), /TLS/i);
  assert.match(pairingAvailabilityError({ bindHost: '0.0.0.0', pairingHost: '192.168.1.9', tlsEnabled: true, fixedToken: true }), /PHONEBRIDGE_TOKEN/i);
  assert.equal(pairingAvailabilityError({ bindHost: '0.0.0.0', pairingHost: '192.168.1.9', tlsEnabled: true }), null);
});

test('QR payload can be rendered as a browser-ready PNG data URL', async () => {
  const payload = buildPairingQrPayload({
    pairingId: 'pair_1',
    code: '123456',
    nonce: 'nonce',
    expiresAt: 2_000,
    transport: { scheme: 'wss', url: 'wss://192.168.1.9:9503', fingerprint: `sha256:${'a'.repeat(64)}` },
  });
  const dataUrl = await QRCode.toDataURL(payload, { errorCorrectionLevel: 'M', margin: 2, width: 300 });
  assert.match(dataUrl, /^data:image\/png;base64,/);
  assert.ok(Buffer.from(dataUrl.split(',')[1], 'base64').length > 100);
});
