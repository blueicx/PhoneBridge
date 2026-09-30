'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { PrivacyCenter, decryptArchive, encryptArchive } = require('./privacy-center');
const { MOTE_PROFILES } = require('./mote-profiles');
const { MoteGrowthStore, buildClueEventId } = require('./mote-growth');
const { RealityEngine } = require('./reality-engine');
const { buildRealityLog } = require('./reality-log');
const privacyMigrationFixture = require('../protocol-fixtures/privacy-migration.json');
const privacyOverviewFixture = require('../protocol-fixtures/privacy-overview.json');

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

const LEGACY_CATEGORIES = ['memories', 'conversations', 'tasks', 'progress'];

function makeLegacyCenter(receiptCount) {
  const receipts = Array.from({ length: receiptCount }, (_, index) => {
    const category = LEGACY_CATEGORIES[index % LEGACY_CATEGORIES.length];
    return {
      requestId: `legacy-delete-${String(index).padStart(3, '0')}`,
      categories: [category],
      completedCategories: [category],
      status: 'completed',
    };
  });
  const persistence = {
    value: { version: 1, receipts },
    load(_key, fallback) { return this.value ?? fallback; },
    save(_key, value) { this.value = structuredClone(value); },
  };
  const categories = Object.fromEntries([...LEGACY_CATEGORIES, 'routines', 'goals'].map(id => [id, {
    label: id,
    count: () => 0,
    export: () => [],
    clear: () => ({ deleted: 0 }),
  }]));
  return { center: new PrivacyCenter({ categories, persistence, now: () => 1_800_000_000_000 }), persistence, receipts };
}

test('encrypted privacy archives round-trip and reject a wrong passphrase', () => {
  const data = { formatVersion: 1, marker: 'private payload' };
  const archive = encryptArchive(data, PASSPHRASE);
  assert.equal(archive.format, 'phonebridge-encrypted-export');
  assert.equal(JSON.stringify(archive).includes('private payload'), false);
  assert.deepEqual(decryptArchive(archive, PASSPHRASE), data);
  assert.throws(() => decryptArchive(archive, 'wrong passphrase value'), /decrypt|integrity|authentication/i);
});

test('deleting progress removes every receipt source used by the derived reality log', async () => {
  const now = Date.parse('2026-09-30T03:00:00.000Z');
  const realityEngine = new RealityEngine({ now: () => now });
  const realityEvent = realityEngine.eventsFor('cell:1:2', now).find(item => item.clueType === 'location');
  realityEngine.resolve({ eventId: realityEvent.id, region: 'cell:1:2', clueType: 'location', at: now });
  const growthStore = new MoteGrowthStore({ now: () => now });
  const growthEventId = buildClueEventId({ activityAt: now, region: 'camera', clueType: 'light', nonce: 'privacy_test' });
  growthStore.recordClue({ eventId: growthEventId, region: 'camera', clueType: 'light', activityAt: now });
  const moteProfiles = { activeId: 'mote', profiles: MOTE_PROFILES };
  const buildLog = () => buildRealityLog({ growthStore, realityEngine, moteProfiles });
  assert.equal(buildLog().entries.length, 2);

  const { center } = makeCenter({ categories: {
    progress: {
      label: 'Mote 成长与探索',
      count: () => growthStore.listReceipts().length + realityEngine.listReceipts().length,
      export: () => ({ growth: growthStore.listReceipts(), reality: realityEngine.listReceipts() }),
      clear: () => {
        const deleted = growthStore.listReceipts().length + realityEngine.listReceipts().length;
        growthStore.reset();
        realityEngine.reset();
        return { deleted };
      },
    },
  } });
  const deletion = await center.delete({
    requestId: 'reality-log-progress-delete',
    categories: ['progress'],
    confirmation: 'DELETE SELECTED DATA',
  });

  assert.equal(deletion.status, 'completed');
  assert.equal(buildLog().entries.length, 0);
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

test('legacy privacy audit below the receipt cap reconstructs revisions by unique request and category', () => {
  const { center, persistence, receipts } = makeLegacyCenter(99);
  const overview = center.overview();
  const expected = Object.fromEntries(LEGACY_CATEGORIES.map(category => [
    category,
    receipts.filter(receipt => receipt.categories.includes(category)).length,
  ]));

  assert.equal(overview.migration.status, 'complete');
  for (const category of LEGACY_CATEGORIES) {
    assert.equal(overview.categories[category].revision, expected[category]);
    assert.equal(overview.categories[category].migrationRequired, false);
  }
  assert.equal(overview.categories.routines.revision, 0);
  assert.equal(overview.categories.goals.revision, 0);
  assert.equal(overview.categories.routines.migrationRequired, false);
  assert.equal(persistence.value.version, 2);
});

test('legacy privacy audit at the cap pauses each old category until an explicit choice', async () => {
  const { center } = makeLegacyCenter(100);
  let overview = center.overview();

  assert.equal(overview.migration.status, 'required');
  assert.deepEqual(overview.migration.requiredCategories, LEGACY_CATEGORIES);
  for (const category of LEGACY_CATEGORIES) assert.equal(overview.categories[category].migrationRequired, true);
  assert.equal(overview.categories.routines.migrationRequired, false);

  await center.resolveMigrationCategory('progress', 'keep');
  overview = center.overview();
  assert.equal(overview.categories.progress.revision >= 1, true);
  assert.equal(overview.categories.progress.migrationRequired, false);
  assert.equal(overview.migration.status, 'required');
  await assert.rejects(center.resolveMigrationCategory('progress', 'clear'), /decision|conflict/i);
});

test('legacy audit with more than the receipt cap is treated as ambiguous after loading', () => {
  const { center, persistence } = makeLegacyCenter(101);
  const overview = center.overview();

  assert.equal(persistence.value.receipts.length, 100);
  assert.equal(overview.migration.status, 'required');
  assert.deepEqual(overview.migration.requiredCategories, LEGACY_CATEGORIES);
});

test('legacy migration decisions remain idempotent after all categories are resolved and the process restarts', async () => {
  const { center, persistence } = makeLegacyCenter(100);
  for (const category of LEGACY_CATEGORIES) await center.resolveMigrationCategory(category, 'keep');
  const adapters = Object.fromEntries([...LEGACY_CATEGORIES, 'routines', 'goals'].map(id => [id, {
    count: () => 0,
    export: () => [],
    clear: () => ({ deleted: 0 }),
  }]));
  const restored = new PrivacyCenter({ categories: adapters, persistence, now: () => 1_800_000_000_000 });

  assert.equal(restored.overview().migration.status, 'complete');
  assert.equal((await restored.resolveMigrationCategory('progress', 'keep')).duplicate, true);
  await assert.rejects(restored.resolveMigrationCategory('progress', 'clear'), /conflict/i);
});

test('legacy clear runs the category purge once and advances its revision fence', async () => {
  const { center } = makeLegacyCenter(100);
  let clears = 0;
  center.categories.get('progress').clear = () => { clears += 1; return { deleted: 7 }; };
  const before = center.categoryRevision('progress');

  const result = await center.resolveMigrationCategory('progress', 'clear');
  const replay = await center.resolveMigrationCategory('progress', 'clear');

  assert.equal(clears, 1);
  assert.equal(result.decisions.progress, 'clear');
  assert.equal(result.categoryRevisions.progress, before + 1);
  assert.equal(replay.duplicate, true);
});

test('concurrent legacy migration retries share one clear and reject a competing choice', async () => {
  const { center } = makeLegacyCenter(100);
  let clears = 0;
  let releaseClear;
  let markStarted;
  const started = new Promise(resolve => { markStarted = resolve; });
  const waiting = new Promise(resolve => { releaseClear = resolve; });
  center.categories.get('progress').clear = async () => {
    clears += 1;
    markStarted();
    await waiting;
    return { deleted: 1 };
  };

  const first = center.resolveMigrationCategory('progress', 'clear');
  await started;
  const replay = center.resolveMigrationCategory('progress', 'clear');
  await assert.rejects(center.resolveMigrationCategory('progress', 'keep'), /conflict/i);
  releaseClear();
  const [result, duplicate] = await Promise.all([first, replay]);

  assert.equal(clears, 1);
  assert.equal(result.duplicate, false);
  assert.equal(duplicate.duplicate, true);
});

test('shared privacy migration fixture matches server overview projection', () => {
  const persistence = {
    load(name) {
      if (name !== 'privacy-audit') return null;
      return {
        version: 2,
        categoryRevisions: Object.fromEntries(Object.entries(privacyMigrationFixture.categories).map(([id, value]) => [id, value.revision])),
        migration: structuredClone(privacyMigrationFixture.migration),
        receipts: [],
      };
    },
    save() {},
  };
  const categories = Object.fromEntries(Object.entries(privacyMigrationFixture.categories).map(([id, item]) => [id, {
    label: item.label,
    count: () => item.count,
    export: () => [],
    clear: () => ({ deleted: item.count }),
  }]));
  const overview = new PrivacyCenter({ categories, persistence, now: () => 1_800_000_000_000 }).overview();

  assert.deepEqual(overview.migration, privacyMigrationFixture.migration);
  for (const [id, expected] of Object.entries(privacyMigrationFixture.categories)) {
    assert.deepEqual(overview.categories[id], expected);
  }
});

test('shared privacy overview fixture covers every client category and exclusion', () => {
  const categories = Object.fromEntries(Object.entries(privacyOverviewFixture.categories).map(([id, item]) => [id, {
    label: item.label,
    count: () => item.count,
    export: () => [],
    clear: () => ({ deleted: item.count }),
  }]));
  const persistence = {
    load(name) {
      if (name !== 'privacy-audit') return null;
      return {
        version: 2,
        categoryRevisions: Object.fromEntries(Object.entries(privacyOverviewFixture.categories).map(([id, item]) => [id, item.revision])),
        migration: structuredClone(privacyOverviewFixture.migration),
        receipts: [],
      };
    },
    save() {},
  };
  const overview = new PrivacyCenter({ categories, persistence, now: () => 1_800_000_000_000 }).overview();

  assert.deepEqual(overview.categories, privacyOverviewFixture.categories);
  assert.deepEqual(overview.migration, privacyOverviewFixture.migration);
  assert.deepEqual(overview.excluded, privacyOverviewFixture.excluded);
});

test('category revisions are allocated once, survive restart, and do not advance on deletion replay', async () => {
  const { center, persistence } = makeCenter();
  assert.equal(center.categoryRevision('memories'), 0);
  const payload = { requestId: 'revision-delete-001', categories: ['memories'], confirmation: 'DELETE SELECTED DATA' };
  const first = await center.delete(payload);
  const replay = await center.delete(payload);
  const restored = new PrivacyCenter({
    categories: {
      memories: { count: () => 0, export: () => [], clear: () => ({ deleted: 0 }) },
      conversations: { count: () => 0, export: () => [], clear: () => ({ deleted: 0 }) },
    },
    persistence,
    now: () => 1_800_000_000_000,
  });

  assert.equal(persistence.value.version, 2);
  assert.equal(first.categoryRevisions.memories, 1);
  assert.equal(replay.categoryRevisions.memories, 1);
  assert.equal(replay.duplicate, true);
  assert.equal(restored.categoryRevision('memories'), 1);
});

test('partial privacy deletion retry reuses its original category revisions', async () => {
  let attempts = 0;
  const { center } = makeCenter({ categories: {
    conversations: {
      label: '聊天记录', count: () => 1, export: () => [],
      clear: () => { attempts += 1; if (attempts === 1) throw new Error('temporary persistence failure'); return { deleted: 1 }; },
    },
  } });
  const payload = { requestId: 'revision-delete-002', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' };

  await assert.rejects(center.delete(payload), /deletion incomplete/);
  assert.equal(center.categoryRevision('conversations'), 1);
  const resumed = await center.delete(payload);
  assert.equal(resumed.categoryRevisions.conversations, 1);
  assert.equal(center.categoryRevision('conversations'), 1);
});

test('observed client privacy revisions only advance monotonically and survive restart', () => {
  const { center, persistence } = makeCenter();
  assert.equal(center.observeCategoryRevisions({ memories: 3, conversations: 2 }), true);
  assert.equal(center.observeCategoryRevisions({ memories: 2, conversations: 2 }), false);
  assert.equal(center.categoryRevision('memories'), 3);
  assert.equal(center.categoryRevision('conversations'), 2);
  assert.equal(persistence.value.categoryRevisions.memories, 3);
  const restored = new PrivacyCenter({
    categories: {
      memories: { count: () => 0, export: () => [], clear: () => ({ deleted: 0 }) },
      conversations: { count: () => 0, export: () => [], clear: () => ({ deleted: 0 }) },
    }, persistence, now: () => 1_800_000_000_000,
  });
  assert.equal(restored.categoryRevision('memories'), 3);
  assert.equal(restored.categoryRevision('conversations'), 2);
  assert.throws(() => restored.observeCategoryRevision('memories', -1), /revision/i);
  assert.throws(() => restored.observeCategoryRevision('unknown', 1), /unknown privacy category/i);

  const save = persistence.save;
  persistence.save = () => { throw new Error('disk unavailable'); };
  assert.throws(() => center.observeCategoryRevisions({ memories: 4, conversations: 3 }), /disk unavailable/i);
  assert.equal(center.categoryRevision('memories'), 3);
  assert.equal(center.categoryRevision('conversations'), 2);
  persistence.save = save;
});

test('direct category deletion advances its revision atomically and survives restart', () => {
  const { center, persistence } = makeCenter({ categories: {
    goals: { label: '个人目标', count: () => 1, export: () => [], clear: () => ({ deleted: 1 }) },
  } });
  assert.equal(center.advanceCategoryRevision('goals'), 1);
  assert.equal(center.categoryRevision('goals'), 1);
  const restored = new PrivacyCenter({
    categories: {
      memories: { count: () => 0, export: () => [], clear: () => ({ deleted: 0 }) },
      conversations: { count: () => 0, export: () => [], clear: () => ({ deleted: 0 }) },
      goals: { count: () => 0, export: () => [], clear: () => ({ deleted: 0 }) },
    },
    persistence,
    now: () => 1_800_000_000_000,
  });
  assert.equal(restored.categoryRevision('goals'), 1);

  const save = persistence.save;
  persistence.save = () => { throw new Error('disk unavailable'); };
  assert.throws(() => center.advanceCategoryRevision('goals'), /disk unavailable/);
  assert.equal(center.categoryRevision('goals'), 1);
  persistence.save = save;
});

test('unfinished deletion receipts survive completed-history compaction', async () => {
  let failOnce = true;
  const { center, persistence } = makeCenter({ categories: {
    conversations: {
      label: '聊天记录', count: () => 1, export: () => [],
      clear: () => { if (failOnce) { failOnce = false; throw new Error('temporary failure'); } return { deleted: 1 }; },
    },
  } });
  const pending = { requestId: 'pending-delete-001', categories: ['conversations'], confirmation: 'DELETE SELECTED DATA' };
  await assert.rejects(center.delete(pending), /deletion incomplete/);
  for (let index = 0; index < 105; index += 1) {
    await center.delete({
      requestId: `history-delete-${String(index).padStart(3, '0')}`,
      categories: ['memories'],
      confirmation: 'DELETE SELECTED DATA',
    });
  }
  assert.ok(persistence.value.receipts.some(receipt => receipt.requestId === pending.requestId));
  assert.equal((await center.delete(pending)).status, 'completed');
});
