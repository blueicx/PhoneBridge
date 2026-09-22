'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');

function certificateFingerprint(bytes) {
  return `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`;
}

function loadTlsOptions({ keyPath = '', certPath = '', fsImpl = fs } = {}) {
  const key = String(keyPath || '').trim();
  const cert = String(certPath || '').trim();
  if (!key && !cert) return { options: null, fingerprint: null };
  if (!key || !cert) throw new Error('both TLS key and certificate must be provided together');
  const keyBytes = fsImpl.readFileSync(key);
  const certBytes = fsImpl.readFileSync(cert);
  if (!keyBytes.length || !certBytes.length) throw new Error('TLS key and certificate must not be empty');
  return {
    options: { key: keyBytes, cert: certBytes },
    fingerprint: certificateFingerprint(certBytes),
  };
}

function pairingTransport({ tls = false, host = '127.0.0.1', port = 9501, fingerprint = null } = {}) {
  const scheme = tls ? 'wss' : 'ws';
  const result = { scheme, url: `${scheme}://${String(host)}:${Number(port)}` };
  if (tls && fingerprint) result.fingerprint = String(fingerprint);
  return result;
}

function buildPairingQrPayload({ pairingId, code, nonce, expiresAt, transport } = {}) {
  const safeTransport = transport && typeof transport === 'object' ? transport : {};
  const endpoint = String(safeTransport.url || '').trim();
  if (!String(pairingId || '').trim() || !/^\d{6}$/.test(String(code || '')) || !String(nonce || '').trim() || !endpoint) {
    throw new Error('pairing QR payload is incomplete');
  }
  return JSON.stringify({
    version: 1,
    pairingId: String(pairingId),
    code: String(code),
    nonce: String(nonce),
    expiresAt: Number(expiresAt),
    endpoint,
    scheme: String(safeTransport.scheme || '').toLowerCase(),
    fingerprint: safeTransport.fingerprint ? String(safeTransport.fingerprint) : null,
  });
}

module.exports = { certificateFingerprint, loadTlsOptions, pairingTransport, buildPairingQrPayload };
