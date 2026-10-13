'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');

test('GET callers share work while mutations remain independent', async () => {
  const { WebRequestCoordinator } = require('./web-runtime');
  const requests = new WebRequestCoordinator();
  let calls = 0, finish;
  const operation = () => { calls++; return new Promise(resolve => { finish = resolve; }); };
  const first = requests.run('/state', operation);
  const second = requests.run('/state', operation);
  await Promise.resolve();
  assert.equal(calls, 1);
  finish({ revision: 3 });
  assert.deepEqual(await Promise.all([first, second]), [{ revision: 3 }, { revision: 3 }]);
  await requests.run('/state', async () => { calls++; });
  assert.equal(calls, 2);
});

test('leaving a page aborts pending reads and suppresses their late result', async () => {
  const { WebRequestCoordinator } = require('./web-runtime');
  const requests = new WebRequestCoordinator();
  let signal, finish;
  const pending = requests.run('/state', value => { signal = value; return new Promise(resolve => { finish = resolve; }); });
  await Promise.resolve();
  requests.abortReads();
  assert.equal(signal.aborted, true);
  finish({ revision: 8 });
  await assert.rejects(pending, { name: 'AbortError' });
});

test('hidden pages, closed drawers and active push suppress polling without suppressing local timers', async () => {
  const { WebRefreshScheduler } = require('./web-runtime');
  const scheduler = new WebRefreshScheduler();
  let stage = 0, drawer = 0, frames = 0, local = 0;
  scheduler.register('stage', () => stage++, 'stage');
  scheduler.register('drawer', () => drawer++, 'drawer');
  scheduler.register('frames', () => frames++, 'frames');
  scheduler.register('local', () => local++, 'local');
  await scheduler.tickAll();
  assert.deepEqual([stage, drawer, frames, local], [1, 0, 0, 1]);
  scheduler.update({ drawerOpen: true, framesVisible: true, pushOnline: true });
  await scheduler.tickAll();
  assert.deepEqual([stage, drawer, frames, local], [1, 0, 1, 2]);
  scheduler.update({ visible: false });
  await scheduler.tickAll();
  assert.deepEqual([stage, drawer, frames, local], [1, 0, 1, 2]);
  scheduler.update({ visible: true, pushOnline: false });
  await scheduler.tickAll();
  assert.deepEqual([stage, drawer, frames, local], [2, 1, 2, 3]);
});

test('slow refresh jobs do not overlap and rejected jobs can run again', async () => {
  const { WebRefreshScheduler } = require('./web-runtime');
  const scheduler = new WebRefreshScheduler();
  let calls = 0, finish;
  scheduler.register('stage', () => { calls++; return new Promise(resolve => { finish = resolve; }); }, 'stage');
  const pending = scheduler.tick('stage');
  await scheduler.tick('stage');
  assert.equal(calls, 1);
  finish(); await pending;
  scheduler.register('error', () => { throw new Error('network'); }, 'stage');
  await scheduler.tick('error');
  await scheduler.tick('error');
  assert.equal(scheduler.errors, 2);
});

test('runtime source is self-contained in the browser', () => {
  const { browserRuntimeScript } = require('./web-runtime');
  const context = { window: {} };
  vm.runInNewContext(browserRuntimeScript(), context);
  assert.equal(typeof context.window.WebRequestCoordinator, 'function');
  assert.equal(typeof context.window.WebRefreshScheduler, 'function');
});
