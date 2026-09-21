const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const RUNTIME_SCHEMA_VERSION = 3;
const LEGACY_SCHEMA_VERSIONS = new Set([2]);
const SECRET_KEY = /(token|secret|password|authorization|cookie|apikey|api_key|privatekey|accesskey)/i;

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value));
}

function stripSecrets(value) {
  if (Array.isArray(value)) return value.map(stripSecrets);
  if (!value || typeof value !== 'object') return value;
  const output = {};
  for (const [key, item] of Object.entries(value)) {
    if (SECRET_KEY.test(key)) continue;
    output[key] = stripSecrets(item);
  }
  return output;
}

function checksumFor(state) {
  return crypto.createHash('sha256').update(JSON.stringify(state)).digest('hex');
}

function migrateState(input) {
  const source = stripSecrets(input && typeof input === 'object' ? clone(input) : {});
  const migrated = { ...source };
  delete migrated.checksum;
  delete migrated.savedAt;
  migrated.schemaVersion = RUNTIME_SCHEMA_VERSION;
  if (!migrated.providerSettings && migrated.aiSettings) migrated.providerSettings = migrated.aiSettings;
  if (!migrated.timeline && migrated.workspaceTimeline) migrated.timeline = migrated.workspaceTimeline;
  if (!migrated.workspace) migrated.workspace = {};
  if (!migrated.mote) migrated.mote = {};
  if (!migrated.providerSettings) migrated.providerSettings = {};
  return migrated;
}

function validateEnvelope(envelope) {
  if (!envelope || typeof envelope !== 'object') return { ok: false, error: 'envelope is not an object' };
  const schemaVersion = Number(envelope.schemaVersion);
  if (schemaVersion !== RUNTIME_SCHEMA_VERSION && !LEGACY_SCHEMA_VERSIONS.has(schemaVersion)) return { ok: false, error: 'unsupported schema version' };
  if (!envelope.state || typeof envelope.state !== 'object') return { ok: false, error: 'state is missing' };
  if (!/^[a-f0-9]{64}$/i.test(String(envelope.checksum || ''))) return { ok: false, error: 'checksum is missing' };
  if (checksumFor(envelope.state) !== envelope.checksum) return { ok: false, error: 'checksum mismatch' };
  return { ok: true, migrated: schemaVersion !== RUNTIME_SCHEMA_VERSION };
}

class RuntimePersistence {
  constructor({ dir, maxBackups = 3, now = () => Date.now(), onRecovery = () => {} } = {}) {
    this.dir = path.resolve(dir || process.cwd());
    this.maxBackups = Math.max(1, Math.min(10, Number(maxBackups) || 3));
    this.now = now;
    this.onRecovery = onRecovery;
    this.recoveryCount = 0;
    this.lastRecovery = null;
    fs.mkdirSync(this.dir, { recursive: true });
    if (process.platform !== 'win32') {
      try { fs.chmodSync(this.dir, 0o700); } catch (_) {}
    }
  }

  filePath(name) {
    if (!/^[a-zA-Z0-9._-]+$/.test(String(name))) throw new Error('invalid persistence name');
    return path.join(this.dir, `${name}.json`);
  }

  _backupPath(name, index) {
    return `${this.filePath(name)}.bak.${index}`;
  }

  _writeEnvelope(name, envelope, { backup = true } = {}) {
    const file = this.filePath(name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    if (backup && fs.existsSync(file)) {
      for (let index = this.maxBackups; index >= 2; index--) {
        const older = this._backupPath(name, index - 1);
        const target = this._backupPath(name, index);
        if (fs.existsSync(target)) fs.rmSync(target, { force: true });
        if (fs.existsSync(older)) fs.renameSync(older, target);
      }
      fs.renameSync(file, this._backupPath(name, 1));
    }
    const temporary = `${file}.${process.pid}.${crypto.randomUUID()}.tmp`;
    fs.writeFileSync(temporary, `${JSON.stringify(envelope, null, 2)}\n`, { mode: 0o600 });
    try {
      fs.renameSync(temporary, file);
      try { fs.chmodSync(file, 0o600); } catch (_) {}
    } finally {
      try { fs.rmSync(temporary, { force: true }); } catch (_) {}
    }
  }

  save(name, state) {
    const migrated = migrateState(state);
    const envelope = {
      schemaVersion: RUNTIME_SCHEMA_VERSION,
      savedAt: new Date(this.now()).toISOString(),
      checksum: checksumFor(migrated),
      state: migrated,
    };
    this._writeEnvelope(name, envelope);
    return clone(migrated);
  }

  _quarantine(file) {
    if (!fs.existsSync(file)) return null;
    const quarantined = `${file}.corrupt-${this.now()}-${crypto.randomUUID()}`;
    try { fs.renameSync(file, quarantined); return quarantined; } catch (_) { return null; }
  }

  _readFile(file) {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  }

  loadWithMeta(name, defaults = {}) {
    const file = this.filePath(name);
    let quarantinedPath = null;
    let migrated = false;
    let recovered = false;
    const candidates = [file, ...Array.from({ length: this.maxBackups }, (_, i) => this._backupPath(name, i + 1))];
    for (const candidate of candidates) {
      if (!fs.existsSync(candidate)) continue;
      try {
        const parsed = this._readFile(candidate);
        const validation = validateEnvelope(parsed);
        if (validation.ok) {
          const state = validation.migrated ? migrateState(parsed.state) : clone(parsed.state);
          const normalizedEnvelope = validation.migrated
            ? { schemaVersion: RUNTIME_SCHEMA_VERSION, savedAt: new Date(this.now()).toISOString(), checksum: checksumFor(state), state }
            : parsed;
          if (validation.migrated) {
            migrated = true;
            this._writeEnvelope(name, normalizedEnvelope, { backup: false });
          }
          if (candidate !== file) {
            recovered = true;
            this._writeEnvelope(name, normalizedEnvelope, { backup: false });
            this.recoveryCount += 1;
            this.lastRecovery = { name, source: candidate, recoveredAt: new Date(this.now()).toISOString() };
            this.onRecovery(this.lastRecovery);
          }
          return { state, migrated, recovered, quarantinedPath };
        }
        if (candidate === file) {
          const looksLikeEnvelope = Object.prototype.hasOwnProperty.call(parsed, 'checksum') ||
            (Object.prototype.hasOwnProperty.call(parsed, 'state') && Object.prototype.hasOwnProperty.call(parsed, 'savedAt'));
          if (looksLikeEnvelope) {
            quarantinedPath = this._quarantine(file);
            continue;
          }
          const raw = migrateState(parsed.state || parsed);
          migrated = true;
          this._writeEnvelope(name, { schemaVersion: RUNTIME_SCHEMA_VERSION, savedAt: new Date(this.now()).toISOString(), checksum: checksumFor(raw), state: raw }, { backup: false });
          return { state: raw, migrated, recovered, quarantinedPath };
        }
      } catch (error) {
        if (candidate === file) quarantinedPath = this._quarantine(file);
      }
    }
    if (quarantinedPath) {
      for (const backup of candidates.slice(1)) {
        if (!fs.existsSync(backup)) continue;
        try {
          const parsed = this._readFile(backup);
          if (!validateEnvelope(parsed).ok) continue;
          recovered = true;
          this._writeEnvelope(name, parsed, { backup: false });
          this.recoveryCount += 1;
          this.lastRecovery = { name, quarantinedPath, source: backup, recoveredAt: new Date(this.now()).toISOString() };
          this.onRecovery(this.lastRecovery);
          return { state: clone(parsed.state), migrated, recovered, quarantinedPath };
        } catch (_) {}
      }
    }
    return { state: migrateState(defaults), migrated, recovered, quarantinedPath };
  }

  load(name, defaults = {}) {
    return this.loadWithMeta(name, defaults).state;
  }

  snapshot() {
    return { schemaVersion: RUNTIME_SCHEMA_VERSION, recoveryCount: this.recoveryCount, lastRecovery: this.lastRecovery };
  }
}

module.exports = { RUNTIME_SCHEMA_VERSION, RuntimePersistence, migrateState, validateEnvelope, checksumFor, stripSecrets };
