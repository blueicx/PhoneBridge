const test = require('node:test');
const assert = require('node:assert/strict');
const { RevisionSnapshotCache } = require('./workspace-performance');

test('performance cache exposes stable hit and build counts', () => {
  let revision = 1;
  const cache = new RevisionSnapshotCache({
    getRevision: () => revision,
    build: current => ({ full: JSON.stringify({ current }), summary: JSON.stringify({ current, view: 'summary' }) }),
  });
  assert.equal(JSON.parse(cache.get('full')).current, 1);
  assert.equal(JSON.parse(cache.get('summary')).view, 'summary');
  assert.deepEqual(cache.stats(), { revision: 1, builds: 1, hits: 1 });
  revision = 2;
  assert.equal(JSON.parse(cache.get('full')).current, 2);
  assert.equal(cache.stats().builds, 2);
});
