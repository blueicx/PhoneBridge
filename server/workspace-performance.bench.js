const { performance } = require('node:perf_hooks');
const { RevisionSnapshotCache, BroadcastCoalescer } = require('./workspace-performance');

let revision = 1;
let sent = 0;
const cache = new RevisionSnapshotCache({
  getRevision: () => revision,
  build: () => {
    const full = JSON.stringify({ type: 'snapshot', revision, tasks: Array.from({ length: 40 }, (_, index) => ({ id: index, title: `task-${index}`, progress: index })) });
    const summary = JSON.stringify({ view: 'summary', revision, taskCount: 40 });
    return { full, summary };
  },
});
const coalescer = new BroadcastCoalescer({ send: () => { sent += 1; }, delayMs: 0 });
const started = performance.now();
for (let index = 0; index < 10_000; index += 1) cache.get(index % 100 === 0 ? 'summary' : 'full');
for (let index = 0; index < 100; index += 1) coalescer.enqueue({ revision: ++revision });
coalescer.flushNow();
const elapsedMs = Number((performance.now() - started).toFixed(3));
const fullBytes = Buffer.byteLength(cache.get('full'));
const summaryBytes = Buffer.byteLength(cache.get('summary'));
if (summaryBytes >= 25_000) process.exitCode = 1;
process.stdout.write(JSON.stringify({ elapsedMs, fullBytes, summaryBytes, snapshotCacheBuilds: cache.stats().builds, snapshotCacheHits: cache.stats().hits, broadcastsAfterBurst: sent }) + '\n');
