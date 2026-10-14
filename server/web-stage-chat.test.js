'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { StageChatController } = require('./web-stage-chat');

function createChatHarness(readyState = 1) {
  const sent = [];
  const updates = [];
  const socket = { readyState, send: value => sent.push(JSON.parse(value)) };
  let nextId = 0;
  const chat = new StageChatController({ getSocket: () => socket, createId: () => `id-${++nextId}`, onChange: state => updates.push(state) });
  return { chat, sent, updates };
}

test('stage chat streams only the active request and keeps an authoritative final reply', () => {
  const { chat, sent } = createChatHarness();
  assert.equal(chat.send('你好'), true);
  const requestId = sent[0].requestId;
  assert.equal(sent[0].type, 'chat');
  assert.equal(sent[0].source, 'web-stage');
  assert.equal(chat.state.status, 'generating');
  chat.handle({ type: 'chat_delta', requestId: 'other', text: 'ignored' });
  chat.handle({ type: 'chat_delta', requestId, text: '你' });
  chat.handle({ type: 'chat_delta', requestId, text: '你好！', terminal: true });
  chat.handle({ type: 'chat', requestId, role: 'assistant', text: '你好！' });
  assert.equal(chat.state.messages.length, 2);
  assert.equal(chat.state.messages[1].text, '你好！');
  assert.equal(chat.state.status, 'complete');
  assert.equal(chat.state.activeRequestId, '');
});

test('a late duplicate reply cannot clear a newer active request', () => {
  const { chat, sent } = createChatHarness();
  chat.send('第一轮');
  const firstId = sent[0].requestId;
  chat.handle({ type: 'chat', requestId: firstId, text: '第一轮回复' });
  chat.send('第二轮');
  const secondId = sent[1].requestId;

  assert.equal(chat.handle({ type: 'chat', requestId: firstId, text: '第一轮权威回复' }), true);
  assert.equal(chat.state.activeRequestId, secondId);
  assert.equal(chat.state.status, 'generating');
  assert.equal(chat.state.pendingText, '第二轮');
  assert.equal(chat.state.messages.filter(message => message.id === `${firstId}:assistant`).length, 1);
  chat.handle({ type: 'chat', requestId: secondId, text: '第二轮回复' });
});

test('stage chat cancellation and retry reuse the failed text with a new request id', () => {
  const { chat, sent } = createChatHarness();
  chat.send('继续刚才的话题');
  const firstId = sent[0].requestId;
  chat.handle({ type: 'chat_error', requestId: firstId, error: 'provider unavailable' });
  assert.equal(chat.state.status, 'error');
  assert.equal(chat.retry(), true);
  const retryId = sent[1].requestId;
  assert.notEqual(retryId, firstId);
  assert.equal(sent[1].text, '继续刚才的话题');
  assert.equal(sent[1].retryOfRequestId, firstId);
  chat.stop();
  assert.deepEqual(sent[2], { type: 'chat_cancel', requestId: retryId });
  chat.handle({ type: 'chat_cancelled', requestId: retryId });
  assert.equal(chat.state.status, 'stopped');
});

test('offline stage chat does not send and remains retryable after reconnection', () => {
  const { chat, sent } = createChatHarness(0);
  assert.equal(chat.send('离线问题'), false);
  assert.equal(sent.length, 0);
  assert.equal(chat.state.status, 'offline');
  assert.equal(chat.state.messages.filter(message => message.role === 'user').length, 1);
  assert.equal(chat.state.messages[0].text, '离线问题');
  chat.getSocket = () => ({ readyState: 1, send: value => sent.push(JSON.parse(value)) });
  assert.equal(chat.retry(), true);
  assert.equal(sent[0].text, '离线问题');
  assert.equal(chat.state.messages.filter(message => message.role === 'user').length, 1);
});

test('a new message does not discard an earlier failed message, which remains retryable', () => {
  const { chat, sent } = createChatHarness();
  assert.equal(chat.send('旧消息'), true);
  chat.handle({ type: 'chat_error', requestId: sent[0].requestId, error: 'temporary failure' });

  assert.equal(chat.send('新消息'), true);
  assert.equal(sent[1].text, '新消息');
  assert.equal(chat.state.lastFailedText, '旧消息');
  chat.handle({ type: 'chat', requestId: sent[1].requestId, text: '新消息已回复' });
  assert.equal(chat.state.lastFailedText, '旧消息');

  assert.equal(chat.retry(), true);
  assert.equal(sent[2].text, '旧消息');
  assert.equal(sent[2].retryOfRequestId, sent[0].requestId);
  assert.equal(chat.state.messages.filter(message => message.role === 'user').length, 2);
  chat.handle({ type: 'chat', requestId: sent[2].requestId, text: '旧消息已补发' });
  assert.equal(chat.state.lastFailedText, '');
});

test('history hydration deduplicates in-flight messages by request id and repeated snapshots', () => {
  const { chat, sent } = createChatHarness();
  chat.send('同一句话');
  const requestId = sent[0].requestId;
  chat.handle({ type: 'chat_delta', requestId, text: '回复中' });
  const history = [
    { requestId, role: 'user', text: '同一句话', time: new Date().toISOString() },
    { requestId, role: 'assistant', text: '旧版快照回复', time: new Date().toISOString() },
    { requestId: 'older-identical-message', role: 'user', text: '同一句话', time: '2020-01-01T00:00:00.000Z' },
    { role: 'user', text: '旧历史', time: '2020-01-01T00:00:00.000Z' },
  ];
  chat.hydrate(history);
  chat.hydrate(history);

  assert.equal(chat.state.messages.filter(message => message.requestId === requestId && message.role === 'user').length, 1);
  assert.equal(chat.state.messages.filter(message => message.requestId === requestId && message.role === 'assistant').length, 1);
  assert.equal(chat.state.messages.filter(message => message.role === 'user' && message.text === '同一句话').length, 2);
  assert.equal(chat.state.messages.filter(message => message.text === '旧历史').length, 1);
});

test('session resume survives an empty history snapshot and updates when history arrives', () => {
  const { chat } = createChatHarness();
  const resume = {
    lastUserMessage: '上次我提到正在准备一个演示。',
    lastAssistantMessage: '我们最后决定先完成最小可用版本。',
    updatedAt: '2026-10-14T08:00:00.000Z',
  };

  chat.hydrate([], resume);
  assert.deepEqual(chat.state.resume, resume);
  assert.equal(chat.state.messages.length, 0);

  chat.hydrate([{ role: 'user', text: '正在准备一个演示。' }], null);
  assert.deepEqual(chat.state.resume, resume);
  assert.equal(chat.state.messages.length, 1);
});

test('a completed server turn clears the retry marker after a reconnect snapshot', () => {
  const { chat, sent } = createChatHarness();
  chat.send('断线期间已完成');
  const requestId = sent[0].requestId;
  chat.connectionLost();
  assert.equal(chat.state.failedMessages.length, 1);
  chat.hydrate([
    { requestId, role: 'user', text: '断线期间已完成', time: new Date().toISOString() },
    { requestId, role: 'assistant', text: '服务端已经完成', time: new Date().toISOString() },
  ]);
  assert.equal(chat.state.failedMessages.length, 0);
  assert.equal(chat.state.lastFailedText, '');
  assert.equal(chat.state.messages.at(-1).text, '服务端已经完成');
});

test('browser runtime exposes the same controller class', () => {
  const vm = require('node:vm');
  const { browserStageChatScript } = require('./web-stage-chat');
  const context = { window: {} };
  vm.runInNewContext(browserStageChatScript(), context);
  assert.equal(typeof context.window.StageChatController, 'function');
});
