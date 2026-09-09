class RevisionSnapshotCache {
  constructor({ getRevision, build } = {}) {
    if (typeof getRevision !== 'function') throw new TypeError('getRevision must be a function');
    if (typeof build !== 'function') throw new TypeError('build must be a function');
    this.getRevision = getRevision;
    this.build = build;
    this.revision = null;
    this.values = null;
    this.builds = 0;
    this.hits = 0;
  }
  get(view = 'full') {
    const revision = this.getRevision();
    if (this.values === null || this.revision !== revision) {
      const values = this.build(revision) || {};
      this.values = { full: values.full ?? '', summary: values.summary ?? values.full ?? '' };
      this.revision = revision;
      this.builds += 1;
    } else this.hits += 1;
    return this.values[view] ?? this.values.full;
  }
  stats() { return { revision: this.revision, builds: this.builds, hits: this.hits }; }
}

class BroadcastCoalescer {
  constructor({ send, delayMs = 16 } = {}) {
    if (typeof send !== 'function') throw new TypeError('send must be a function');
    this.send = send;
    this.delayMs = Math.max(0, Number(delayMs) || 0);
    this.pending = null;
    this.timer = null;
    this.waiters = [];
  }
  enqueue(payload) {
    this.pending = payload;
    if (this.timer === null) this.timer = setTimeout(() => this.flushNow(), this.delayMs);
  }
  flushNow() {
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    const payload = this.pending;
    this.pending = null;
    if (payload !== null) this.send(payload);
    this.waiters.splice(0).forEach(resolve => resolve());
  }
  flush() {
    if (this.pending === null) return Promise.resolve();
    return new Promise(resolve => { this.waiters.push(resolve); this.flushNow(); });
  }
}

class PersistenceScheduler {
  constructor({ write, delayMs = 250 } = {}) {
    if (typeof write !== 'function') throw new TypeError('write must be a function');
    this.write = write;
    this.delayMs = Math.max(0, Number(delayMs) || 0);
    this.timer = null;
    this.waiters = [];
  }
  schedule() { if (this.timer === null) this.timer = setTimeout(() => this.flushNow(), this.delayMs); }
  flushNow() {
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    this.write();
    this.waiters.splice(0).forEach(resolve => resolve());
  }
  flush() {
    if (this.timer === null) return Promise.resolve();
    return new Promise(resolve => { this.waiters.push(resolve); this.flushNow(); });
  }
}

module.exports = { RevisionSnapshotCache, BroadcastCoalescer, PersistenceScheduler };
