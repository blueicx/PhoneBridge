const test = require('node:test');
const assert = require('node:assert/strict');
const { prepareConversation } = require('./session-context');

test('conversation context keeps recent messages and creates a deterministic summary', () => {
  const result = prepareConversation({
    history: Array.from({ length: 20 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', text: `message-${index}` })),
    memories: ['喜欢短回答'],
    maxMessages: 4,
  });
  assert.equal(result.messages.slice(-1)[0].text, 'message-19');
  assert.match(result.messages[0].text, /早期会话摘要/);
  assert.deepEqual(result.memories, ['喜欢短回答']);
});

test('context ignores malformed messages and caps content lengths', () => {
  const result = prepareConversation({ history: [{ role: 'tool', text: 'ignored' }, { role: 'user', text: 'x'.repeat(10000) }] });
  assert.equal(result.messages.length, 1);
  assert.equal(result.messages[0].text.length, 4000);
});

test('no-memory turns do not create summaries or carry stored memory entries', () => {
  const result = prepareConversation({
    remember: false,
    history: Array.from({ length: 20 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', text: `turn-${index}` })),
    memories: [{ id: 'm1', text: '长期偏好', source: 'user' }],
    maxMessages: 4
  });

  assert.equal(result.summary, '');
  assert.deepEqual(result.memories, []);
  assert.deepEqual(result.memoryEntries, []);
  assert.equal(result.messages.some(item => item.text.includes('早期会话摘要')), false);
  assert.equal(result.messages.at(-1).text, 'turn-19');
});

test('conversation compression respects the character budget and retains memory provenance', () => {
  const result = prepareConversation({
    history: Array.from({ length: 12 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', text: `${index}:` + 'x'.repeat(350) })),
    memories: [{ id: 'm-quiet', text: '喜欢安静提醒', source: 'user-confirmed', updatedAt: '2026-10-14T00:00:00.000Z' }],
    maxMessages: 12,
    maxChars: 1400
  });

  assert.ok(result.messages.reduce((sum, item) => sum + item.text.length, 0) <= 1400);
  assert.match(result.summary, /0:/);
  assert.match(result.messages.at(-1).text, /11:/);
  assert.deepEqual(result.memoryEntries, [{
    id: 'm-quiet', text: '喜欢安静提醒', source: 'user-confirmed', updatedAt: '2026-10-14T00:00:00.000Z'
  }]);
  assert.deepEqual(result.memoryUsage, [{ id: 'm-quiet', source: 'user-confirmed', reason: 'conversation_recall' }]);
});
