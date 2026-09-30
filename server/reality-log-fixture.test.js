'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { MOTE_PROFILES } = require('./mote-profiles');
const { buildRealityLog } = require('./reality-log');

const fixture = JSON.parse(fs.readFileSync(
  path.join(__dirname, '..', 'protocol-fixtures', 'reality-log.json'),
  'utf8'
));

test('shared reality-log fixture matches the receipt projection privacy allowlist', () => {
  const expected = fixture.serverPage.entries[0];
  const result = buildRealityLog({
    growthStore: {
      listReceipts: () => [{
        eventId: expected.eventId,
        businessStatus: 'accepted',
        createdAt: expected.occurredAt,
        region: expected.coarseRegion,
        clueType: expected.clueType,
        reward: { xp: expected.reward.xp },
      }],
    },
    realityEngine: { listReceipts: () => [] },
    moteProfiles: { activeId: expected.moteId, profiles: MOTE_PROFILES },
  });

  assert.deepEqual(Object.keys(result.entries[0]).sort(), fixture.allowedServerEntryFields.slice().sort());
  assert.deepEqual(result, fixture.serverPage);
  assert.equal(fixture.pendingOutbox.privacyCategory, fixture.privacyCategory);
  assert.equal(fixture.rejectedOutbox.privacyCategory, fixture.privacyCategory);
  assert.equal(Object.hasOwn(fixture.pendingOutbox, 'reward'), false);
});
