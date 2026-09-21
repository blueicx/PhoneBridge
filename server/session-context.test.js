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
