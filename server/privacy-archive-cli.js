'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { encryptBytes, decryptBytes } = require('./privacy-center');

function readPassphrase() {
  return fs.readFileSync(0, 'utf8').replace(/\r?\n$/, '');
}

function atomicWrite(destination, contents) {
  const target = path.resolve(destination);
  const temporary = `${target}.${process.pid}.${crypto.randomUUID()}.tmp`;
  let descriptor;
  try {
    descriptor = fs.openSync(temporary, 'wx', 0o600);
    fs.writeFileSync(descriptor, contents);
    fs.closeSync(descriptor);
    descriptor = undefined;
    fs.renameSync(temporary, target);
    try { fs.chmodSync(target, 0o600); } catch (_) {}
  } finally {
    if (descriptor !== undefined) try { fs.closeSync(descriptor); } catch (_) {}
    try { fs.unlinkSync(temporary); } catch (_) {}
  }
}

function main(args) {
  const [command, source, destination] = args;
  if (!['encrypt', 'decrypt', 'verify'].includes(command) || !source || (command !== 'verify' && !destination)) {
    throw new Error('usage: privacy-archive-cli.js <encrypt|decrypt|verify> <source> [destination]');
  }
  const passphrase = readPassphrase();
  const input = fs.readFileSync(path.resolve(source));
  if (command === 'encrypt') {
    const envelope = encryptBytes(input, passphrase);
    atomicWrite(destination, Buffer.from(JSON.stringify(envelope), 'utf8'));
    return;
  }
  let envelope;
  try { envelope = JSON.parse(input.toString('utf8')); } catch (_) { throw new Error('encrypted archive is invalid'); }
  const plaintext = decryptBytes(envelope, passphrase);
  if (command === 'decrypt') atomicWrite(destination, plaintext);
  else process.stdout.write('encrypted archive verified\n');
}

try { main(process.argv.slice(2)); }
catch (error) {
  process.stderr.write(`${error.message || 'privacy archive operation failed'}\n`);
  process.exitCode = 1;
}
