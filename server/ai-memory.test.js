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
