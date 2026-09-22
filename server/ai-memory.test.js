const test = require('node:test');
const assert = require('node:assert/strict');
const { MemoryStore } = require('./ai-memory');

test('memory store supports idempotent add, retrieval, edit and removal', () => {
  const saved = {};
  const persistence = { load: () => saved.value || null, save: (_, value) => { saved.value = JSON.parse(JSON.stringify(value)); } };
  const store = new MemoryStore({ persistence, now: () => 1700000000000 });
  const first = store.add({ id: 'm1', text: '用户喜欢安静的夜间提醒', source: 'user' });
  assert.equal(first.duplicate, false);
  assert.equal(store.add({ id: 'm1', text: '重复' }).duplicate, true);
  assert.equal(store.list({ query: '夜间' })[0].id, 'm1');
  assert.equal(store.update('m1', { sensitivity: 'sensitive' }).entry.sensitivity, 'sensitive');
  assert.equal(store.export().length, 0);
  assert.equal(store.remove('m1').removed, true);
  assert.equal(store.snapshot().count, 0);
});

test('memory persistence restores only valid entries and caps input', () => {
  const store = new MemoryStore({ persistence: { load: () => ({ entries: [{ id: 'ok', text: 'valid' }, { nope: true }], revision: 2 }) } });
  assert.equal(store.snapshot().count, 1);
  assert.equal(store.add({ text: 'x'.repeat(4000) }).entry.text.length, 2000);
});

test('memory store separates auto candidates from confirmed memories', () => {
  const store = new MemoryStore({ now: () => 1700000000000 });
  const candidate = store.add({ id: 'candidate-1', text: '可能喜欢低亮度', source: 'auto_extract', status: 'candidate' });
  const confirmed = store.add({ id: 'confirmed-1', text: '明确喜欢安静', source: 'user', explicit: true });
  assert.equal(candidate.entry.status, 'candidate');
  assert.equal(confirmed.entry.status, 'confirmed');
  assert.equal(store.list({ status: 'candidate' }).length, 1);
  assert.equal(store.list({ status: 'confirmed' }).length, 1);
  assert.equal(store.confirm('candidate-1').entry.status, 'confirmed');
  assert.equal(store.snapshot().candidateCount, 0);
  assert.equal(store.snapshot().confirmedCount, 2);
});

test('memory store supports a per-request no-memory selection without changing saved entries', () => {
  const store = new MemoryStore({ now: () => 1700000000000 });
  store.add({ id: 'm1', text: '长期偏好', source: 'user' });
  assert.deepEqual(store.selectForConversation({ remember: false, query: '偏好' }), []);
  assert.equal(store.selectForConversation({ remember: true, query: '偏好' })[0].id, 'm1');
});
