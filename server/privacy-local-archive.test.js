'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fixture = require('../protocol-fixtures/privacy-local-archive.json');
const { decryptLocalPrivacyArchive } = require('./privacy-local-archive');

const PASSPHRASE = 'PhoneBridge fixture test passphrase';

test('Node decrypts the shared Android local-archive fixture', () => {
  const plaintext = decryptLocalPrivacyArchive(fixture, PASSPHRASE);
  assert.deepEqual(JSON.parse(plaintext.toString('utf8')), {
    formatVersion: 1,
    test: 'cross-runtime crypto fixture',
  });
});

test('local archive rejects wrong passwords and malformed parameters', () => {
  assert.throws(() => decryptLocalPrivacyArchive(fixture, 'wrong passphrase value'), /authentication/i);
  assert.throws(() => decryptLocalPrivacyArchive({ ...fixture, iterations: 100 }, PASSPHRASE), /unsupported/i);
});
