const { EventEmitter } = require('node:events');

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value));
}

class TaskRunner extends EventEmitter {
  constructor({ maxConcurrency = 1, maxRetries = 1, retryDelayMs = 250, now = () => Date.now() } = {}) {
    super();
    this.maxConcurrency = Math.max(1, Number(maxConcurrency) || 1);
    this.maxRetries = Math.max(0, Number(maxRetries) || 0);
    this.retryDelayMs = Math.max(0, Number(retryDelayMs) || 0);
    this.now = now;
    this.executor = null;
    this.records = new Map();
    this.queue = [];
    this.active = new Set();
    this.scheduled = 0;
    this.idleWaiters = [];
  }

  setExecutor(executor) {
    if (typeof executor !== 'function') throw new TypeError('executor must be a function');
    this.executor = executor;
  }

  has(id) { return this.records.has(String(id)); }

  list() { return [...this.records.values()].map(record => this.get(record.id)); }

  enqueue(task) {
    const id = String(task?.id || '').trim();
    if (!id) throw new Error('task id is required');
    if (this.records.has(id)) return this.get(id);
    const record = {
      id,
      title: String(task.title || id),
      metadata: clone(task.metadata || {}),
      state: 'pending',
      progress: 0,
      attempt: 0,
      error: null,
      result: null,
      createdAt: new Date(this.now()).toISOString(),
      updatedAt: new Date(this.now()).toISOString(),
      pauseRequested: false,
      abortController: null,
      waiters: [],
    };
    this.records.set(id, record);
    this.queue.push(id);
    this._emit(record);
    this._pump();
    return this.get(id);
  }

  get(id) {
    const record = this.records.get(String(id));
    if (!record) return null;
    const { abortController, waiters, ...publicRecord } = record;
    return clone(publicRecord);
  }

  pause(id) {
    const record = this._require(id);
    if (record.state === 'pending') {
      this.queue = this.queue.filter(item => item !== record.id);
      record.state = 'paused';
    } else if (record.state === 'running') {
      record.pauseRequested = true;
      record.state = 'paused';
    }
    record.updatedAt = new Date(this.now()).toISOString();
    this._emit(record);
    return this.get(record.id);
  }

  resume(id) {
    const record = this._require(id);
    if (record.state !== 'paused') return this.get(record.id);
    if (this.active.has(record.id)) {
      record.pauseRequested = false;
      record.state = 'running';
      this._releaseWaiters(record);
    } else {
      record.state = 'pending';
      this.queue.push(record.id);
      this._pump();
    }
    record.updatedAt = new Date(this.now()).toISOString();
    this._emit(record);
    return this.get(record.id);
  }

  cancel(id) {
    const record = this._require(id);
    if (['succeeded', 'failed', 'cancelled'].includes(record.state)) return this.get(record.id);
    this.queue = this.queue.filter(item => item !== record.id);
    record.state = 'cancelled';
    record.error = 'cancelled';
    record.updatedAt = new Date(this.now()).toISOString();
    if (record.abortController) record.abortController.abort();
    this._releaseWaiters(record, new Error('cancelled'));
    this._emit(record);
    this._resolveIdle();
    return this.get(record.id);
  }

  retry(id) {
    const record = this._require(id);
    if (!['failed', 'cancelled'].includes(record.state)) throw new Error(`cannot retry task from ${record.state}`);
    record.state = 'pending';
    record.error = null;
    record.result = null;
    record.pauseRequested = false;
    record.updatedAt = new Date(this.now()).toISOString();
    this.queue.push(record.id);
    this._emit(record);
    this._pump();
    return this.get(record.id);
  }

  whenIdle() {
    if (!this._hasRunnableWork()) return Promise.resolve();
    return new Promise(resolve => this.idleWaiters.push(resolve));
  }

  _hasRunnableWork() {
    return this.active.size > 0 || this.queue.length > 0 || this.scheduled > 0;
  }

  _require(id) {
    const record = this.records.get(String(id));
    if (!record) throw new Error('task not found');
    return record;
  }

  _emit(record) {
    this.emit('state', this.get(record.id));
  }

  _releaseWaiters(record, error = null) {
    const waiters = record.waiters.splice(0);
    for (const waiter of waiters) error ? waiter.reject(error) : waiter.resolve();
  }

  _pump() {
    while (this.executor && this.active.size < this.maxConcurrency && this.queue.length) {
      const id = this.queue.shift();
      const record = this.records.get(id);
      if (!record || record.state !== 'pending') continue;
      this._start(record);
    }
    this._resolveIdle();
  }

  _start(record) {
    record.attempt += 1;
    record.state = 'running';
    record.pauseRequested = false;
    record.abortController = new AbortController();
    record.updatedAt = new Date(this.now()).toISOString();
    this.active.add(record.id);
    this._emit(record);
    const context = {
      task: this.get(record.id),
      attempt: record.attempt,
      signal: record.abortController.signal,
      report: progress => {
        record.progress = Math.max(0, Math.min(100, Number(progress) || 0));
        record.updatedAt = new Date(this.now()).toISOString();
        this._emit(record);
      },
      waitIfPaused: () => {
        if (!record.pauseRequested) return Promise.resolve();
        return new Promise((resolve, reject) => record.waiters.push({ resolve, reject }));
      },
    };
    Promise.resolve()
      .then(() => this.executor(context))
      .then(result => this._complete(record, result))
      .catch(error => this._fail(record, error))
      .finally(() => {
        record.abortController = null;
        this.active.delete(record.id);
        this._pump();
      });
  }

  _complete(record, result) {
    if (record.state === 'cancelled') return;
    record.state = 'succeeded';
    record.progress = 100;
    record.result = clone(result);
    record.error = null;
    record.updatedAt = new Date(this.now()).toISOString();
    this._releaseWaiters(record);
    this._emit(record);
  }

  _fail(record, error) {
    if (record.state === 'cancelled') return;
    record.error = String(error?.message || error || 'task failed');
    record.updatedAt = new Date(this.now()).toISOString();
    if (record.attempt <= this.maxRetries && !record.pauseRequested) {
      record.state = 'pending';
      this.scheduled += 1;
      this._emit(record);
      setTimeout(() => {
        this.scheduled -= 1;
        if (record.state === 'pending') this.queue.unshift(record.id);
        this._pump();
      }, this.retryDelayMs * Math.max(1, record.attempt));
      return;
    }
    record.state = 'failed';
    this._releaseWaiters(record, error);
    this._emit(record);
  }

  _resolveIdle() {
    if (this._hasRunnableWork()) return;
    const waiters = this.idleWaiters.splice(0);
    waiters.forEach(resolve => resolve());
  }
}

module.exports = { TaskRunner };
