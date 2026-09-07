const test = require('node:test');
const assert = require('node:assert');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const http = require('http');
const { WebSocket } = require('ws');

test('PhoneBridge Authentication Flow', async (t) => {
  const testDir = path.join(__dirname, 'test-auth-runtime-' + Date.now());
  fs.mkdirSync(testDir, { recursive: true });
  const port = 19503;
  let token = 'test-token-1234';
  

  const child = spawn('node', ['index.js'], {
    cwd: __dirname,
    env: { ...process.env, PHONEBRIDGE_PORT: port, PHONEBRIDGE_RUNTIME_DIR: testDir, PHONEBRIDGE_TOKEN: token }
  });

  await new Promise(resolve => setTimeout(resolve, 1500)); // wait for server to start

  const makeRequest = (path, method = 'GET', headers = {}, body = null) => {
    return new Promise((resolve, reject) => {
      const req = http.request({
        hostname: '127.0.0.1',
        port,
        path,
        method,
        headers
      }, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
      });
      req.on('error', reject);
      if (body) req.write(body);
      req.end();
    });
  };

  await t.test('unauthenticated access to API returns 401', async () => {
    const res = await makeRequest('/api/state');
    assert.strictEqual(res.status, 401);
  });

  await t.test('unauthenticated access to media endpoints returns 401', async () => {
    let res = await makeRequest('/frame');
    assert.strictEqual(res.status, 401);
    res = await makeRequest('/audio');
    assert.strictEqual(res.status, 401);
    res = await makeRequest('/live_audio');
    assert.strictEqual(res.status, 401);
  });

  await t.test('unauthenticated access to / returns login page (200 HTML)', async () => {
    const res = await makeRequest('/');
    assert.strictEqual(res.status, 200);
    assert.match(res.body, /Mote Command Center/);
    assert.match(res.body, /<form id="loginForm">/);
  });

  await t.test('login with wrong token returns 401', async () => {
    const res = await makeRequest('/login', 'POST', { 'Content-Type': 'application/json' }, JSON.stringify({ token: 'wrong' }));
    assert.strictEqual(res.status, 401);
  });

  await t.test('login with correct token sets cookie and returns 200', async () => {
    const res = await makeRequest('/login', 'POST', { 'Content-Type': 'application/json' }, JSON.stringify({ token }));
    assert.strictEqual(res.status, 200);
    assert.match(res.headers['set-cookie'][0], /phonebridge_token=test-token-1234/);
  });

  await t.test('authenticated access to / returns main page with logout button', async () => {
    const res = await makeRequest('/', 'GET', { 'Cookie': 'phonebridge_token=' + token });
    assert.strictEqual(res.status, 200);
    assert.match(res.body, /退出/);
  });

  await t.test('logout clears cookie and returns 200', async () => {
    const res = await makeRequest('/logout', 'POST', { 'Cookie': 'phonebridge_token=' + token });
    assert.strictEqual(res.status, 200);
    assert.match(res.headers['set-cookie'][0], /phonebridge_token=;/);
  });

  await t.test('unauthenticated WebSocket connection is closed', async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/`);
    await new Promise((resolve, reject) => {
      ws.on('close', (code, reason) => {
        assert.strictEqual(code, 4401);
        assert.match(reason.toString(), /invalid token/);
        resolve();
      });
      ws.on('error', reject);
    });
  });

  await t.test('authenticated WebSocket connection succeeds', async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/?token=${token}`);
    await new Promise((resolve, reject) => {
      ws.on('open', () => {
        ws.close();
        resolve();
      });
      ws.on('error', reject);
      ws.on('close', (code) => {
        if (code === 4401) reject(new Error('Rejected token incorrectly'));
      });
    });
  });

  child.kill('SIGTERM');
  
  setTimeout(() => {
    try {
      fs.rmSync(testDir, { recursive: true, force: true });
    } catch (e) {}
  }, 500);
});
