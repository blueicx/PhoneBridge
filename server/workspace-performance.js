class RevisionSnapshotCache {
  constructor({ getRevision, build, buildFull, buildSummary } = {}) {
    if (typeof getRevision !== 'function') throw new TypeError('getRevision must be a function');
    const fullBuilder = buildFull || build;
    if (typeof fullBuilder !== 'function') throw new TypeError('build or buildFull must be a function');
    this.getRevision = getRevision;
    this.build = fullBuilder;
    this.buildSummary = typeof buildSummary === 'function' ? buildSummary : null;
    this.revision = null;
    this.values = null;
    this.generationCounted = false;
    this.builds = 0;
    this.hits = 0;
    this.viewBuilds = { full: 0, summary: 0 };
  }
  get(view = 'full') {
    const revision = this.getRevision();
    if (this.values === null || this.revision !== revision) {
      this.values = {};
      this.revision = revision;
      this.generationCounted = false;
    }
    const requestedView = view === 'summary' ? 'summary' : 'full';
    if (this.values[requestedView] === undefined) {
      if (requestedView === 'summary' && this.buildSummary) {
        this.values.summary = this.buildSummary(revision) ?? '';
      } else {
        const values = this.build(revision) || {};
        if (typeof values === 'string') this.values.full = values;
        else {
          this.values.full = values.full ?? '';
          if (!this.buildSummary) this.values.summary = values.summary ?? this.values.full;
          else if (values.summary !== undefined) this.values.summary = values.summary;
        }
      }
      this.viewBuilds[requestedView] += 1;
      if (!this.generationCounted) {
        // Keep the historical counter meaning: one cache generation per revision,
        // even when the first request only needs the lightweight summary.
        this.builds += 1;
        this.generationCounted = true;
      }
    } else this.hits += 1;
    return this.values[requestedView] ?? this.values.full ?? '';
  }
  stats() {
    const stats = { revision: this.revision, builds: this.builds, hits: this.hits };
    if (this.buildSummary) stats.viewBuilds = { ...this.viewBuilds };
    return stats;
  }
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
