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

test('confirmed memories can be excluded from recall without deleting their audit details', () => {
  const store = new MemoryStore({ now: () => 1700000000000 });
  store.add({ id: 'm1', text: '喜欢夜间提醒', source: 'user' });
  const excluded = store.update('m1', { excludedFromRecall: true });
  assert.equal(excluded.entry.excludedFromRecall, true);
  assert.deepEqual(store.selectForConversation({ remember: true, query: '提醒' }), []);
  assert.equal(store.list()[0].source, 'user');
  assert.equal(store.update('m1', { excludedFromRecall: false }).entry.excludedFromRecall, false);
  assert.equal(store.selectForConversation({ remember: true, query: '提醒' })[0].id, 'm1');
});

test('conversation recall matches relevant Chinese phrases without requiring whitespace tokenization', () => {
  const store = new MemoryStore({ now: () => 1700000000000 });
  store.add({ id: 'quiet-memory', text: '我喜欢安静的夜间提醒', source: 'user', explicit: true });
  store.add({ id: 'walk-memory', text: '周末喜欢户外散步', source: 'user', explicit: true });

  assert.deepEqual(store.selectForConversation({ query: '我想聊聊安静的夜间提醒', limit: 5 }).map(item => item.id), ['quiet-memory']);
});

test('memory store flags only likely opposite-preference conflicts for user review', () => {
  storeMemoryForConflictTest();
});

function storeMemoryForConflictTest() {
  const store = new MemoryStore({ now: () => 1700000000000 });
  store.add({ id: 'quiet-positive', text: '我喜欢安静的夜间提醒', source: 'user', explicit: true });
  const candidate = store.add({ id: 'quiet-negative', text: '我不喜欢安静的夜间提醒', source: 'auto_extract', status: 'candidate' });
  store.add({ id: 'coffee', text: '我喜欢喝咖啡', source: 'user', explicit: true });

  assert.deepEqual(candidate.conflicts.map(item => item.id), ['quiet-positive']);
  assert.deepEqual(store.findPotentialConflicts('我不喜欢安静的夜间提醒').map(item => item.id), ['quiet-positive']);
  assert.deepEqual(store.findPotentialConflicts('我喜欢早上散步'), []);
}

test('memory candidates can be batch-confirmed with a single revision and persistence write', () => {
  let writes = 0;
  const store = new MemoryStore({
    persistence: { load: () => null, save: () => { writes += 1; } },
    now: () => 1700000000000
  });
  store.add({ id: 'candidate-a', text: '周末喜欢散步', source: 'auto_extract', status: 'candidate' });
  store.add({ id: 'candidate-b', text: '偏好低亮度', source: 'auto_extract', status: 'candidate' });
  const startingRevision = store.snapshot().revision;
  const startingWrites = writes;

  const result = store.confirmMany(['candidate-a', 'candidate-b', 'missing']);

  assert.deepEqual(result.confirmed.map(item => item.id), ['candidate-a', 'candidate-b']);
  assert.equal(result.revision, startingRevision + 1);
  assert.equal(writes, startingWrites + 1);
  assert.equal(store.snapshot().candidateCount, 0);
});

test('candidate merge preserves provenance, confirms the selected text and leaves unrelated confirmed memories intact', () => {
  const store = new MemoryStore({ now: () => 1700000000000 });
  store.add({ id: 'confirmed', text: '喜欢安静提醒', source: 'user', explicit: true });
  store.add({ id: 'candidate-a', text: '夜间提醒希望低亮度', source: 'auto_extract', status: 'candidate' });
  store.add({ id: 'candidate-b', text: '晚上提醒不要太亮', source: 'conversation_extract', status: 'candidate' });

  const result = store.mergeCandidates({
    targetId: 'candidate-a',
    candidateIds: ['candidate-b'],
    text: '用户希望夜间提醒保持低亮度'
  });

  assert.equal(result.entry.id, 'candidate-a');
  assert.equal(result.entry.text, '用户希望夜间提醒保持低亮度');
  assert.equal(result.entry.status, 'candidate');
  assert.deepEqual(result.entry.provenance, ['auto_extract', 'conversation_extract']);
  assert.deepEqual(result.entry.mergedFrom, ['candidate-b']);
  assert.deepEqual(store.list().map(item => item.id).sort(), ['candidate-a', 'confirmed']);
});
