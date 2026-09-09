const test = require('node:test');
const assert = require('node:assert/strict');
const { TaskRunner } = require('./task-runner');

const waitFor = predicate => new Promise((resolve, reject) => {
  const started = Date.now();
  const tick = () => predicate() ? resolve() : Date.now() - started > 2000 ? reject(new Error('timeout')) : setTimeout(tick, 2);
  tick();
});

test('queues one task at a time and retries a transient failure', async () => {
  const starts = [];
  let attempts = 0;
  const runner = new TaskRunner({ maxConcurrency: 1, retryDelayMs: 1, maxRetries: 1 });
  runner.setExecutor(async ({ task, report }) => {
    starts.push(task.id);
    report(50);
    if (task.id === 'first' && attempts++ === 0) throw new Error('temporary');
    return { ok: true, id: task.id };
  });
  runner.enqueue({ id: 'first', title: 'first' });
  runner.enqueue({ id: 'second', title: 'second' });
  await runner.whenIdle();
  assert.deepEqual(starts.slice().sort(), ['first', 'first', 'second'].sort());
  assert.equal(runner.get('first').state, 'succeeded');
  assert.equal(runner.get('first').attempt, 2);
  assert.equal(runner.get('second').state, 'succeeded');
  assert.equal(runner.get('second').progress, 100);
});

test('pause, resume and cancel affect queued and active tasks', async () => {
  let release;
  const blocker = new Promise(resolve => { release = resolve; });
  const runner = new TaskRunner({ maxConcurrency: 1 });
  runner.setExecutor(async ({ task, signal, waitIfPaused }) => {
    await waitIfPaused();
    if (task.id === 'active') {
      await new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
        blocker.then(resolve);
      });
    }
    return task.id;
  });
  runner.enqueue({ id: 'active', title: 'active' });
  runner.enqueue({ id: 'queued', title: 'queued' });
  await waitFor(() => runner.get('active')?.state === 'running');
  assert.equal(runner.pause('queued').state, 'paused');
  assert.equal(runner.cancel('active').state, 'cancelled');
  release();
  await runner.whenIdle();
  assert.equal(runner.get('queued').state, 'paused');
  assert.ok(['pending', 'running'].includes(runner.resume('queued').state));
  await runner.whenIdle();
  assert.equal(runner.get('queued').state, 'succeeded');
});
