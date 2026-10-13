'use strict';

// Requests own cancellation; views own rendering. Only idempotent reads share work.
class WebRequestCoordinator {
  constructor() { this.pending = new Map(); this.requests = 0; this.coalesced = 0; }
  run(key, operation) {
    if (this.pending.has(key)) { this.coalesced++; return this.pending.get(key).promise; }
    const controller = new AbortController();
    this.requests++;
    const entry = { controller, promise: null };
    entry.promise = Promise.resolve().then(() => operation(controller.signal)).then(result => {
      if (controller.signal.aborted) { const error = new Error('Request cancelled'); error.name = 'AbortError'; throw error; }
      return result;
    }).finally(() => { if (this.pending.get(key) === entry) this.pending.delete(key); });
    this.pending.set(key, entry);
    return entry.promise;
  }
  abortReads() { for (const entry of this.pending.values()) entry.controller.abort(); this.pending.clear(); }
}

class WebRefreshScheduler {
  constructor() {
    this.state = { visible: true, drawerOpen: false, framesVisible: false, pushOnline: false };
    this.jobs = new Map(); this.errors = 0;
  }
  update(patch) { Object.assign(this.state, patch); }
  register(name, operation, mode = 'stage') { this.jobs.set(name, { operation, mode, busy: false }); }
  async tick(name) {
    const job = this.jobs.get(name), state = this.state;
    if (!job || job.busy || !state.visible) return;
    if (job.mode === 'stage' && state.pushOnline) return;
    if (job.mode === 'drawer' && (!state.drawerOpen || state.pushOnline)) return;
    if (job.mode === 'frames' && (!state.drawerOpen || !state.framesVisible)) return;
    job.busy = true;
    try { await job.operation(); } catch (_) { this.errors++; } finally { job.busy = false; }
  }
  async tickAll() { await Promise.all([...this.jobs.keys()].map(name => this.tick(name))); }
}

function browserRuntimeScript() {
  return `window.WebRequestCoordinator=${WebRequestCoordinator.toString()};window.WebRefreshScheduler=${WebRefreshScheduler.toString()};`;
}
module.exports = { WebRequestCoordinator, WebRefreshScheduler, browserRuntimeScript };
