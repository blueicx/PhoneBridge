'use strict';

class ActiveRequestRegistry {
  constructor() {
    this.requests = new Map();
    this.pauseCount = 0;
  }

  begin(id, cancel) {
    const key = String(id || '').trim();
    if (this.pauseCount > 0) throw new Error('new chat requests are temporarily paused');
    if (!key || this.requests.has(key)) throw new Error('active request id is missing or already in use');
    let resolve;
    const done = new Promise((finish) => { resolve = finish; });
    const entry = { cancel: typeof cancel === 'function' ? cancel : () => {}, done, resolve };
    this.requests.set(key, entry);
    let settled = false;
    return () => {
      if (settled) return;
      settled = true;
      if (this.requests.get(key) === entry) this.requests.delete(key);
      resolve();
    };
  }

  hasActive() {
    return this.requests.size > 0;
  }

  async cancelAndWait() {
    const current = [...this.requests.values()];
    for (const request of current) {
      try { request.cancel(); } catch (_) {}
    }
    await Promise.all(current.map((request) => request.done));
    return current.length;
  }

  async pauseCancelAndWait() {
    this.pauseCount += 1;
    await this.cancelAndWait();
    let resumed = false;
    return () => {
      if (resumed) return;
      resumed = true;
      this.pauseCount = Math.max(0, this.pauseCount - 1);
    };
  }
}

module.exports = { ActiveRequestRegistry };
