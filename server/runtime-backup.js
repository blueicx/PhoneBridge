'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { RUNTIME_SCHEMA_VERSION, checksumFor, migrateState, validateEnvelope } = require('./runtime-persistence');

const PRIVATE_BACKUP_KEY = /^(?:logs|latitude|longitude|lat|lon|lng|altitude|accuracy|coordinates?|gpsCoordinates|locationCoordinates|geotag|exif|photo|photos|photoData|photoBytes|photoBase64|photoUri|rawPhoto|rawPhotoData|originalPhoto|image|images|imageData|imageBytes|imageBase64|imageUri|rawImage|rawImageData|originalImage|cameraFrame|cameraFrameData|cameraFrameBytes|frameData|frameBytes|frameBase64|dataUrl)$/i;

function removeEmbeddedPrivateCollections(value) {
  if (Array.isArray(value)) return value.map(removeEmbeddedPrivateCollections);
  if (!value || typeof value !== 'object') return value;
  const output = {};
  for (const [key, item] of Object.entries(value)) {
    if (PRIVATE_BACKUP_KEY.test(key)) continue;
    output[key] = removeEmbeddedPrivateCollections(item);
  }
  return output;
}

function sanitizeRuntimeState(input, { now = () => Date.now() } = {}) {
  const parsed = typeof input === 'string' ? JSON.parse(input) : input;
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('runtime state is not an object');
  const envelopeLike = Object.hasOwn(parsed, 'schemaVersion') || Object.hasOwn(parsed, 'checksum') ||
    (Object.hasOwn(parsed, 'savedAt') && Object.hasOwn(parsed, 'state'));
  let sourceState;
  let savedAt = null;
  if (envelopeLike) {
    const validation = validateEnvelope(parsed);
    if (!validation.ok) throw new Error(`runtime-state checksum or schema is invalid: ${validation.error}`);
    sourceState = parsed.state;
    savedAt = parsed.savedAt;
  } else {
    sourceState = parsed.state && typeof parsed.state === 'object' ? parsed.state : parsed;
  }
  const migrated = migrateState(sourceState);
  const state = removeEmbeddedPrivateCollections(migrated);
  return {
    schemaVersion: RUNTIME_SCHEMA_VERSION,
    savedAt: savedAt || new Date(now()).toISOString(),
    checksum: checksumFor(state),
    state,
  };
}

function sanitizeRuntimeFile(sourcePath, destinationPath) {
  const source = path.resolve(sourcePath);
  const destination = path.resolve(destinationPath);
  const envelope = sanitizeRuntimeState(fs.readFileSync(source, 'utf8'));
  const temporary = `${destination}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(envelope, null, 2)}\n`, { mode: 0o600 });
  try {
    fs.renameSync(temporary, destination);
    try { fs.chmodSync(destination, 0o600); } catch (_) {}
  } finally {
    try { fs.rmSync(temporary, { force: true }); } catch (_) {}
  }
}

if (require.main === module) {
  const [, , sourcePath, destinationPath] = process.argv;
  try {
    if (!sourcePath || !destinationPath) throw new Error('source and destination are required');
    sanitizeRuntimeFile(sourcePath, destinationPath);
  } catch (_) {
    process.stderr.write('runtime state backup sanitization failed\n');
    process.exitCode = 1;
  }
}

module.exports = { sanitizeRuntimeState, sanitizeRuntimeFile };
