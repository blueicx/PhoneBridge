'use strict';

const crypto = require('node:crypto');

const FORMAT = 'phonebridge-local-privacy';
const VERSION = 1;
const KDF = 'PBKDF2-HMAC-SHA256';
const ITERATIONS = 210_000;
const CIPHER = 'AES-256-GCM';
const AAD = Buffer.from('PhoneBridge local privacy archive v1', 'utf8');
const MAX_BYTES = 32 * 1024 * 1024;

function decryptLocalPrivacyArchive(envelope, passphrase) {
  if (String(passphrase || '').length < 12 || String(passphrase).length > 1024) throw new Error('passphrase must be 12-1024 characters');
  if (!envelope || envelope.format !== FORMAT || envelope.version !== VERSION || envelope.kdf !== KDF ||
      envelope.iterations !== ITERATIONS || envelope.cipher !== CIPHER) throw new Error('unsupported local privacy archive');
  const salt = decodeBase64(envelope.salt, 16);
  const nonce = decodeBase64(envelope.nonce, 12);
  const encoded = decodeBase64(envelope.ciphertext);
  if (encoded.length < 17 || encoded.length > MAX_BYTES + 16) throw new Error('invalid local privacy archive size');
  if (!/^[a-f0-9]{64}$/i.test(String(envelope.sha256 || ''))) throw new Error('invalid local privacy archive digest');

  const key = crypto.pbkdf2Sync(String(passphrase), salt, ITERATIONS, 32, 'sha256');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, nonce);
  decipher.setAAD(AAD);
  decipher.setAuthTag(encoded.subarray(-16));
  let plaintext;
  try { plaintext = Buffer.concat([decipher.update(encoded.subarray(0, -16)), decipher.final()]); }
  catch (_) { throw new Error('local privacy archive authentication failed'); }
  if (plaintext.length > MAX_BYTES) throw new Error('local privacy archive is too large');
  const actual = crypto.createHash('sha256').update(plaintext).digest();
  const expected = Buffer.from(envelope.sha256, 'hex');
  if (!crypto.timingSafeEqual(actual, expected)) throw new Error('local privacy archive integrity check failed');
  try { JSON.parse(plaintext.toString('utf8')); }
  catch (_) { throw new Error('local privacy archive payload is invalid'); }
  return plaintext;
}

function decodeBase64(value, expectedLength = null) {
  const text = String(value || '');
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(text)) throw new Error('invalid local privacy archive encoding');
  const bytes = Buffer.from(text, 'base64');
  if (expectedLength != null && bytes.length !== expectedLength) throw new Error('invalid local privacy archive parameters');
  return bytes;
}

module.exports = { decryptLocalPrivacyArchive, FORMAT, VERSION, KDF, ITERATIONS, CIPHER };
