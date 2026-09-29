'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { ActiveRequestRegistry } = require('./active-request-registry');

test('active request deletion cancels every request and waits for their cleanup', async () => {
  const registry = new ActiveRequestRegistry();
  let cancelled = 0;
  let finish;
  const complete = registry.begin('chat-1', () => {
    cancelled += 1;
    setImmediate(() => finish());
  });
  finish = complete;

  assert.equal(registry.hasActive(), true);
  assert.equal(await registry.cancelAndWait(), 1);
  assert.equal(cancelled, 1);
  assert.equal(registry.hasActive(), false);
});

test('active request ids cannot be reused until the original request settles', () => {
  const registry = new ActiveRequestRegistry();
  const complete = registry.begin('chat-1');
  assert.throws(() => registry.begin('chat-1'), /already in use/);
  complete();
  const completeAgain = registry.begin('chat-1');
  completeAgain();
});

test('privacy pause rejects new chat requests until deletion finishes', async () => {
  const registry = new ActiveRequestRegistry();
  const [resume, resumeSecond] = await Promise.all([
    registry.pauseCancelAndWait(),
    registry.pauseCancelAndWait(),
  ]);
  assert.throws(() => registry.begin('chat-2'), /temporarily paused/);
  resume();
  assert.throws(() => registry.begin('chat-2'), /temporarily paused/);
  resumeSecond();
  const complete = registry.begin('chat-2');
  complete();
});
