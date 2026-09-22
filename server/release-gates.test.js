'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildReleaseManifest, validateReleaseManifest } = require('./release-gates');

const validArtifact = {
  artifactPath: 'android/app/build/outputs/apk/debug/app-debug.apk',
  artifactBytes: 1200,
  artifactSha256: 'a'.repeat(64),
};

test('release gate accepts a feature-branch internal debug artifact', () => {
  const manifest = buildReleaseManifest({
    versionName: '2.0.0',
    versionCode: 2,
    branch: 'feature/integrated-enhancement',
    commit: 'a'.repeat(40),
    ...validArtifact,
    generatedAt: '2026-09-22T00:00:00.000Z',
  });
  assert.deepEqual(validateReleaseManifest(manifest), { ok: true, errors: [] });
});

test('release gate rejects unsigned release, protected branch, and sensitive artifact', () => {
  const result = validateReleaseManifest(buildReleaseManifest({
    versionName: '2.0.0',
    versionCode: 2,
    branch: 'main',
    commit: 'a'.repeat(40),
    channel: 'release',
    artifactPath: 'server/access.token',
    artifactBytes: 1,
    artifactSha256: 'not-a-hash',
  }));
  assert.equal(result.ok, false);
  assert.ok(result.errors.some(error => /signed/.test(error)));
  assert.ok(result.errors.some(error => /protected/.test(error)));
  assert.ok(result.errors.some(error => /publishable/.test(error)));
  assert.ok(result.errors.some(error => /sha256/.test(error)));
});

test('release gate requires a signed artifact for the release channel', () => {
  const manifest = buildReleaseManifest({
    versionName: '2.0.0',
    versionCode: 2,
    branch: 'feature/release',
    commit: 'b'.repeat(40),
    channel: 'release',
    ...validArtifact,
  });
  assert.deepEqual(validateReleaseManifest(manifest), { ok: false, errors: ['release artifacts must be signed'] });
});
