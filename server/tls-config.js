'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');

function certificateFingerprint(bytes) {
  const certificate = new crypto.X509Certificate(bytes);
  return `sha256:${crypto.createHash('sha256').update(certificate.raw).digest('hex')}`;
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

function normalizeHost(host) {
  return String(host || '').trim().toLowerCase().replace(/^\[|\]$/g, '');
}

function isLoopbackHost(host) {
  const value = normalizeHost(host);
  return value === 'localhost' || value.endsWith('.localhost') || value === '::1' || /^127\./.test(value);
}

function isWildcardHost(host) {
  const value = normalizeHost(host);
  return value === '0.0.0.0' || value === '::';
}

function isPhoneReachableHost(host) {
  const value = String(host || '').trim();
  if (!value || /[\\/?#\s@]/.test(value)) return false;
  let parsed;
  try { parsed = new URL(`https://${value}`); } catch (_) { return false; }
  const normalized = normalizeHost(parsed.hostname);
  return !parsed.port && !parsed.username && !parsed.password && parsed.pathname === '/' &&
    !parsed.search && !parsed.hash && !isLoopbackHost(normalized) && !isWildcardHost(normalized);
}

function pairingAvailabilityError({ bindHost = '127.0.0.1', pairingHost = bindHost, tlsEnabled = false, fixedToken = false } = {}) {
  if (!String(bindHost || '').trim() || isLoopbackHost(bindHost)) {
    return 'Phone pairing requires a server bound to a LAN interface; configure PHONEBRIDGE_BIND first.';
  }
  if (!tlsEnabled) return 'Remote phone pairing requires TLS; configure PHONEBRIDGE_TLS_KEY and PHONEBRIDGE_TLS_CERT.';
  if (fixedToken) return 'Phone pairing is disabled while PHONEBRIDGE_TOKEN is set because the token cannot be rotated.';
  if (!isPhoneReachableHost(pairingHost)) {
    return 'Set PHONEBRIDGE_PAIRING_HOST to a reachable LAN IP or hostname, not a loopback or wildcard address.';
  }
  return null;
}

function buildPairingQrPayload({ pairingId, code, nonce, expiresAt, transport } = {}) {
  const safeTransport = transport && typeof transport === 'object' ? transport : {};
  const endpoint = String(safeTransport.url || '').trim();
  if (!String(pairingId || '').trim() || !/^\d{6}$/.test(String(code || '')) || !String(nonce || '').trim() || !endpoint) {
    throw new Error('pairing QR payload is incomplete');
  }
  const parsedEndpoint = new URL(endpoint);
  if (!['ws:', 'wss:'].includes(parsedEndpoint.protocol) || parsedEndpoint.username || parsedEndpoint.password || parsedEndpoint.pathname !== '/') {
    throw new Error('pairing QR endpoint is invalid');
  }
  const scheme = parsedEndpoint.protocol.slice(0, -1);
  if (scheme !== String(safeTransport.scheme || '').toLowerCase()) throw new Error('pairing QR scheme mismatch');
  if (scheme === 'wss' && !/^sha256:[0-9a-f]{64}$/i.test(String(safeTransport.fingerprint || ''))) {
    throw new Error('pairing QR certificate fingerprint is invalid');
  }
  return JSON.stringify({
    version: 2,
    pairingId: String(pairingId),
    code: String(code),
    nonce: String(nonce),
    expiresAt: Number(expiresAt),
    endpoint,
    scheme,
    fingerprint: safeTransport.fingerprint ? String(safeTransport.fingerprint) : null,
    fingerprintType: 'x509-der-sha256',
  });
}

module.exports = {
  certificateFingerprint,
  loadTlsOptions,
  pairingTransport,
  pairingAvailabilityError,
  isPhoneReachableHost,
  buildPairingQrPayload,
};
