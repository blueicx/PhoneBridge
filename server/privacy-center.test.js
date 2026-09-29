'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { PrivacyCenter, decryptArchive, encryptArchive } = require('./privacy-center');

const PASSPHRASE = 'correct horse battery staple';

function makeCenter(overrides = {}) {
  const calls = [];
  const categories = {
    memories: {
      label: '长期记忆',
      count: () => 2,
      export: () => [{ id: 'm1', text: 'secret memory text' }],
      clear: () => { calls.push('memories'); return { deleted: 2 }; },
    },
    conversations: {
      label: '聊天记录',
      count: () => 3,
      export: () => [{ role: 'user', text: 'secret chat text' }],
      clear: () => { calls.push('conversations'); return { deleted: 3 }; },
    },
    ...overrides.categories,
  };
  const persistence = overrides.persistence || {
    value: null,
    load(_key, fallback) { return this.value ?? fallback; },
    save(_key, value) { this.value = structuredClone(value); },
  };
  return { center: new PrivacyCenter({ categories, persistence, now: () => 1_800_000_000_000 }), calls, persistence };
}

test('encrypted privacy archives round-trip and reject a wrong passphrase', () => {
  const data = { formatVersion: 1, marker: 'private payload' };
  const archive = encryptArchive(data, PASSPHRASE);
  assert.equal(archive.format, 'phonebridge-encrypted-export');
  assert.equal(JSON.stringify(archive).includes('private payload'), false);
  assert.deepEqual(decryptArchive(archive, PASSPHRASE), data);
  assert.throws(() => decryptArchive(archive, 'wrong passphrase value'), /decrypt|integrity|authentication/i);
});

test('encrypted runtime archive CLI round-trips bytes without exposing the passphrase', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-private-archive-'));
  try {
    const source = path.join(root, 'runtime.zip');
    const encrypted = path.join(root, 'runtime.pbenc');
    const restored = path.join(root, 'restored.zip');
    const cli = path.join(__dirname, 'privacy-archive-cli.js');
    const bytes = Buffer.from('private runtime state\u0000\u0001', 'utf8');
    fs.writeFileSync(source, bytes);
    const run = (command, input, output = null) => spawnSync(process.execPath, [cli, command, sourceOrEncrypted(command), ...(output ? [output] : [])], { input: `${input}\n`, encoding: 'utf8' });
    const sourceOrEncrypted = command => command === 'encrypt' ? source : encrypted;

    const encryptedResult = run('encrypt', PASSPHRASE, encrypted);
    assert.equal(encryptedResult.status, 0, encryptedResult.stderr);
    assert.equal(fs.readFileSync(encrypted, 'utf8').includes('private runtime state'), false);
    assert.equal(encryptedResult.stderr.includes(PASSPHRASE), false);
    assert.equal(run('verify', PASSPHRASE).status, 0);
    const decrypted = run('decrypt', PASSPHRASE, restored);
    assert.equal(decrypted.status, 0, decrypted.stderr);
    assert.deepEqual(fs.readFileSync(restored), bytes);
    assert.equal(run('verify', 'incorrect passphrase').status, 1);
  } finally {
    const resolvedTemp = path.resolve(os.tmpdir());
    const resolvedRoot = path.resolve(root);
    assert.ok(resolvedRoot.startsWith(resolvedTemp + path.sep));
    fs.rmSync(resolvedRoot, { recursive: true, force: true });
  }
});

test('privacy overview and encrypted export include only selected known categories', () => {
  const { center } = makeCenter();
  const overview = center.overview();
  assert.equal(overview.categories.memories.count, 2);
  assert.equal(overview.categories.conversations.count, 3);
  assert.deepEqual(Object.keys(overview.categories).sort(), ['conversations', 'memories']);

  const archive = center.exportEncrypted({ categories: ['memories'], passphrase: PASSPHRASE });
  const decoded = decryptArchive(archive, PASSPHRASE);
  assert.deepEqual(Object.keys(decoded.data), ['memories']);
  assert.equal(decoded.data.memories[0].text, 'secret memory text');
  assert.throws(() => center.exportEncrypted({ categories: ['unknown'], passphrase: PASSPHRASE }), /category/i);
  assert.throws(() => center.exportEncrypted({ categories: ['memories'], passphrase: 'short' }), /passphrase/i);
});

test('privacy deletion requires explicit confirmation and is idempotent by request id', async () => {
  const { center, calls, persistence } = makeCenter();
  await assert.rejects(center.delete({ requestId: 'delete-001', categories: ['memories'], confirmation: 'no' }), /confirmation/i);
  const first = await center.delete({ requestId: 'delete-001', categories: ['memories'], confirmation: 'DELETE SELECTED DATA' });
  const replay = await center.delete({ requestId: 'delete-001', categories: ['memories'], confirmation: 'DELETE SELECTED DATA' });
  assert.equal(first.status, 'completed');
  assert.equal(replay.duplicate, true);
  assert.deepEqual(calls, ['memories']);
  assert.equal(JSON.stringify(persistence.value).includes('secret'), false);
  await assert.rejects(center.delete({ requestId: 'delete-001', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' }), /request id/i);
});

test('privacy deletion resumes idempotently after a category adapter fails', async () => {
  let attempts = 0;
  const { center } = makeCenter({ categories: {
    conversations: {
      label: '聊天记录', count: () => 1, export: () => [],
      clear: () => { attempts += 1; if (attempts === 1) throw new Error('temporary persistence failure'); return { deleted: 1 }; },
    },
  } });
  await assert.rejects(center.delete({ requestId: 'delete-002', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' }), /deletion incomplete/);
  const resumed = await center.delete({ requestId: 'delete-002', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' });
  assert.equal(resumed.status, 'completed');
  assert.equal(attempts, 2);
});

test('privacy deletion closes the mutation gate, drains existing writes, then reopens it', async () => {
  let releaseClear;
  let clearStarted;
  const started = new Promise(resolve => { clearStarted = resolve; });
  const clearWait = new Promise(resolve => { releaseClear = resolve; });
  const { center } = makeCenter({ categories: {
    conversations: {
      label: '聊天记录', count: () => 1, export: () => [],
      clear: async () => { clearStarted(); await clearWait; return { deleted: 1 }; },
    },
  } });
  const releaseWrite = center.beginMutation();
  const deletion = center.delete({ requestId: 'delete-gate-001', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' });
  assert.equal(center.isDeleting(), true);
  assert.throws(() => center.beginMutation(), /privacy deletion/i);
  releaseWrite();
  await started;
  assert.throws(() => center.beginMutation(), /privacy deletion/i);
  releaseClear();
  assert.equal((await deletion).status, 'completed');
  assert.equal(center.isDeleting(), false);
  assert.doesNotThrow(() => center.beginMutation()());
});

test('concurrent retries share one deletion and cannot rebind its request id', async () => {
  let resolveClear;
  let calls = 0;
  const waitForClear = new Promise(resolve => { resolveClear = resolve; });
  const { center } = makeCenter({ categories: {
    conversations: {
      label: '聊天记录', count: () => 1, export: () => [],
      clear: async () => { calls += 1; await waitForClear; return { deleted: 1 }; },
    },
  } });
  const payload = { requestId: 'delete-race-001', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' };
  const first = center.delete(payload);
  const second = center.delete(payload);
  await assert.rejects(center.delete({ ...payload, categories: ['memories'] }), /request id/i);
  await assert.rejects(center.delete({ ...payload, requestId: 'delete-race-002' }), /another privacy deletion/i);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls, 1);
  resolveClear();
  const [a, b] = await Promise.all([first, second]);
  assert.equal(a.status, 'completed');
  assert.equal(b.duplicate, true);
  assert.equal(calls, 1);
});
