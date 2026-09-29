'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { decryptLocalPrivacyArchive } = require('../server/privacy-local-archive');

async function readHiddenPassphrase() {
  if (!process.stdin.isTTY || typeof process.stdin.setRawMode !== 'function') {
    throw new Error('run this command in an interactive terminal so the passphrase can stay out of command history');
  }
  process.stdout.write('导出口令（输入不回显）：');
  process.stdin.setRawMode(true);
  process.stdin.resume();
  return new Promise((resolve, reject) => {
    let bytes = Buffer.alloc(0);
    const finish = (error = null) => {
      process.stdin.setRawMode(false);
      process.stdin.pause();
      process.stdin.off('data', onData);
      process.stdout.write('\n');
      if (error) reject(error);
      else resolve(bytes.toString('utf8'));
    };
    const onData = chunk => {
      for (const byte of chunk) {
        if (byte === 3) return finish(new Error('cancelled'));
        if (byte === 13 || byte === 10) return finish();
        if (byte === 8 || byte === 127) {
          bytes = Buffer.from(Array.from(bytes.toString('utf8')).slice(0, -1).join(''), 'utf8');
        } else if (byte >= 32) bytes = Buffer.concat([bytes, Buffer.from([byte])]);
      }
    };
    process.stdin.on('data', onData);
  });
}

async function main() {
  const [inputPath, outputPath] = process.argv.slice(2);
  if (!inputPath || !outputPath || process.argv.length !== 4) {
    throw new Error('usage: node scripts/decrypt_local_privacy_archive.js <archive.pbenc.json> <output.json>');
  }
  const input = path.resolve(inputPath);
  const output = path.resolve(outputPath);
  if (input === output) throw new Error('input and output paths must differ');
  const envelope = JSON.parse(fs.readFileSync(input, 'utf8'));
  const passphrase = await readHiddenPassphrase();
  const plaintext = decryptLocalPrivacyArchive(envelope, passphrase);
  const descriptor = fs.openSync(output, 'wx', 0o600);
  try { fs.writeFileSync(descriptor, plaintext); }
  finally { fs.closeSync(descriptor); }
  console.log(`已解密并写入：${output}`);
}

main().catch(error => {
  console.error(`解密失败：${error.message}`);
  process.exitCode = 1;
});
