'use strict';

const crypto = require('node:crypto');

const FORMAT = 'phonebridge-encrypted-export';
const FORMAT_VERSION = 1;
const CONFIRMATION = 'DELETE SELECTED DATA';
const MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
const MAX_RECEIPTS = 100;
const KDF = Object.freeze({ name: 'scrypt', n: 16384, r: 8, p: 1, keyBytes: 32 });

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }

function normalizePassphrase(value) {
  const passphrase = String(value || '');
  if (passphrase.length < 12 || passphrase.length > 1024) throw new Error('passphrase must be 12-1024 characters');
  return passphrase;
}

function envelopeAad(envelope) {
  return Buffer.from(JSON.stringify({
    format: envelope.format,
    version: envelope.version,
    kdf: { ...envelope.kdf, salt: undefined },
    cipher: { name: envelope.cipher?.name, nonce: envelope.cipher?.nonce },
    plaintextSha256: envelope.plaintextSha256,
  }));
}

function deriveKey(passphrase, salt) {
  return crypto.scryptSync(normalizePassphrase(passphrase), salt, KDF.keyBytes, {
    N: KDF.n,
    r: KDF.r,
    p: KDF.p,
    maxmem: 64 * 1024 * 1024,
  });
}

function encryptArchive(value, passphrase) {
  return encryptBytes(Buffer.from(JSON.stringify(value), 'utf8'), passphrase);
}

function encryptBytes(value, passphrase) {
  const plaintext = Buffer.from(value);
  if (plaintext.length > MAX_ARCHIVE_BYTES) throw new Error('privacy export is too large');
  const salt = crypto.randomBytes(16);
  const nonce = crypto.randomBytes(12);
  const envelope = {
    format: FORMAT,
    version: FORMAT_VERSION,
    kdf: { ...KDF, salt: salt.toString('base64') },
    cipher: { name: 'aes-256-gcm', nonce: nonce.toString('base64') },
    plaintextSha256: crypto.createHash('sha256').update(plaintext).digest('hex'),
  };
  const cipher = crypto.createCipheriv('aes-256-gcm', deriveKey(passphrase, salt), nonce);
  cipher.setAAD(envelopeAad(envelope));
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  envelope.cipher.tag = cipher.getAuthTag().toString('base64');
  envelope.ciphertext = ciphertext.toString('base64');
  return envelope;
}

function decryptArchive(envelope, passphrase) {
  const plaintext = decryptBytes(envelope, passphrase);
  try { return JSON.parse(plaintext.toString('utf8')); } catch (_) { throw new Error('privacy archive payload is invalid'); }
}

function decryptBytes(envelope, passphrase) {
  if (!envelope || envelope.format !== FORMAT || envelope.version !== FORMAT_VERSION ||
      envelope.kdf?.name !== KDF.name || envelope.kdf?.n !== KDF.n || envelope.kdf?.r !== KDF.r || envelope.kdf?.p !== KDF.p ||
      envelope.cipher?.name !== 'aes-256-gcm') throw new Error('unsupported privacy archive format');
  const salt = Buffer.from(String(envelope.kdf.salt || ''), 'base64');
  const nonce = Buffer.from(String(envelope.cipher.nonce || ''), 'base64');
  const tag = Buffer.from(String(envelope.cipher.tag || ''), 'base64');
  const ciphertext = Buffer.from(String(envelope.ciphertext || ''), 'base64');
  if (salt.length !== 16 || nonce.length !== 12 || tag.length !== 16 || ciphertext.length > MAX_ARCHIVE_BYTES) throw new Error('invalid privacy archive');
  const decipher = crypto.createDecipheriv('aes-256-gcm', deriveKey(passphrase, salt), nonce);
  decipher.setAAD(envelopeAad(envelope));
  decipher.setAuthTag(tag);
  let plaintext;
  try { plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]); }
  catch (_) { throw new Error('privacy archive authentication failed'); }
  const digest = crypto.createHash('sha256').update(plaintext).digest();
  const expected = Buffer.from(String(envelope.plaintextSha256 || ''), 'hex');
  if (expected.length !== digest.length || !crypto.timingSafeEqual(expected, digest)) throw new Error('privacy archive integrity check failed');
  return plaintext;
}

class PrivacyCenter {
  constructor({ categories, persistence = null, now = () => Date.now() } = {}) {
    if (!categories || typeof categories !== 'object' || !Object.keys(categories).length) throw new Error('privacy categories are required');
    this.categories = new Map();
    for (const [id, adapter] of Object.entries(categories)) {
      if (!/^[a-z][a-z0-9_]{0,31}$/.test(id) || !adapter || typeof adapter.count !== 'function' || typeof adapter.export !== 'function' || typeof adapter.clear !== 'function') {
        throw new Error(`invalid privacy category adapter: ${id}`);
      }
      this.categories.set(id, adapter);
    }
    this.persistence = persistence;
    this.now = now;
    this.activeDeletion = null;
    this.activeMutations = 0;
    this.mutationWaiters = [];
    this.inFlightDeletes = new Map();
    const saved = persistence?.load?.('privacy-audit', null);
    this.receipts = Array.isArray(saved?.receipts) ? saved.receipts.slice(-MAX_RECEIPTS) : [];
  }

  _save() {
    this.persistence?.save?.('privacy-audit', { version: 1, receipts: this.receipts.slice(-MAX_RECEIPTS) });
  }

  _normalizeCategories(value) {
    const ids = [...new Set((Array.isArray(value) ? value : []).map(item => String(item || '').trim()))].sort();
    if (!ids.length) throw new Error('at least one privacy category is required');
    for (const id of ids) if (!this.categories.has(id)) throw new Error(`unknown privacy category: ${id}`);
    return ids;
  }

  overview() {
    const categories = {};
    for (const [id, adapter] of this.categories) {
      categories[id] = { label: String(adapter.label || id), count: Math.max(0, Number(adapter.count()) || 0) };
    }
    return {
      formatVersion: 1,
      generatedAt: new Date(this.now()).toISOString(),
      categories,
      excluded: ['访问令牌与配对凭据', 'Provider 密钥', '原始照片/画面', '精确位置与连续轨迹'],
      latestDeletion: this.receipts.at(-1) ? clone(this.receipts.at(-1)) : null,
      recentDeletions: this.receipts.filter(receipt => receipt.status === 'completed').map(clone),
    };
  }

  exportEncrypted({ categories = [...this.categories.keys()], passphrase } = {}) {
    const selected = this._normalizeCategories(categories);
    normalizePassphrase(passphrase);
    const data = {};
    for (const id of selected) data[id] = clone(this.categories.get(id).export());
    const payload = {
      formatVersion: 1,
      createdAt: new Date(this.now()).toISOString(),
      categories: selected,
      excluded: ['访问令牌与配对凭据', 'Provider 密钥', '原始照片/画面', '精确位置与连续轨迹'],
      data,
    };
    return encryptArchive(payload, passphrase);
  }

  isDeleting() {
    return this.activeDeletion !== null;
  }

  beginMutation() {
    if (this.activeDeletion) throw new Error('privacy deletion is in progress');
    this.activeMutations += 1;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.activeMutations = Math.max(0, this.activeMutations - 1);
      if (this.activeMutations === 0) {
        const waiters = this.mutationWaiters.splice(0);
        waiters.forEach(resolve => resolve());
      }
    };
  }

  _waitForMutations() {
    if (this.activeMutations === 0) return Promise.resolve();
    return new Promise(resolve => this.mutationWaiters.push(resolve));
  }

  async delete(options = {}) {
    const { requestId, categories, confirmation } = options;
    const id = String(requestId || '').trim();
    if (!/^[A-Za-z0-9_-]{8,96}$/.test(id)) throw new Error('request id must be 8-96 safe characters');
    if (confirmation !== CONFIRMATION) throw new Error('explicit confirmation is required');
    const selected = this._normalizeCategories(categories);
    const inFlight = this.inFlightDeletes.get(id);
    if (inFlight) {
      if (JSON.stringify(inFlight.categories) !== JSON.stringify(selected)) throw new Error('request id is already bound to different categories');
      return inFlight.promise.then(receipt => ({ ...clone(receipt), duplicate: true }));
    }
    if (this.activeDeletion) throw new Error('another privacy deletion is in progress');
    this.activeDeletion = { requestId: id, categories: selected };
    const operation = this._deleteSelected(id, selected);
    this.inFlightDeletes.set(id, { categories: selected, promise: operation });
    return operation.finally(() => {
      if (this.inFlightDeletes.get(id)?.promise === operation) this.inFlightDeletes.delete(id);
      this.activeDeletion = null;
    });
  }

  async _deleteSelected(id, selected) {
    let receipt = this.receipts.find(item => item.requestId === id);
    if (receipt && JSON.stringify(receipt.categories) !== JSON.stringify(selected)) throw new Error('request id is already bound to different categories');
    if (receipt?.status === 'completed') return { ...clone(receipt), duplicate: true };
    const releases = [];
    try {
      if (!receipt) {
        for (const category of selected) await this.categories.get(category).validateClear?.();
        receipt = { requestId: id, categories: selected, status: 'pending', completedCategories: [], createdAt: new Date(this.now()).toISOString() };
        this.receipts.push(receipt);
        this.receipts = this.receipts.slice(-MAX_RECEIPTS);
        this._save();
      }

      for (const category of selected) {
        if (receipt.completedCategories.includes(category)) continue;
        const release = await this.categories.get(category).prepareDelete?.();
        if (typeof release === 'function') releases.push(release);
      }
      await this._waitForMutations();

      receipt.deletedCounts ||= {};
      for (const category of selected) {
        if (receipt.completedCategories.includes(category)) continue;
        try {
          const result = await this.categories.get(category).clear();
          receipt.deletedCounts[category] = Math.max(0, Number(result?.deleted) || 0);
          receipt.completedCategories.push(category);
          this._save();
        } catch (_) {
          receipt.status = 'partial';
          receipt.failure = 'category_delete_failed';
          this._save();
          throw new Error(`privacy deletion incomplete; retry request ${id}`);
        }
      }
      receipt.status = 'completed';
      receipt.completedAt = new Date(this.now()).toISOString();
      delete receipt.failure;
      this._save();
      return clone(receipt);
    } finally {
      for (const release of releases.reverse()) {
        try { release(); } catch (_) {}
      }
    }
  }
}

module.exports = { PrivacyCenter, encryptArchive, decryptArchive, encryptBytes, decryptBytes, CONFIRMATION, FORMAT, FORMAT_VERSION };
