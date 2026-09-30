'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { DailyRoutinesStore, ACTIVITY_CATALOG } = require('./daily-routines');
const { RuntimePersistence } = require('./runtime-persistence');

function makeStore(overrides = {}) {
  const persistence = overrides.persistence || {
    value: null,
    load(name, fallback) { return name === 'daily-routines' ? structuredClone(this.value ?? fallback) : fallback; },
    save(name, value) { if (name === 'daily-routines') this.value = structuredClone(value); },
  };
  return { store: new DailyRoutinesStore({ persistence, now: () => Date.parse('2026-09-30T12:00:00.000Z') }), persistence };
}

const at = seconds => new Date(Date.parse('2026-09-30T10:00:00.000Z') + seconds * 1000).toISOString();
const event = (eventId, action, seconds, extra = {}) => ({ eventId, action, occurredAt: at(seconds), ...extra });

test('catalog contains only the three fixed permission-free activities', () => {
  assert.deepEqual(ACTIVITY_CATALOG.map(activity => activity.id), ['focus-timer', 'walk-observation', 'bedtime-review']);
  assert.ok(ACTIVITY_CATALOG.every(activity => activity.permissionRequired === false));
  assert.equal(ACTIVITY_CATALOG.find(activity => activity.id === 'bedtime-review').input, 'text');
});

test('create, pause, resume, finish, skip, and interrupt follow the activity state machine', () => {
  const { store } = makeStore();
  const started = store.recordEvent('focus-timer', event('evt-focus-start', 'start', 0));
  assert.equal(started.entry.status, 'active');
  assert.equal(started.entry.elapsedSeconds, 0);
  assert.equal(store.recordEvent('focus-timer', event('evt-focus-pause', 'pause', 10, { elapsedSeconds: 10 })).entry.status, 'paused');
  assert.equal(store.recordEvent('focus-timer', event('evt-focus-resume', 'resume', 20, { elapsedSeconds: 10 })).entry.status, 'active');
  assert.equal(store.recordEvent('focus-timer', event('evt-focus-finish', 'finish', 30, { elapsedSeconds: 20 })).entry.status, 'finished');

  store.recordEvent('walk-observation', event('evt-walk-start', 'start', 40));
  assert.equal(store.recordEvent('walk-observation', event('evt-walk-skip', 'skip', 45)).entry.status, 'skipped');
  store.recordEvent('bedtime-review', event('evt-bed-start', 'start', 50));
  const review = store.recordEvent('bedtime-review', event('evt-bed-finish', 'finish', 60, { reflection: '今天完成了一件重要的小事。' }));
  assert.equal(review.entry.status, 'finished');
  assert.equal(review.entry.reflection, '今天完成了一件重要的小事。');

  store.recordEvent('focus-timer', event('evt-focus-start-2', 'start', 70));
  assert.equal(store.recordEvent('focus-timer', event('evt-focus-interrupt', 'interrupt', 71)).entry.status, 'interrupted');
  assert.deepEqual(store.list().current, []);
});

test('eventId is idempotent across replay and process restart, but cannot be rebound', () => {
  const { store, persistence } = makeStore();
  const start = event('evt-restart-start', 'start', 0);
  const first = store.recordEvent('focus-timer', start);
  const replay = store.recordEvent('focus-timer', start);
  assert.equal(replay.duplicate, true);
  assert.equal(replay.entry.id, first.entry.id);
  assert.equal(store.snapshot().revision, 1);

  const restored = new DailyRoutinesStore({ persistence, now: () => Date.parse('2026-09-30T12:00:00.000Z') });
  const afterRestart = restored.recordEvent('focus-timer', start);
  assert.equal(afterRestart.duplicate, true);
  assert.equal(afterRestart.entry.id, first.entry.id);
  assert.equal(restored.list().current[0].status, 'active');
  assert.throws(() => restored.recordEvent('focus-timer', event('evt-restart-start', 'pause', 1)), /eventId.*reuse|eventId.*different/i);
});

test('runtime snapshot restores an unfinished activity and its event receipt from disk', () => {
  const runtimeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-daily-routines-'));
  try {
    const now = () => Date.parse('2026-09-30T12:00:00.000Z');
    const first = new DailyRoutinesStore({ persistence: new RuntimePersistence({ dir: runtimeDir }), now });
    const start = event('evt-disk-restart', 'start', 0);
    const created = first.recordEvent('walk-observation', start);

    const restored = new DailyRoutinesStore({ persistence: new RuntimePersistence({ dir: runtimeDir }), now });
    const replay = restored.recordEvent('walk-observation', start);
    assert.equal(replay.duplicate, true);
    assert.equal(replay.entry.id, created.entry.id);
    assert.equal(restored.list().current[0].status, 'active');
  } finally {
    fs.rmSync(runtimeDir, { recursive: true, force: true });
  }
});

test('restart loading keeps only the routine schema and drops unknown persisted fields', () => {
  const persistence = {
    load() {
      return {
        version: 1,
        revision: 1,
        entries: [{
          id: 'routine_safe-id', routineId: 'focus-timer', title: 'untrusted title', status: 'active',
          startedAt: at(0), updatedAt: at(0), elapsedSeconds: 0, revision: 1,
          apiKey: 'must not survive normalization',
        }],
        receipts: {
          'evt-safe': {
            fingerprint: 'a'.repeat(64), entryId: 'routine_safe-id', action: 'start', occurredAt: at(0),
            prompt: 'untrusted extra data must not be exported',
          },
          'evt-orphan': { fingerprint: 'b'.repeat(64), entryId: 'missing-entry', action: 'start', occurredAt: at(0) },
        },
      };
    },
    save() {},
  };
  const store = new DailyRoutinesStore({ persistence, now: () => Date.parse('2026-09-30T12:00:00.000Z') });
  const snapshot = store.export();
  assert.equal(snapshot.entries[0].title, '专注计时');
  assert.equal(Object.hasOwn(snapshot.entries[0], 'apiKey'), false);
  assert.deepEqual(Object.keys(snapshot.receipts['evt-safe']).sort(), ['action', 'entryId', 'fingerprint', 'occurredAt']);
  assert.equal(Object.hasOwn(snapshot.receipts['evt-safe'], 'prompt'), false);
  assert.equal(Object.hasOwn(snapshot.receipts, 'evt-orphan'), false);
});

test('rejects unknown actions, illegal transitions, duplicate active starts, and out-of-order timestamps', () => {
  const { store } = makeStore();
  assert.throws(() => store.recordEvent('focus-timer', event('evt-bad-pause', 'pause', 0)), /unfinished activity|transition|state/i);
  assert.throws(() => store.recordEvent('focus-timer', event('evt-bad-action', 'check-in', 0)), /action/i);
  store.recordEvent('focus-timer', event('evt-active-start', 'start', 10));
  assert.throws(() => store.recordEvent('focus-timer', event('evt-second-start', 'start', 11)), /unfinished|active/i);
  assert.throws(() => store.recordEvent('focus-timer', event('evt-time-reversal', 'pause', 9)), /later|chronolog|order/i);
  store.recordEvent('focus-timer', event('evt-active-pause', 'pause', 12));
  assert.throws(() => store.recordEvent('focus-timer', event('evt-double-pause', 'pause', 13)), /transition|state/i);
});

test('validates elapsed time and limits user reflection to bedtime completion', () => {
  const { store } = makeStore();
  assert.throws(() => store.recordEvent('focus-timer', event('evt-start-invalid-elapsed', 'start', 0, { elapsedSeconds: -1 })), /elapsed/i);
  assert.throws(() => store.recordEvent('focus-timer', { eventId: 'evt-string-elapsed', action: 'start', occurredAt: at(0), elapsedSeconds: '0' }), /elapsed/i);
  assert.throws(() => store.recordEvent('focus-timer', { eventId: 7, action: 'start', occurredAt: at(0) }), /eventId/i);
  assert.throws(() => store.recordEvent('focus-timer', { eventId: 'evt-ambiguous-time', action: 'start', occurredAt: '1' }), /ISO-8601/i);
  assert.throws(() => store.recordEvent('focus-timer', { eventId: 'evt-future-time', action: 'start', occurredAt: '2026-09-30T12:06:00.000Z' }), /future/i);
  store.recordEvent('focus-timer', event('evt-focus-start-for-reflection', 'start', 1));
  assert.throws(() => store.recordEvent('focus-timer', event('evt-focus-reflection', 'finish', 2, { reflection: 'not a bedtime review' })), /reflection/i);
  store.recordEvent('bedtime-review', event('evt-bed-start-for-limit', 'start', 3));
  assert.throws(() => store.recordEvent('bedtime-review', event('evt-bed-too-long', 'finish', 4, { reflection: '🧠'.repeat(1001) })), /1000|length/i);
  const result = store.recordEvent('bedtime-review', event('evt-bed-boundary', 'finish', 5, { reflection: '🧠'.repeat(1000) }));
  assert.equal(Array.from(result.entry.reflection).length, 1000);
});

test('history pagination, privacy export/count/clear, and persistence failures are safe', () => {
  const { store, persistence } = makeStore();
  store.recordEvent('focus-timer', event('evt-page-focus', 'start', 0));
  store.recordEvent('focus-timer', event('evt-page-focus-finish', 'finish', 1));
  store.recordEvent('walk-observation', event('evt-page-walk', 'start', 2));
  store.recordEvent('walk-observation', event('evt-page-walk-skip', 'skip', 3));
  const firstPage = store.list({ cursor: 0, limit: 1 });
  assert.equal(firstPage.history.length, 1);
  assert.equal(firstPage.nextCursor, 1);
  assert.equal(store.list({ cursor: firstPage.nextCursor, limit: 1 }).history.length, 1);
  assert.equal(store.count(), 2);
  assert.equal(store.export().entries.length, 2);
  assert.equal(store.clear().deleted, 2);
  assert.equal(store.count(), 0);

  const before = store.snapshot();
  const save = persistence.save;
  persistence.save = () => { throw new Error('disk unavailable'); };
  assert.throws(() => store.recordEvent('focus-timer', event('evt-save-failure', 'start', 4)), /disk unavailable/);
  assert.deepEqual(store.snapshot(), before);
  persistence.save = save;
});
