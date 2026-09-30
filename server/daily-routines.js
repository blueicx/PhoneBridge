'use strict';

const crypto = require('node:crypto');

const STATE_VERSION = 1;
const MAX_REFLECTION_CODE_POINTS = 1000;
const MAX_ELAPSED_SECONDS = 24 * 60 * 60;
const MAX_HISTORY_LIMIT = 100;
const MAX_FUTURE_SKEW_MS = 5 * 60 * 1000;
const ALLOWED_ACTIONS = new Set(['start', 'pause', 'resume', 'skip', 'finish', 'interrupt']);
const TERMINAL_STATUSES = new Set(['finished', 'skipped', 'interrupted']);
const ALL_STATUSES = new Set(['active', 'paused', ...TERMINAL_STATUSES]);

const ACTIVITY_CATALOG = Object.freeze([
  Object.freeze({ id: 'focus-timer', title: '专注计时', description: '按自己的节奏专注；可随时暂停或结束。', kind: 'focus', permissionRequired: false }),
  Object.freeze({ id: 'walk-observation', title: '散步观察', description: '无需定位或相机，留意身边值得记录的一件小事。', kind: 'observation', permissionRequired: false }),
  Object.freeze({ id: 'bedtime-review', title: '睡前回顾', description: '可选的纯文本回顾，不会自动写入长期记忆。', kind: 'reflection', input: 'text', permissionRequired: false }),
]);

const CATALOG_BY_ID = new Map(ACTIVITY_CATALOG.map(activity => [activity.id, activity]));

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }

class RoutineError extends Error {
  constructor(code, message, statusCode = 400) {
    super(message);
    this.name = 'RoutineError';
    this.code = code;
    this.statusCode = statusCode;
  }
}

function fail(code, message, statusCode = 400) {
  throw new RoutineError(code, message, statusCode);
}

function normalizeOccurredAt(value, now) {
  if (typeof value !== 'string' && typeof value !== 'number') fail('invalid_time', 'occurredAt must be a timestamp');
  if (typeof value === 'string' && !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) {
    fail('invalid_time', 'occurredAt must be an ISO-8601 timestamp or epoch milliseconds');
  }
  const date = new Date(value);
  const at = date.getTime();
  if (!Number.isFinite(at)) fail('invalid_time', 'occurredAt is invalid');
  if (at > now + MAX_FUTURE_SKEW_MS) fail('future_time', 'occurredAt is too far in the future');
  return date.toISOString();
}

function normalizeEvent(routineId, payload, now) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) fail('invalid_event', 'event body must be an object');
  if (typeof payload.eventId !== 'string') fail('invalid_event_id', 'eventId must be text');
  const eventId = payload.eventId.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(eventId)) fail('invalid_event_id', 'eventId must contain 1-128 safe characters');
  if (typeof payload.action !== 'string') fail('invalid_action', 'action must be text');
  const action = payload.action.trim().toLowerCase();
  if (!ALLOWED_ACTIONS.has(action)) fail('invalid_action', 'action must be start, pause, resume, skip, finish, or interrupt');
  const occurredAt = normalizeOccurredAt(payload.occurredAt, now);
  const normalized = { eventId, routineId, action, occurredAt };

  if (Object.hasOwn(payload, 'elapsedSeconds')) {
    const elapsedSeconds = payload.elapsedSeconds;
    if (!Number.isSafeInteger(elapsedSeconds) || elapsedSeconds < 0 || elapsedSeconds > MAX_ELAPSED_SECONDS) {
      fail('invalid_elapsed_time', `elapsedSeconds must be an integer from 0 to ${MAX_ELAPSED_SECONDS}`);
    }
    normalized.elapsedSeconds = elapsedSeconds;
  }

  if (Object.hasOwn(payload, 'reflection')) {
    if (routineId !== 'bedtime-review' || action !== 'finish') {
      fail('reflection_not_allowed', 'reflection is only accepted when finishing a bedtime review');
    }
    if (typeof payload.reflection !== 'string') fail('invalid_reflection', 'reflection must be text');
    if (Array.from(payload.reflection).length > MAX_REFLECTION_CODE_POINTS) {
      fail('reflection_too_long', `reflection must not exceed ${MAX_REFLECTION_CODE_POINTS} characters`);
    }
    normalized.reflection = payload.reflection;
  }

  return normalized;
}

function initialState() {
  return { version: STATE_VERSION, revision: 0, entries: [], receipts: {} };
}

function storedTime(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return null;
  const time = new Date(value).getTime();
  return Number.isFinite(time) ? new Date(time).toISOString() : null;
}

function normalizeSavedEntry(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const id = String(value.id || '');
  const routineId = String(value.routineId || '');
  const status = String(value.status || '');
  const startedAt = storedTime(value.startedAt);
  const updatedAt = storedTime(value.updatedAt);
  if (!/^[A-Za-z0-9_:-]{1,128}$/.test(id) || !CATALOG_BY_ID.has(routineId) || !ALL_STATUSES.has(status) || !startedAt || !updatedAt) return null;
  const elapsedSeconds = Number(value.elapsedSeconds);
  if (!Number.isSafeInteger(elapsedSeconds) || elapsedSeconds < 0 || elapsedSeconds > MAX_ELAPSED_SECONDS) return null;
  const entry = {
    id,
    routineId,
    title: CATALOG_BY_ID.get(routineId).title,
    status,
    startedAt,
    updatedAt,
    lastEventAt: storedTime(value.lastEventAt) || updatedAt,
    elapsedSeconds,
    revision: Number.isSafeInteger(Number(value.revision)) && Number(value.revision) >= 0 ? Number(value.revision) : 0,
  };
  if (TERMINAL_STATUSES.has(status)) entry.endedAt = storedTime(value.endedAt) || updatedAt;
  if (status === 'finished' && routineId === 'bedtime-review' && typeof value.reflection === 'string' && Array.from(value.reflection).length <= MAX_REFLECTION_CODE_POINTS) {
    entry.reflection = value.reflection;
  }
  return entry;
}

function normalizeSavedReceipts(value, entryIds) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const receipts = {};
  for (const [eventId, receipt] of Object.entries(value)) {
    const occurredAt = storedTime(receipt?.occurredAt);
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(eventId)
      || !receipt || !/^[a-f0-9]{64}$/i.test(String(receipt.fingerprint || ''))
      || typeof receipt.entryId !== 'string' || !entryIds.has(receipt.entryId)
      || !ALLOWED_ACTIONS.has(String(receipt.action || '')) || !occurredAt) continue;
    receipts[eventId] = {
      fingerprint: String(receipt.fingerprint).toLowerCase(),
      entryId: receipt.entryId,
      action: String(receipt.action),
      occurredAt,
    };
  }
  return receipts;
}

class DailyRoutinesStore {
  constructor({ persistence = null, now = () => Date.now() } = {}) {
    this.persistence = persistence;
    this.now = now;
    this.state = initialState();
    this._load();
  }

  _load() {
    const saved = this.persistence?.load?.('daily-routines', null);
    if (!saved || typeof saved !== 'object') return;
    const entries = Array.isArray(saved.entries)
      ? saved.entries.map(normalizeSavedEntry).filter(Boolean)
      : [];
    const entryIds = new Set(entries.map(entry => entry.id));
    this.state = {
      version: STATE_VERSION,
      revision: Math.max(0, Number.isSafeInteger(Number(saved.revision)) ? Number(saved.revision) : 0),
      entries,
      receipts: normalizeSavedReceipts(saved.receipts, entryIds),
    };
  }

  _commit(next) {
    next.version = STATE_VERSION;
    this.persistence?.save?.('daily-routines', clone(next));
    this.state = next;
  }

  snapshot() { return clone(this.state); }

  list({ cursor = 0, limit = 20 } = {}) {
    const offset = Number(cursor);
    const pageSize = Number(limit);
    if (!Number.isSafeInteger(offset) || offset < 0) fail('invalid_cursor', 'cursor must be a non-negative integer');
    if (!Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > MAX_HISTORY_LIMIT) {
      fail('invalid_limit', `limit must be an integer from 1 to ${MAX_HISTORY_LIMIT}`);
    }
    const entries = this.state.entries;
    const current = entries.filter(entry => !TERMINAL_STATUSES.has(entry.status))
      .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
      .map(clone);
    const historyItems = entries.filter(entry => TERMINAL_STATUSES.has(entry.status))
      .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
    const history = historyItems.slice(offset, offset + pageSize).map(clone);
    return {
      catalog: clone(ACTIVITY_CATALOG),
      current,
      history,
      nextCursor: offset + history.length < historyItems.length ? offset + history.length : null,
      revision: this.state.revision,
    };
  }

  recordEvent(routineIdValue, payload) {
    const routineId = String(routineIdValue || '').trim();
    if (!CATALOG_BY_ID.has(routineId)) fail('routine_not_found', 'routine was not found', 404);
    const normalized = normalizeEvent(routineId, payload, Number(this.now()));
    const fingerprint = crypto.createHash('sha256').update(JSON.stringify(normalized)).digest('hex');
    const existingReceipt = this.state.receipts[normalized.eventId];
    if (existingReceipt) {
      if (existingReceipt.fingerprint !== fingerprint) fail('event_id_reused', 'eventId is already bound to a different event', 409);
      const entry = this.state.entries.find(item => item.id === existingReceipt.entryId);
      if (!entry) fail('event_receipt_orphaned', 'event receipt has no activity record', 409);
      return { entry: clone(entry), action: normalized.action, duplicate: true, revision: this.state.revision };
    }

    const next = clone(this.state);
    const currentIndex = next.entries.findIndex(entry => entry.routineId === routineId && !TERMINAL_STATUSES.has(entry.status));
    let entry;
    if (normalized.action === 'start') {
      if (currentIndex >= 0) fail('routine_already_active', 'an unfinished activity of this type already exists', 409);
      if (normalized.elapsedSeconds !== undefined && normalized.elapsedSeconds !== 0) {
        fail('invalid_elapsed_time', 'elapsedSeconds must be zero when an activity starts');
      }
      entry = {
        id: `routine_${crypto.createHash('sha256').update(normalized.eventId).digest('hex').slice(0, 24)}`,
        routineId,
        title: CATALOG_BY_ID.get(routineId).title,
        status: 'active',
        startedAt: normalized.occurredAt,
        updatedAt: normalized.occurredAt,
        lastEventAt: normalized.occurredAt,
        elapsedSeconds: 0,
      };
      next.entries.push(entry);
      next.revision += 1;
      entry.revision = next.revision;
      next.receipts[normalized.eventId] = { fingerprint, entryId: entry.id, action: normalized.action, occurredAt: normalized.occurredAt };
      this._commit(next);
      return { entry: clone(entry), action: normalized.action, duplicate: false, revision: this.state.revision };
    }

    if (currentIndex < 0) fail('invalid_transition', 'action requires an unfinished activity', 409);
    entry = next.entries[currentIndex];
    const previousAt = Date.parse(entry.lastEventAt || entry.updatedAt || entry.startedAt);
    const at = Date.parse(normalized.occurredAt);
    if (at <= previousAt) fail('out_of_order_event', 'occurredAt must be later than the previous activity event', 409);
    if (normalized.elapsedSeconds !== undefined && normalized.elapsedSeconds < Number(entry.elapsedSeconds || 0)) {
      fail('elapsed_time_decreased', 'elapsedSeconds cannot decrease', 409);
    }
    const valid = {
      pause: entry.status === 'active',
      resume: entry.status === 'paused',
      skip: entry.status === 'active' || entry.status === 'paused',
      finish: entry.status === 'active' || entry.status === 'paused',
      interrupt: entry.status === 'active' || entry.status === 'paused',
    };
    if (!valid[normalized.action]) fail('invalid_transition', `cannot ${normalized.action} an activity in ${entry.status} state`, 409);

    if (normalized.elapsedSeconds !== undefined) entry.elapsedSeconds = normalized.elapsedSeconds;
    entry.status = normalized.action === 'pause' ? 'paused'
      : normalized.action === 'resume' ? 'active'
        : normalized.action === 'finish' ? 'finished'
          : normalized.action === 'skip' ? 'skipped'
            : normalized.action === 'interrupt' ? 'interrupted'
              : entry.status;
    entry.lastEventAt = normalized.occurredAt;
    entry.updatedAt = normalized.occurredAt;
    if (TERMINAL_STATUSES.has(entry.status)) entry.endedAt = normalized.occurredAt;
    if (normalized.action === 'finish' && normalized.reflection !== undefined) entry.reflection = normalized.reflection;

    next.revision += 1;
    entry.revision = next.revision;
    next.receipts[normalized.eventId] = { fingerprint, entryId: entry.id, action: normalized.action, occurredAt: normalized.occurredAt };
    this._commit(next);
    return { entry: clone(entry), action: normalized.action, duplicate: false, revision: this.state.revision };
  }

  count() { return this.state.entries.length; }
  export() { return this.snapshot(); }

  clear() {
    const deleted = this.state.entries.length;
    const next = initialState();
    next.revision = this.state.revision + 1;
    this._commit(next);
    return { deleted };
  }
}

module.exports = { ACTIVITY_CATALOG, DailyRoutinesStore, RoutineError, MAX_REFLECTION_CODE_POINTS };
