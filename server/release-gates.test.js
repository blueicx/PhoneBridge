'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildReleaseManifest, validateReleaseManifest } = require('./release-gates');

const validArtifact = {
  artifactPath: 'android/app/build/outputs/apk/debug/app-debug.apk',
  artifactBytes: 1200,
  artifactSha256: 'a'.repeat(64),
  artifactPackageName: 'com.phonebridge',
  artifactVersionName: '2.1.0',
  artifactVersionCode: 3,
  artifactSignatureVerified: true,
};

test('release gate accepts a feature-branch internal debug artifact', () => {
  const manifest = buildReleaseManifest({
    versionName: '2.1.0',
    versionCode: 3,
    branch: 'feature/integrated-enhancement',
    commit: 'a'.repeat(40),
    ...validArtifact,
    generatedAt: '2026-09-22T00:00:00.000Z',
  });
  assert.deepEqual(validateReleaseManifest(manifest), { ok: true, errors: [] });
});

test('release gate rejects unsigned release, protected branch, and sensitive artifact', () => {
  const result = validateReleaseManifest(buildReleaseManifest({
    versionName: '2.1.0',
    versionCode: 3,
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
  assert.ok(result.errors.some(error => /package|signature|version/i.test(error)));
});

test('release gate requires a signed artifact for the release channel', () => {
  const manifest = buildReleaseManifest({
    versionName: '2.1.0',
    versionCode: 3,
    branch: 'feature/release',
    commit: 'b'.repeat(40),
    channel: 'release',
    ...validArtifact,
    artifactSignatureVerified: false,
  });
  const result = validateReleaseManifest(manifest);
  assert.equal(result.ok, false);
  assert.ok(result.errors.includes('release artifacts must be signed'));
  assert.ok(result.errors.includes('artifact signature was not verified'));
});

test('release gate rejects an APK whose embedded metadata differs from Gradle', () => {
  const manifest = buildReleaseManifest({
    versionName: '2.1.0',
    versionCode: 3,
    branch: 'feature/release',
    commit: 'c'.repeat(40),
    artifactPackageName: 'com.other.app',
    artifactVersionName: '2.0.0',
    artifactVersionCode: 2,
    artifactSignatureVerified: true,
    artifactPath: validArtifact.artifactPath,
    artifactBytes: validArtifact.artifactBytes,
    artifactSha256: validArtifact.artifactSha256,
  });
  const result = validateReleaseManifest(manifest);
  assert.equal(result.ok, false);
  assert.ok(result.errors.some(error => /package name/i.test(error)));
  assert.ok(result.errors.some(error => /versionName/i.test(error)));
  assert.ok(result.errors.some(error => /versionCode/i.test(error)));
});
