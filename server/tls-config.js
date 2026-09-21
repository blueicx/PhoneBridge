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

module.exports = { certificateFingerprint, loadTlsOptions, pairingTransport };
