'use strict';

const crypto = require('node:crypto');

class PairingManager {
  constructor({ now = () => Date.now(), ttlMs = 5 * 60 * 1000 } = {}) {
    this.now = now;
    this.ttlMs = Math.max(30_000, Math.min(15 * 60 * 1000, Number(ttlMs) || 5 * 60 * 1000));
    this.pending = new Map();
  }

  start({ host = '127.0.0.1', port = 9501, fingerprint = null } = {}) {
    this.expire();
    const id = `pair_${crypto.randomUUID()}`;
    const code = String(crypto.randomInt(100000, 1000000));
    const nonce = crypto.randomBytes(12).toString('base64url');
    const expiresAt = this.now() + this.ttlMs;
    this.pending.set(id, {
      id,
      codeHash: this.hash(`${code}:${nonce}`),
      nonceHash: this.hash(nonce),
      expiresAt,
      used: false,
    });
    return { id, code, nonce, host: String(host), port: Number(port), fingerprint: fingerprint || null, expiresAt };
  }

  claim({ id, code, nonce } = {}) {
    this.expire();
    const record = this.pending.get(String(id || ''));
    if (!record || record.used || record.expiresAt <= this.now()) throw new Error('pairing code expired or not found');
    if (record.nonceHash !== this.hash(String(nonce || '')) || record.codeHash !== this.hash(`${String(code || '')}:${String(nonce || '')}`)) {
      throw new Error('pairing code invalid');
    }
    record.used = true;
    this.pending.delete(record.id);
    return { paired: true, pairingId: record.id };
  }

  expire() {
    const now = this.now();
    for (const [id, record] of this.pending) if (record.expiresAt <= now || record.used) this.pending.delete(id);
  }

  hash(value) { return crypto.createHash('sha256').update(String(value)).digest('hex'); }
}

module.exports = { PairingManager };
