'use strict';

const fs = require('node:fs');

const SHA256 = /^[a-f0-9]{64}$/i;
const VERSION = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;
const COMMIT = /^[a-f0-9]{7,64}$/i;
const FORBIDDEN_ARTIFACT = /(?:access\.token|authorization|password|secret|\.log(?:$|[./\\])|frames[./\\]|screenshots[./\\])/i;

function number(value, fallback = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function validateReleaseManifest(manifest = {}) {
  const errors = [];
  const channel = String(manifest.channel || '').trim();
  const versionName = String(manifest.versionName || '').trim();
  const versionCode = number(manifest.versionCode, 0);
  const branch = String(manifest.branch || '').trim();
  const commit = String(manifest.commit || '').trim();
  const artifact = manifest.artifact && typeof manifest.artifact === 'object' ? manifest.artifact : null;

  const schemaVersion = Number(manifest.schemaVersion);
  if (![1, 2].includes(schemaVersion)) errors.push('schemaVersion must be 1 or 2');
  if (!VERSION.test(versionName)) errors.push('versionName must be semver-like');
  if (!Number.isInteger(versionCode) || versionCode < 1) errors.push('versionCode must be a positive integer');
  if (!channel || !['internal-debug', 'release'].includes(channel)) errors.push('channel must be internal-debug or release');
  if (!branch) errors.push('branch is required');
  if (/^(main|master)$/i.test(branch) && manifest.allowProtectedBranch !== true) errors.push('branch must be a non-protected feature branch');
  if (!COMMIT.test(commit)) errors.push('commit must be a git hash');
  if (!artifact) {
    errors.push('artifact is required');
  } else {
    const artifactPath = String(artifact.path || '').trim();
    if (!artifactPath || FORBIDDEN_ARTIFACT.test(artifactPath)) errors.push('artifact path is not publishable');
    if (String(artifact.kind || '').toLowerCase() !== 'apk') errors.push('artifact kind must be apk');
    if (!Number.isInteger(number(artifact.bytes, 0)) || number(artifact.bytes, 0) <= 0) errors.push('artifact bytes must be positive');
    if (!SHA256.test(String(artifact.sha256 || ''))) errors.push('artifact sha256 must be 64 hex characters');
    if (schemaVersion >= 2) {
      if (String(artifact.packageName || '') !== 'com.phonebridge') errors.push('artifact package name must be com.phonebridge');
      if (String(artifact.versionName || '') !== versionName) errors.push('artifact versionName does not match Gradle metadata');
      if (number(artifact.versionCode, 0) !== versionCode) errors.push('artifact versionCode does not match Gradle metadata');
      if (artifact.signatureVerified !== true) errors.push('artifact signature was not verified');
    }
  }
  if (channel === 'release' && manifest.signed !== true) errors.push('release artifacts must be signed');
  if (channel === 'internal-debug' && manifest.signed === true && manifest.signingKeySource === 'missing') errors.push('signing key source is inconsistent');

  return { ok: errors.length === 0, errors };
}

function buildReleaseManifest({
  versionName,
  versionCode,
  branch,
  commit,
  channel = 'internal-debug',
  signed = false,
  signingKeySource = signed ? 'external-keystore' : 'none',
  allowProtectedBranch = false,
  artifactPath,
  artifactBytes,
  artifactSha256,
  artifactPackageName = 'com.phonebridge',
  artifactVersionName = versionName,
  artifactVersionCode = versionCode,
  artifactSignatureVerified = channel === 'internal-debug' || signed,
  generatedAt = new Date().toISOString(),
} = {}) {
  return {
    schemaVersion: 2,
    generatedAt,
    channel,
    versionName,
    versionCode,
    branch,
    commit,
    signed,
    signingKeySource,
    allowProtectedBranch,
    artifact: {
      kind: 'apk',
      path: artifactPath,
      bytes: artifactBytes,
      sha256: artifactSha256,
      packageName: artifactPackageName,
      versionName: artifactVersionName,
      versionCode: artifactVersionCode,
      signatureVerified: artifactSignatureVerified,
    },
    exclusions: ['access.token', 'runtime logs', 'camera frames', 'screenshots', 'signing keys'],
    rollback: {
      runtimeBackup: 'scripts/backup_runtime.ps1',
      runtimeRestore: 'scripts/restore_runtime.ps1',
      codeRollback: 'revert the batch commit; do not reset or clean user work',
    },
  };
}

function readStdin() {
  return fs.readFileSync(0, 'utf8');
}

if (require.main === module) {
  try {
    const manifest = JSON.parse(readStdin());
    const result = validateReleaseManifest(manifest);
    process.stdout.write(JSON.stringify(result));
    process.exitCode = result.ok ? 0 : 1;
  } catch (error) {
    process.stderr.write(`invalid release manifest: ${error.message}\n`);
    process.exitCode = 2;
  }
}

module.exports = { buildReleaseManifest, validateReleaseManifest };
