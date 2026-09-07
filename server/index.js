const http = require('http');
const crypto = require('crypto');
const { execFile, spawn } = require('child_process');
const { WebSocketServer } = require('ws');
const { WorkspaceStore, createEventEnvelope } = require('./workspace-core');
const { DeviceHealthStore } = require('./device-health');
const { MoteStore } = require('./mote-profiles');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = process.env.PHONEBRIDGE_PORT || 9501;
const BIND_HOST = process.env.PHONEBRIDGE_BIND || '127.0.0.1';
const RUNTIME_DIR = process.env.PHONEBRIDGE_RUNTIME_DIR || __dirname;
const FRAMES_DIR = path.join(__dirname, 'frames');
const SHOTS_DIR = path.join(__dirname, 'screenshots');
for (const dir of [FRAMES_DIR, SHOTS_DIR]) fs.mkdirSync(dir, { recursive: true });
const PYTHON = 'C:/Users/blueice/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe';
const CODEX_SCRIPT = path.join(__dirname, 'codex_bridge.py');
const STT_SCRIPT = path.join(__dirname, 'stt_vosk.py');

process.on('uncaughtException', (error) => {
  try { fs.appendFileSync(path.join(__dirname, 'crash.log'), `${new Date().toISOString()} ${error.stack}\n`); } catch (_) {}
});
process.on('unhandledRejection', (error) => {
  try { fs.appendFileSync(path.join(__dirname, 'crash.log'), `${new Date().toISOString()} rejection ${error?.stack || error}\n`); } catch (_) {}
});

const latestFramePath = path.join(FRAMES_DIR, 'latest_frame.jpg');
const pendingAudioPath = path.join(FRAMES_DIR, 'pending_audio.pcm');
const liveAudioPath = path.join(FRAMES_DIR, 'live_audio.pcm');

let audioBuffer = [];
let isRecording = false;
let frameCount = 0;
let audioCount = 0;
let streamChunkCount = 0;
let startedAt = Date.now();
let phoneTelemetry = { cpu: 0, memory: 0, battery: 0, temperature: 0, network_rx: 0, network_tx: 0, updated: 0 };
let petState = {};
let codexInfo = { providers: [], tasks: [], currentProviderId: '', currentProviderName: '', currentModel: '' };
let selectedCodexTaskId = '';
let selectedCodexTaskDetail = null;
let selectedCodexTaskRefreshedAt = 0;
const chatHistory = [];
const tasks = new Map();
const logs = [];
const commandHistory = [];
let idleTimeoutMinutes = 5;
let screenOffExitMinutes = 10;
const sensorSuppressedUntil = { camera: 0, audio: 0 };
let lastInteractionAt = Date.now();
const sensors = { camera: false, audio: false };
let pttReplyQueue = Promise.resolve();
const STATE_FILE = path.join(RUNTIME_DIR, 'runtime-state.json');
const HANDOFF_FILE = path.join(RUNTIME_DIR, 'handoff.json');
const LOCK_FILE = process.env.PHONEBRIDGE_LOCK_FILE || path.join(RUNTIME_DIR, 'node.lock');
let handoffState = null;

function acquireSingletonLock() {
  for (let attempt = 0; attempt < 2; attempt++) {
    let fd;
    try {
      fd = fs.openSync(LOCK_FILE, 'wx');
      fs.writeSync(fd, String(process.pid));
      fs.closeSync(fd);
      return true;
    } catch (error) {
      if (fd) try { fs.closeSync(fd); } catch (_) {}
      if (error.code !== 'EEXIST') throw error;

      let ownerPid = 0;
      let ownerAlive = false;
      try {
        ownerPid = Number(fs.readFileSync(LOCK_FILE, 'utf8').trim());
        ownerAlive = !!ownerPid && ownerPid !== process.pid;
        if (ownerAlive) process.kill(ownerPid, 0);
      } catch (_) {
        ownerAlive = false;
      }

      let ownerAgeMs = 0;
      try { ownerAgeMs = Date.now() - fs.statSync(LOCK_FILE).mtimeMs; } catch (_) {}
      if (!ownerAlive || ownerAgeMs > 24 * 60 * 60 * 1000) {
        try { fs.unlinkSync(LOCK_FILE); } catch (_) {}
        continue;
      }

      console.log(`PhoneBridge node ${ownerPid} already owns this directory`);
      return false;
    }
  }
  return false;
}

if (!acquireSingletonLock()) process.exit(0);
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, () => {
    try { fs.unlinkSync(LOCK_FILE); } catch (_) {}
    process.exit(0);
  });
}

function normalizeHandoff(value) {
  const source = value && typeof value === 'object' ? value : {};
  return {
    goal: String(source.goal || ''),
    currentTask: String(source.currentTask || ''),
    nextSteps: String(source.nextSteps || ''),
    keyConstraints: String(source.keyConstraints || ''),
    recentDecisions: String(source.recentDecisions || ''),
    notes: String(source.notes || ''),
    revision: Math.max(0, Math.round(Number(source.revision) || 0)),
    updatedAtMs: Math.max(0, Math.round(Number(source.updatedAtMs) || 0)),
    updatedBy: String(source.updatedBy || 'pc').slice(0, 32)
  };
}

function loadHandoff() {
  if (handoffState) return handoffState;
  try {
    handoffState = normalizeHandoff(JSON.parse(fs.readFileSync(HANDOFF_FILE, 'utf8')));
  } catch (_) {
    handoffState = normalizeHandoff({});
  }
  return handoffState;
}

function saveHandoff(state) {
  handoffState = normalizeHandoff(state);
  const temp = `${HANDOFF_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(temp, JSON.stringify(handoffState, null, 2));
  try {
    fs.renameSync(temp, HANDOFF_FILE);
  } finally {
    try { fs.unlinkSync(temp); } catch (_) {}
  }
  return handoffState;
}

function newerHandoff(local, remote) {
  return remote.revision > local.revision ||
    (remote.revision === local.revision && remote.updatedAtMs > local.updatedAtMs);
}

function loadPersistentState() {
  try {
    if (!fs.existsSync(STATE_FILE)) return;
    const state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    frameCount = Number(state.frameCount) || 0;
    audioCount = Number(state.audioCount) || 0;
    streamChunkCount = Number(state.streamChunkCount) || 0;
    startedAt = Number(state.startedAt) || Date.now();
    phoneTelemetry = state.phoneTelemetry || phoneTelemetry;
    petState = state.petState || {};
    selectedCodexTaskId = state.selectedCodexTaskId || '';
    idleTimeoutMinutes = Math.min(120, Math.max(1, Number(state.idleTimeoutMinutes) || 5));
    screenOffExitMinutes = Math.min(120, Math.max(0, Math.round(Number(state.screenOffExitMinutes ?? 10))));
    lastInteractionAt = Number(state.lastInteractionAt) || Date.now();
    if (Array.isArray(state.tasks)) {
      tasks.clear();
      state.tasks.forEach(item => { if (item?.id) tasks.set(item.id, item); });
    }
    if (Array.isArray(state.logs)) logs.splice(0, logs.length, ...state.logs.slice(-300));
    if (Array.isArray(state.chatHistory)) chatHistory.splice(0, chatHistory.length, ...state.chatHistory.slice(-200));
    console.log(`Restored persistent state: ${tasks.size} tasks, ${chatHistory.length} chat messages`);
  } catch (error) {
    console.error('Persistent state restore failed:', error.message);
  }
}

function savePersistentState() {
  const payload = {
    frameCount,
    audioCount,
    streamChunkCount,
    startedAt,
    recording:false,
    phoneTelemetry,
    petState,
    selectedCodexTaskId,
    idleTimeoutMinutes,
    screenOffExitMinutes,
    lastInteractionAt,
    sensors,
    tasks: publicTasks().slice(0, 120),
    logs: publicLogs(300),
    chatHistory: chatHistory.slice(-200)
  };
  const temp = `${STATE_FILE}.${process.pid}.tmp`;
  try {
    fs.writeFileSync(temp, JSON.stringify(payload));
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        fs.renameSync(temp, STATE_FILE);
        return;
      } catch (error) {
        if (attempt === 2 || !['EPERM', 'EACCES', 'ENOENT'].includes(error.code)) throw error;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, true, (attempt + 1) * 20);
      }
    }
  } finally {
    try { fs.unlinkSync(temp); } catch (_) {}
  }
}

loadPersistentState();
loadHandoff();

function loadAccessToken() {
  if (process.env.PHONEBRIDGE_TOKEN) return process.env.PHONEBRIDGE_TOKEN;
  const tokenFile = path.join(__dirname, 'access.token');
  try {
    const existing = fs.readFileSync(tokenFile, 'utf8').trim();
    if (existing.length >= 24) return existing;
  } catch (_) {}
  const generated = crypto.randomBytes(24).toString('hex');
  fs.writeFileSync(tokenFile, `${generated}\n`, { mode: 0o600 });
  return generated;
}

function rotateAccessToken() {
  const tokenFile = path.join(__dirname, 'access.token');
  const rotated = crypto.randomBytes(24).toString('hex');
  if (process.env.PHONEBRIDGE_TOKEN) throw new Error('环境变量令牌不能在运行时轮换');
  fs.writeFileSync(tokenFile, `${rotated}\n`, { mode: 0o600 });
  ACCESS_TOKEN = rotated;
  return rotated;
}

let ACCESS_TOKEN = loadAccessToken();
const workspaceStore = new WorkspaceStore({
  journalPath: path.join(RUNTIME_DIR, 'workspace-events.jsonl'),
  snapshotPath: path.join(RUNTIME_DIR, 'workspace-state.json'),
});
const moteStore = new MoteStore({ snapshotPath: path.join(RUNTIME_DIR, 'mote-state.json') });
const deviceHealthStore = new DeviceHealthStore({
  bridge: 'disconnected',
  node: 'inactive',
  camera: 'inactive',
  microphone: 'inactive',
  authorization: 'unknown',
  outbox: 'online',
});
const proactiveState = {
  paused: false,
  quietStart: 23,
  quietEnd: 7,
  maxPerHour: 6,
  dedupeMinutes: 15,
  recent: [],
  byKey: new Map(),
};

function nowTime() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false });
}

function publicTasks() {
  return [...tasks.values()].sort((a, b) => b.createdAt - a.createdAt).slice(0, 80);
}

function publicLogs(count = 100) {
  return logs.slice(-count);
}

function broadcast(payload) {
  const text = typeof payload === 'string' ? payload : JSON.stringify(payload);
  wss.clients.forEach((socket) => {
    if (socket.readyState === socket.OPEN) socket.send(text);
  });
}

function updateDeviceHealth(patch = {}, broadcastChange = true) {
  const result = deviceHealthStore.update(patch);
  if (result.changed && broadcastChange) broadcast(deviceHealthStore.event());
  return result.state;
}

function broadcastAttention(attention) {
  if (attention) broadcast({ type: 'attention.upsert', attention });
}

function broadcastActionUpdate(actionRun) {
  if (!actionRun) return;
  const eventType = actionRun.state === 'queued' || actionRun.state === 'running' ? 'action.run' : 'action.result';
  broadcast({ type: eventType, actionRun });
}

function broadcastTaskAttention(task) {
  if (!task || !['needs_confirmation', 'succeeded', 'failed', 'cancelled'].includes(task.state)) return null;
  const attention = workspaceStore.getLatestAttentionForTask(task.id);
  broadcastAttention(attention);
  return attention;
}

function addLog(level, message) {
  const item = { time: nowTime(), level, message };
  logs.push(item);
  if (logs.length > 300) logs.splice(0, logs.length - 300);
  console.log(`[${level}] ${message}`);
  broadcast({ type: 'log', ...item });
}

function upsertTask(id, title, status, progress, detail = '') {
  const previous = tasks.get(id) || {};
  const item = {
    id,
    title,
    status,
    progress: Math.max(0, Math.min(100, Number(progress) || 0)),
    detail,
    createdAt: previous.createdAt || Date.now(),
    updatedAt: Date.now()
  };
  tasks.set(id, item);
  if (tasks.size > 120) {
    const oldestDone = [...tasks.values()]
      .filter(task => task.status === 'done' || task.status === 'error')
      .sort((a, b) => a.createdAt - b.createdAt)[0];
    if (oldestDone) tasks.delete(oldestDone.id);
    else tasks.delete(tasks.keys().next().value);
  }
  broadcast({ type: 'task', ...item });
  return item;
}

function finishTask(id, status, detail) {
  const old = tasks.get(id);
  if (!old) return;
  upsertTask(id, old.title, status, status === 'error' ? old.progress : 100, detail);
}

function appendCapped(file, data, maxBytes) {
  const currentSize = fs.existsSync(file) ? fs.statSync(file).size : 0;
  if (currentSize + data.length > maxBytes) fs.writeFileSync(file, data);
  else fs.appendFileSync(file, data);
}

function pcmFromWav(buffer) {
  if (buffer.length < 44 || buffer.toString('ascii', 0, 4) !== 'RIFF') return buffer;
  let pos = 12;
  while (pos + 8 <= buffer.length) {
    const id = buffer.toString('ascii', pos, pos + 4);
    const size = buffer.readUInt32LE(pos + 4);
    if (id === 'data') return buffer.subarray(pos + 8, Math.min(pos + 8 + size, buffer.length));
    pos += 8 + size + (size % 2);
  }
  return Buffer.alloc(0);
}

function synthesizeSpeech(text) {
  return new Promise((resolve, reject) => {
    const clean = String(text || '').trim().slice(0, 500);
    if (!clean) return reject(new Error('empty'));
    const wav = path.join(FRAMES_DIR, `tts_${Date.now()}.wav`);
    execFile(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'speak.ps1'), clean, wav],
      { timeout: 30000, windowsHide: true },
      (err) => {
        try {
          if (err) throw err;
          const pcm = pcmFromWav(fs.readFileSync(wav));
          if (!pcm.length) throw new Error('TTS produced no PCM');
          resolve(pcm);
        } catch (e) {
          reject(e);
        } finally {
          try { fs.unlinkSync(wav); } catch (_) {}
        }
      }
    );
  });
}

async function speakToPhone(text) {
  const clean = String(text || '').trim().slice(0, 500);
  const pcm = await synthesizeSpeech(clean);
  let seq = 0;
  for (let offset = 0; offset < pcm.length; offset += 4096) {
    const part = pcm.subarray(offset, Math.min(offset + 4096, pcm.length));
    const packet = Buffer.allocUnsafe(part.length + 2);
    packet[0] = 5;
    packet[1] = ++seq & 255;
    part.copy(packet, 2);
    wss.clients.forEach((socket) => {
      if (socket.readyState === socket.OPEN) socket.send(packet, { binary: true });
    });
  }
  addLog('success', `语音已发送：${clean}`);
  return { ok: true, bytes: pcm.length };
}

function transcribePcm(audioPath) {
  return new Promise((resolve, reject) => {
    execFile(
      PYTHON,
      [STT_SCRIPT, audioPath],
      { timeout: 20000, windowsHide: true, maxBuffer: 1024 * 1024 },
      (error, stdout) => {
        if (error) return reject(error);
        try {
          const result = JSON.parse(stdout.trim().split(/\r?\n/).pop() || '{}');
          if (!result.ok) throw new Error(result.error || '语音识别失败');
          resolve(String(result.text || '').trim());
        } catch (e) {
          reject(e);
        }
      }
    );
  });
}

async function processPttAudio(audioPath) {
  try {
    const text = await transcribePcm(audioPath);
    if (!text) {
      addLog('warn', 'PTT 语音识别为空');
      await speakToPhone('我没有听清，请再说一次。');
      return;
    }
    addLog('info', `PTT 语音识别：${text.slice(0, 120)}`);
    const result = await handleChat(text);
    await speakToPhone(result.reply);
  } catch (error) {
    addLog('error', `PTT 语音回复失败：${error.message}`);
    await speakToPhone('语音处理失败了，请再试一次。').catch(() => {});
  } finally {
    try { fs.unlinkSync(audioPath); } catch (_) {}
  }
}

function queuePttReply() {
  if (!fs.existsSync(pendingAudioPath)) return;
  const audioPath = path.join(FRAMES_DIR, `ptt_${Date.now()}_${Math.random().toString(36).slice(2, 7)}.pcm`);
  try {
    fs.renameSync(pendingAudioPath, audioPath);
  } catch (error) {
    addLog('error', `PTT 录音保存失败：${error.message}`);
    return;
  }
  pttReplyQueue = pttReplyQueue
    .catch(() => {})
    .then(() => processPttAudio(audioPath));
}

function runShellTask(title, file, args, options = {}) {
  const id = `task_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
  upsertTask(id, title, 'running', 8, '启动中');
  const child = spawn(file, args, { windowsHide: true, cwd: __dirname });
  let output = '';
  let lines = 0;
  const started = Date.now();
  const timer = setInterval(() => {
    const elapsed = Date.now() - started;
    const estimate = Math.min(90, 10 + Math.round(elapsed / (options.expectedMs || 1800) * 80));
    const current = tasks.get(id);
    if (current && current.status === 'running') {
      upsertTask(id, title, 'running', Math.max(current.progress, estimate), lines ? `${lines} 行输出` : '执行中');
    }
  }, 450);

  child.stdout.on('data', (data) => {
    output += data.toString();
    lines++;
    if (lines % 4 === 0) upsertTask(id, title, 'running', Math.min(90, tasks.get(id)?.progress + 3), `${lines} 行输出`);
  });
  child.stderr.on('data', (data) => { output += data.toString(); });
  child.on('error', (error) => {
    clearInterval(timer);
    finishTask(id, 'error', error.message);
    addLog('error', `${title} 失败：${error.message}`);
  });
  child.on('close', (code) => {
    clearInterval(timer);
    const clean = output.trim().slice(-6000);
    if (code === 0) {
      finishTask(id, 'done', clean || '完成');
      addLog('success', `${title} 完成`);
    } else {
      finishTask(id, 'error', clean || `exit ${code}`);
      addLog('error', `${title} 失败：exit ${code}`);
    }
    options.onClose?.(code, output, clean);
  });
  return id;
}

function screenshotTask() {
  const file = path.join(SHOTS_DIR, `screen_${Date.now()}.png`);
  const escaped = file.replace(/'/g, "''");
  const script = `Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; $b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds; $bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height; $g=[System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Location,[System.Drawing.Point]::Empty,$b.Size); $bmp.Save('${escaped}');$g.Dispose();$bmp.Dispose(); Write-Output '${escaped}'`;
  return runShellTask('截取电脑屏幕', 'powershell.exe', ['-NoProfile','-ExecutionPolicy','Bypass','-Command',script], {
    expectedMs: 1200,
    onClose(code, output) {
      if (code === 0) addLog('success', `截图保存：${output.trim().split(/\r?\n/).pop()}`);
    }
  });
}

function sendDevice(action, extra = {}) {
  if (![...wss.clients].some(ws => ws.isPhone)) {
    addLog('warn', '手机离线，指令未送达');
    return;
  }
  broadcast({ type: 'device', action, ...extra });
  const now = Date.now();
  if (action === 'camera_off') sensorSuppressedUntil.camera = now + 2000;
  if (action === 'listen_off') sensorSuppressedUntil.audio = now + 2000;
  if (action === 'camera_on' || action === 'camera_front' || action === 'camera_back') sensorSuppressedUntil.camera = 0;
  if (action === 'listen_on') sensorSuppressedUntil.audio = 0;
  if (action === 'camera_on' || action === 'camera_front' || action === 'camera_back') sensors.camera = true;
  if (action === 'camera_off') sensors.camera = false;
  if (action === 'listen_on') sensors.audio = true;
  if (action === 'listen_off') sensors.audio = false;
  addLog('info', `已发送设备指令：${action}`);
}

function registerWorkspaceTools() {
  workspaceStore.registerTool({
    id: 'device.camera',
    title: '相机控制',
    description: '打开、关闭或切换手机相机；只发送已注册的设备动作。',
    invoke: ({ action = 'camera_on' }) => {
      const allowed = new Set(['camera_on', 'camera_off', 'camera_front', 'camera_back']);
      if (!allowed.has(action)) throw new Error('未注册的相机动作');
      sendDevice(action);
      return { action, online: [...wss.clients].some(ws => ws.isPhone) };
    },
  });
  workspaceStore.registerTool({
    id: 'device.listen',
    title: '麦克风控制',
    description: '打开或关闭手机麦克风监听。',
    invoke: ({ enabled = true }) => {
      sendDevice(enabled ? 'listen_on' : 'listen_off');
      return { enabled: Boolean(enabled), online: [...wss.clients].some(ws => ws.isPhone) };
    },
  });
  workspaceStore.registerTool({
    id: 'device.telemetry',
    title: '设备状态',
    description: '读取手机电量、温度、网络和传感器状态。',
    readOnly: true,
    invoke: () => ({ telemetry: phoneTelemetry, sensors: { ...sensors } }),
  });
  workspaceStore.registerTool({
    id: 'session.memory',
    title: '会话记忆',
    description: '读取当前会话中已保存的消息。',
    readOnly: true,
    invoke: ({ sessionId }, context) => context.store.getSession(sessionId)?.messages || [],
  });
  workspaceStore.registerTool({
    id: 'task.create',
    title: '创建任务',
    description: '创建可追踪的 PhoneBridge 工作任务。',
    invoke: ({ title, detail = '', source = 'conversation' }, context) => context.store.createTask({ title, detail, source }),
  });
}

function inQuietHours() {
  const hour = new Date().getHours();
  return proactiveState.quietStart > proactiveState.quietEnd
    ? hour >= proactiveState.quietStart || hour < proactiveState.quietEnd
    : hour >= proactiveState.quietStart && hour < proactiveState.quietEnd;
}

function emitProactive(message, key = message) {
  const now = Date.now();
  const payload = { type: 'proactive', key, message: String(message).slice(0, 500), createdAt: new Date(now).toISOString() };
  const attention = workspaceStore.upsertAttentionItem({
    source: 'proactive',
    severity: 'medium',
    title: `主动提醒：${payload.message.slice(0, 36)}`,
    summary: payload.message,
    dedupeKey: String(key),
  });
  broadcastAttention(attention);
  if (proactiveState.paused || inQuietHours()) return false;
  proactiveState.recent = proactiveState.recent.filter(timestamp => now - timestamp < 60 * 60 * 1000);
  if (proactiveState.recent.length >= proactiveState.maxPerHour) return false;
  const last = proactiveState.byKey.get(key) || 0;
  if (now - last < proactiveState.dedupeMinutes * 60 * 1000) return false;
  proactiveState.byKey.set(key, now);
  proactiveState.recent.push(now);
  broadcast(payload);
  addLog('info', `Mote 主动提醒：${payload.message}`);
  return true;
}

function runWorkspaceAutomation(run) {
  const automation = workspaceStore.getAutomation(run.automationId);
  if (!automation) return;
  try {
    for (const action of automation.actions || []) {
      const toolId = String(action.toolId || action.tool || '');
      if (!toolId) continue;
      workspaceStore.invokeAutomationTool(automation.id, toolId, action.args || {}, {
        taskId: action.taskId || null,
        expiresAt: action.expiresAt || action.actionExpiresAt || null,
        confirmed: action.confirmed === true,
        onUpdate: broadcastActionUpdate,
      });
    }
    const completed = workspaceStore.completeAutomationRun(run.id, 'succeeded');
    broadcast({ type: 'workspace.automation', run: completed });
    emitProactive(`场景“${automation.name}”已完成`, `automation:${automation.id}:success`);
  } catch (error) {
    const failed = workspaceStore.completeAutomationRun(run.id, 'failed', error.message);
    broadcast({ type: 'workspace.automation', run: failed });
    emitProactive(`场景“${automation.name}”执行失败：${error.message}`, `automation:${automation.id}:failure`);
  }
}

function evaluateWorkspaceAutomations(deviceState) {
  for (const run of workspaceStore.evaluateAutomations(deviceState)) {
    broadcast({ type: 'workspace.automation', run });
    runWorkspaceAutomation(run);
  }
}

function applyWorkspaceEvent(event) {
  let payload = event.payload;
  if (typeof payload === 'string') {
    try { payload = JSON.parse(payload); } catch (_) { payload = {}; }
  }
  if (!payload || typeof payload !== 'object') return;
  if (event.type === 'workspace.message' && payload.sessionId && workspaceStore.getSession(payload.sessionId)) {
    workspaceStore.appendMessage(payload.sessionId, {
      id: payload.messageId || payload.id,
      role: payload.role || 'user',
      text: payload.text || '',
      createdAt: payload.createdAt || new Date().toISOString(),
    });
  }
  if (event.type === 'workspace.task' && payload.id) {
    const task = workspaceStore.getTask(payload.id)
      ? workspaceStore.updateTask(payload.id, payload)
      : workspaceStore.createTask(payload);
    broadcastTaskAttention(task);
  }
  if (event.type === 'device.state') {
    updateDeviceHealth(payload.state || payload);
  }
  if (event.type === 'mote.exploration' && payload.eventId && payload.clueType) {
    try { applyMoteClue(payload); } catch (_) {}
  }
}

function applyMoteClue(payload) {
  const result = moteStore.collectClue({ eventId: payload.eventId, clueType: payload.clueType });
  if (!result.duplicate) broadcastMoteState();
  return result;
}

function broadcastMoteState() {
  const state = moteStore.getState();
  broadcast({ type: 'mote.roster', roster: moteStore.roster(), state });
  broadcast({ type: 'mote.profile', profile: moteStore.roster().find(item => item.active) || null });
  broadcast({ type: 'mote.exploration', exploration: state.exploration, state });
}

function markInteraction() {
  lastInteractionAt = Date.now();
}

function idleSeconds() {
  return Math.max(0, Math.round((Date.now() - lastInteractionAt) / 1000));
}

function checkIdleTimeout() {
  const idleForMs = Date.now() - lastInteractionAt;
  if (idleForMs < idleTimeoutMinutes * 60000) return;
  if (!sensors.camera && !sensors.audio) return;
  const stopped = [];
  if (sensors.camera) {
    sendDevice('camera_off');
    stopped.push('视频');
  }
  if (sensors.audio) {
    sendDevice('listen_off');
    stopped.push('音频');
  }
  addLog('success', `空闲 ${idleTimeoutMinutes} 分钟，已自动关闭${stopped.join('/')}`);
}

function handleCommand(raw) {
  const text = String(raw || '').trim();
  if (!text) return;
  commandHistory.push({ time: nowTime(), text });
  if (commandHistory.length > 120) commandHistory.shift();
  const parts = text.replace(/^\//, '').split(/\s+/);
  const name = parts.shift()?.toLowerCase();

  switch (name) {
    case 'help':
      ['指令：status tasks ps disk net sysinfo ping <host> screenshot',
       '控制：camera on/off/front/back listen on/off say 文字 open <url>',
       '其他：clear history'].forEach(x => addLog('info', x));
      break;
    case 'status': case 'tasks':
      broadcast({ type:'snapshot', stats:statsSnapshot(), tasks:publicTasks(), logs:publicLogs(30), telemetry:phoneTelemetry });
      break;
    case 'ps':
      runShellTask('采集进程', 'powershell.exe', ['-NoProfile','-Command','Get-Process | Sort CPU -Descending | Select -First 14 Name,Id,CPU,WorkingSet | ConvertTo-Csv -NoTypeInformation'],{expectedMs:1500});
      break;
    case 'disk':
      runShellTask('磁盘状态','powershell.exe',['-NoProfile','-Command','Get-PSDrive -PSProvider FileSystem | ConvertTo-Csv -NoTypeInformation'],{expectedMs:900});
      break;
    case 'net':
      runShellTask('网络配置','ipconfig',['/all'],{expectedMs:1300});
      break;
    case 'sysinfo':
      runShellTask('系统信息','systeminfo',[],{expectedMs:3500});
      break;
    case 'ping': {
      const host = parts[0] || '8.8.8.8';
      if (!/^[\w.-]+$/.test(host)) { addLog('error','主机名不合法'); break; }
      runShellTask(`Ping ${host}`,'ping',['-n','4',host],{expectedMs:3200});
      break;
    }
    case 'screenshot': screenshotTask(); break;
    case 'say': speakToPhone(parts.join(' ')).catch(e=>addLog('error',e.message)); break;
    case 'open': {
      const url = parts[0] || '';
      if (!/^https?:\/\//i.test(url)) { addLog('error','URL 必须以 http(s):// 开头'); break; }
      runShellTask(`打开页面`,'cmd.exe',['/c','start','',url],{expectedMs:800});
      break;
    }
    case 'camera':
      sendDevice({on:'camera_on',off:'camera_off',front:'camera_front',back:'camera_back'}[parts[0]] || 'camera_on');
      break;
    case 'listen':
      sendDevice(parts[0] === 'off' ? 'listen_off' : 'listen_on');
      break;
    case 'clear': logs.length = 0; addLog('info','日志已清空'); break;
    case 'history': addLog('info', commandHistory.slice(-15).map(x=>x.text).join(' | ') || '无历史'); break;
    default: addLog('warn', `未知指令：${name}；输入 help`);
  }
}

function statsSnapshot() {
  return {
    clients: wss.clients.size,
    frames: frameCount,
    audioChunks: audioCount,
    recording: isRecording,
    streamChunks: streamChunkCount,
    uptimeMinutes: Math.round((Date.now() - startedAt) / 60000),
    idleSeconds: idleSeconds(),
    idleTimeoutMinutes,
    screenOffExitMinutes,
    sensors: { ...sensors },
    deviceHealth: deviceHealthStore.snapshot(),
    platform: `${os.type()} ${os.release()}`
  };
}

function refreshCodexInfo() {
  return new Promise((resolve) => {
    execFile(PYTHON, [CODEX_SCRIPT, 'info'], { timeout: 5000, windowsHide: true }, (error, stdout) => {
      if (error) {
        console.error('Codex info failed:', error.message);
        resolve();
        return;
      }
      try {
        codexInfo = JSON.parse(stdout);
      } catch (e) {
        console.error('Codex info parse failed:', e.message);
      }
      resolve();
    });
  });
}

function refreshSelectedCodexTask(force = false) {
  return new Promise((resolve) => {
    const taskId = String(selectedCodexTaskId || '');
    if (!taskId || (Date.now() - selectedCodexTaskRefreshedAt < 10000 && !force)) {
      resolve();
      return;
    }
    execFile(
      PYTHON,
      [CODEX_SCRIPT, 'task', taskId],
      { timeout: 8000, maxBuffer: 1024 * 1024, windowsHide: true },
      (error, stdout) => {
        if (error) {
          console.error('Codex task failed:', error.message);
          selectedCodexTaskDetail = { id: taskId, status: 'unavailable', error: error.message };
        } else {
          try {
            selectedCodexTaskDetail = JSON.parse(stdout);
            selectedCodexTaskRefreshedAt = Date.now();
          } catch (e) {
            console.error('Codex task parse failed:', e.message);
          }
        }
        resolve();
      }
    );
  });
}

function selectCodexModel(providerId, model) {
  return new Promise((resolve, reject) => {
    const args = [CODEX_SCRIPT, 'select', providerId];
    if (model) args.push(model);
    execFile(PYTHON, args, { timeout: 8000, windowsHide: true }, (error, stdout) => {
      if (error) return reject(error);
      try { resolve(JSON.parse(stdout)); } catch (e) { reject(e); }
    });
  });
}

async function chatWithModel(text, memories = [], options = {}) {
  const config = fs.readFileSync(path.join(os.homedir(), '.codex', 'config.toml'), 'utf8');
  let apiKey = '';
  try {
    apiKey = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.codex', 'auth.json'), 'utf8')).OPENAI_API_KEY || '';
  } catch (_) {}
  const baseMatch = config.match(/^base_url\s*=\s*"([^"]+)"/m);
  const modelMatch = config.match(/^model\s*=\s*"([^"]+)"/m);
  const wireMatch = config.match(/^wire_api\s*=\s*"([^"]+)"/m);
  const baseUrl = (baseMatch ? baseMatch[1] : '').replace(/\/$/, '');
  const model = String(options.model || '').trim() || (modelMatch ? modelMatch[1] : 'gpt-5');
  const wireApi = wireMatch ? wireMatch[1] : 'responses';
  if (!baseUrl || !apiKey) throw new Error('当前模型节点未配置');

  const proProfile = petState && typeof petState === 'object' ? petState.proProfile : null;
  const proActive = petState?.proMode === true && proProfile && typeof proProfile === 'object';
  const assistantName = proActive ? String(proProfile.name || 'Aria') : 'Mote';
  const activeMote = moteStore.roster().find(item => item.active) || null;
  const persona = (proActive
    ? [
        `你是 ${assistantName}，用户亲手定制的 Pro 形象助手。`,
        String(proProfile.persona || '').trim() || '性格自然、可靠、有陪伴感。',
        '回答要简短、自然；用户要求技术细节时再展开。'
      ]
    : [
        '你是 Mote，一台驻留在 Xperia 手机上的感官同伴。',
        activeMote ? `当前形态是${activeMote.name}：${activeMote.voice}；任务偏好：${activeMote.taskAffinity.join('、')}。` : '',
        '回答要简短、自然、有陪伴感；用户要求技术细节时再展开。',
        '你能够看到摄像头画面、听到麦克风、执行电脑任务，并感知手机电量和温度。'
      ]).join('\n');
  const memoryList = (Array.isArray(memories) ? memories : [])
    .map(item => String(item || '').trim())
    .filter(Boolean)
    .slice(0, 20);
  const memoryBlock = memoryList.length
    ? `\n以下是用户让 ${assistantName} 长期记住的事实与偏好，回答时优先尊重这些信息：\n${memoryList.map((item, index) => `${index + 1}. ${item}`).join('\n')}`
    : '';
  const handoff = loadHandoff();
  const handoffLines = [
    handoff.goal && `目标:${handoff.goal}`,
    handoff.currentTask && `当前任务:${handoff.currentTask}`,
    handoff.nextSteps && `下一步:${handoff.nextSteps}`,
    handoff.keyConstraints && `关键约束:${handoff.keyConstraints}`,
    handoff.recentDecisions && `近期决定:${handoff.recentDecisions}`,
    handoff.notes && `备注:${handoff.notes}`
  ].filter(Boolean);
  const handoffBlock = handoffLines.length
    ? `\n手机与电脑共享的交接上下文：\n${handoffLines.join('\n')}`
    : '';
  const history = Array.isArray(options.history) ? options.history : chatHistory.slice(-16);
  const context = [
    { role: 'system', content: persona + memoryBlock + handoffBlock },
    ...history.slice(-16).map(item => ({ role: item.role, content: item.text || item.content || '' })),
  ];
  const endpoint = baseUrl + (wireApi === 'chat' ? '/chat/completions' : '/responses');
  const streamId = `chat_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
  const body = wireApi === 'chat'
    ? { model, messages: context, stream: true }
    : { model, input: context, stream: true };
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120000),
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`HTTP ${response.status}: ${errorText.slice(0, 180)}`);
  }

  let reply = '';
  let buffer = '';
  let lastBroadcast = 0;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || '';
    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line || !line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (!data || data === '[DONE]') continue;
      try {
        const payload = JSON.parse(data);
        let delta = '';
        if (wireApi === 'chat') {
          delta = payload.choices?.[0]?.delta?.content || '';
        } else if (payload.type === 'response.output_text.delta') {
          delta = payload.delta || '';
        }
        if (!delta) continue;
        reply += delta;
        streamChunkCount++;
        const now = Date.now();
        if (now - lastBroadcast > 90) {
          lastBroadcast = now;
          broadcast({ type:'chat_delta', id:streamId, sessionId: options.sessionId || '', text:reply });
        }
      } catch (_) {}
    }
  }
  broadcast({ type:'chat_delta', id:streamId, sessionId: options.sessionId || '', text:reply });
  reply = String(reply || '').trim();
  if (!reply) throw new Error('模型返回空响应');
  return { id: streamId, reply };
}

async function handleChat(text, memories = []) {
  const clean = String(text || '').trim().slice(0, 2000);
  if (!clean) throw new Error('empty');
  chatHistory.push({ role: 'user', text: clean, time: nowTime() });
  const result = await chatWithModel(clean, memories);
  const reply = result.reply;
  chatHistory.push({ role: 'assistant', text: reply, time: nowTime() });
  broadcast({ type: 'chat', role: 'assistant', text: reply, time: nowTime() });
  addLog('success', `Mote 对话回复：${reply.slice(0, 100)}`);
  return { ok: true, reply };
}

function snapshotPayload() {
  return JSON.stringify({
    type: 'snapshot',
    stats: statsSnapshot(),
    tasks: publicTasks(),
    logs: publicLogs(80),
    telemetry: phoneTelemetry,
    pet: petState,
    handoff: loadHandoff(),
    codex: {
      ...codexInfo,
      selectedTaskId: selectedCodexTaskId,
      selectedTask: selectedCodexTaskDetail,
    },
    chat: chatHistory.slice(-50),
    deviceHealth: deviceHealthStore.snapshot(),
    workspace: workspaceSnapshot(),
    motes: { state: moteStore.getState(), roster: moteStore.roster() },
    autonomy: workspaceStore.getAutonomyPolicy(),
  });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => { body += chunk; if (body.length > 1_000_000) reject(new Error('too large')); });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

function requestHasAccess(req) {
  if (!ACCESS_TOKEN) return true;
  const url = new URL(req.url, 'http://localhost');
  const provided = req.headers['x-phonebridge-token']
    || String(req.headers.authorization || '').replace(/^Bearer\s+/i, '')
    || url.searchParams.get('token')
    || (req.headers.cookie && req.headers.cookie.match(/phonebridge_token=([^;]+)/)?.[1])
    || '';
  return provided === ACCESS_TOKEN;
}

function denyAccess(res) {
  res.writeHead(401, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ ok: false, error: '访问令牌缺失或无效' }));
}

function sendJson(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(payload));
}

async function readJson(req) {
  const body = await readBody(req);
  try { return JSON.parse(body || '{}'); } catch (_) { throw new Error('请求 JSON 无效'); }
}

function workspaceSnapshot() {
  return {
    sessions: workspaceStore.listSessions(),
    tools: workspaceStore.listTools(),
    tasks: workspaceStore.listTasks(),
    automations: workspaceStore.listAutomations(),
    automationRuns: workspaceStore.automationRuns().slice(-100),
    attention: workspaceStore.listAttentionItems().slice(0, 100),
    policies: workspaceStore.listPolicies(),
    actionRuns: workspaceStore.listActionRuns().slice(0, 100),
    audit: workspaceStore.auditLog().slice(-100),
    emergencyStop: workspaceStore.emergencyStopState(),
    autonomy: workspaceStore.getAutonomyPolicy(),
    motes: { state: moteStore.getState(), roster: moteStore.roster() },
  };
}

async function runWorkspaceMessage(sessionId, message, taskId, memories) {
  const session = workspaceStore.getSession(sessionId);
  try {
    workspaceStore.updateTask(taskId, { state: 'running', progress: 10, log: '已提交模型调用' });
    const result = await chatWithModel(message.text, memories, {
      model: session.model,
      sessionId,
      history: session.messages,
    });
    const assistant = workspaceStore.appendMessage(sessionId, { role: 'assistant', text: result.reply, streamId: result.id });
    const task = workspaceStore.updateTask(taskId, { state: 'succeeded', progress: 100, log: '模型结果已归档', artifactRefs: [assistant.id] });
    broadcast({ type: 'workspace.message', sessionId, message: assistant });
    broadcast({ type: 'workspace.task', task });
    broadcastTaskAttention(task);
    emitProactive(`会话任务已完成：${message.text.slice(0, 48)}`, `task:${taskId}:success`);
  } catch (error) {
    const task = workspaceStore.updateTask(taskId, { state: 'failed', error: error.message, log: '模型调用失败' });
    broadcast({ type: 'workspace.task', task });
    broadcastTaskAttention(task);
    emitProactive(`会话任务失败：${error.message}`, `task:${taskId}:failure`);
    addLog('error', `AI 空间会话失败：${error.message}`);
  }
}

function getLoginHtml() {
  const uptime = Math.max(0, Math.round((Date.now() - startedAt) / 60000));
  const clientCount = wss ? wss.clients.size : 0;
  const statusBadge = clientCount > 0 ? '🟢 设备已连接' : '⚪ 设备待连接';
  const uptimeStr = uptime >= 60 ? `${Math.floor(uptime / 60)}小时${uptime % 60}分` : `${uptime}分钟`;

  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>登录 · Mote</title>
<style>
:root{--bg:#07100D;--panel:#101E18;--mint:#8FF0C4;--text:#EAF7F1;--muted:#88A296;--card:#0C1712;--border:#24352C;}
body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;}
.login-box{background:linear-gradient(160deg,#101e18,#0a1511);border:1px solid var(--border);border-radius:18px;padding:32px;width:100%;max-width:380px;text-align:center;box-shadow:0 8px 32px rgba(0,0,0,0.4);}
h1{margin:0 0 12px;font-size:24px;color:#F2F7F2;}
.badge-row{display:flex;justify-content:center;gap:8px;margin-bottom:18px;font-size:12px;}
.badge{background:#162B22;border:1px solid #2B4E3E;color:var(--mint);padding:3px 10px;border-radius:12px;}
.runtime-info{background:var(--card);border:1px solid #1E3328;border-radius:10px;padding:10px 14px;margin-bottom:20px;text-align:left;font-size:12px;color:var(--muted);display:grid;grid-template-columns:1fr 1fr;gap:6px;}
.runtime-info span b{color:var(--text);font-weight:normal;}
input{width:100%;box-sizing:border-box;background:#0c1712;border:1px solid #2c483c;color:#effaf4;border-radius:10px;padding:12px;font:inherit;margin-bottom:16px;}
button{width:100%;background:#183326;border:1px solid #40705b;color:#ffdfa3;border-radius:10px;padding:12px;font:inherit;cursor:pointer;font-weight:bold;}
button:hover{background:#204432;}
.error{color:#FF6B6B;font-size:12px;margin-bottom:16px;min-height:18px;}
</style>
<div class="login-box">
  <h1>Mote Command Center</h1>
  <div class="badge-row">
    <span class="badge">Node: :${PORT}</span>
    <span class="badge">${statusBadge}</span>
  </div>
  <div class="runtime-info">
    <span>节点端口: <b>${PORT}</b></span>
    <span>运行时间: <b>${uptimeStr}</b></span>
    <span>连接客户端: <b>${clientCount}</b></span>
    <span>服务状态: <b>正常待命</b></span>
  </div>
  <div class="error" id="errorMsg"></div>
  <form id="loginForm">
    <input type="password" id="token" placeholder="请输入访问令牌" required>
    <button type="submit">登 录</button>
  </form>
</div>
<script>
document.getElementById('loginForm').onsubmit = async (e) => {
  e.preventDefault();
  const token = document.getElementById('token').value;
  const res = await fetch('/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token })
  });
  if (res.ok) location.reload();
  else {
    const data = await res.json();
    document.getElementById('errorMsg').textContent = data.error || '登录失败';
  }
};
</script>`;
}

const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Mote · PhoneBridge</title>
<style>
:root{--bg:#07100D;--panel:#101E18;--line:#28453A;--mint:#8FF0C4;--amber:#FFC86B;--coral:#FF6B6B;--text:#EAF7F1;--muted:#88A296}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
.wrap{max-width:1280px;margin:auto;padding:22px}.top{display:flex;align-items:center;gap:18px}.logo{font-size:32px;font-weight:800;color:#F2F7F2}.sub{color:var(--muted);font-size:12px}.pill{border:1px solid var(--line);background:#0d1b15;border-radius:99px;padding:7px 13px;font-size:12px}
.grid{display:grid;grid-template-columns:minmax(280px,380px) minmax(340px,1fr);gap:16px;margin-top:18px}.panel{background:linear-gradient(160deg,#101e18,#0a1511);border:1px solid #24352c;border-radius:18px;padding:16px}.panel h2{font-size:15px;margin:0 0 12px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.metric{background:#0d1913;border-radius:12px;padding:11px}.metric b{display:block;color:var(--mint)}.metric span{font-size:11px;color:var(--muted)}
.tabs{display:flex;gap:6px;margin-bottom:10px}.tabs button{flex:1;background:#10201a;color:#d8f5e7;border:1px solid #2c483c;border-radius:9px;height:33px;cursor:pointer}.tabs button.active{background:#183326;color:var(--amber)}
#log,#tasks,#sensors{height:min(48vh,430px);overflow:auto;padding-right:6px}.item{border-left:2px solid #33584a;padding:6px 9px;margin-bottom:6px;background:#081209;white-space:pre-wrap}.taskbar{height:4px;background:#20342b;margin-top:5px}.taskbar i{display:block;height:100%;background:var(--mint)}.row{display:flex;gap:8px;margin-top:10px}input,button,select{background:#0c1712;border:1px solid #2c483c;color:#effaf4;border-radius:10px;padding:9px;font:inherit}button{cursor:pointer}button.primary{background:#183326;border-color:#40705b;color:#ffdfa3}#frame{width:100%;aspect-ratio:3/2;object-fit:cover;border-radius:16px;border:2px solid var(--mint)}textarea{width:100%;min-height:110px;background:#081209;color:#cde8da;border:1px solid #24352c;border-radius:12px;padding:10px;font:12px ui-monospace}
@media(max-width:850px){.grid{grid-template-columns:1fr}}
</style><div class="wrap"><div class="top"><div><div class="logo">Mote</div><div class="sub">PhoneBridge · sensory familiar</div></div><div style="margin-left:auto;display:flex;gap:12px;align-items:center"><div class="pill" id="status">loading</div><button onclick="logout()" style="padding:4px 12px;font-size:12px;background:#0d1b15">退出</button></div></div>
<div class="grid"><div class="panel"><h2>实时感官</h2><img id="frame"><div class="metrics" style="margin-top:12px"><div class="metric"><b id="cpu">-</b><span>手机 CPU</span></div><div class="metric"><b id="mem">-</b><span>内存</span></div><div class="metric"><b id="bat">-</b><span>电量</span></div><div class="metric"><b id="temp">-</b><span>温度</span></div></div><div class="row"><button class="primary" onclick="device('camera_on')">开眼</button><button onclick="device('camera_front')">前眼</button><button onclick="device('camera_back')">后眼</button><button onclick="device('listen_on')">监听</button><button onclick="say()">说话</button></div><div class="row"><input id="speech" placeholder="输入要在手机上播放的话" style="flex:1"></div><div class=row><select id=idleTimeout title="空闲断流时间"><option value=1>1 分钟</option><option value=3>3 分钟</option><option value=5 selected>5 分钟</option><option value=10>10 分钟</option><option value=30>30 分钟</option></select><button onclick=setIdleTimeout()>空闲断流</button></div><div class=row><select id=screenOffTimeout title="息屏自动退出时间"><option value=0>不自动退出</option><option value=1>1 分钟</option><option value=3>3 分钟</option><option value=5>5 分钟</option><option value=10 selected>10 分钟</option><option value=30>30 分钟</option><option value=60>60 分钟</option></select><button onclick=setScreenOffTimeout()>息屏退出</button></div></div>
<div class="panel"><h2>指挥台</h2><div class="tabs"><button class="active" data-tab="tasks">任务</button><button data-tab="log">日志</button><button data-tab="sensors">传感器</button><button data-tab="frame">画面</button></div><div id="tasks"></div><div id="log" hidden></div><div id="sensors" hidden></div><div id="framebox" hidden><img id="frame2"></div><div class="row"><input id="cmd" placeholder="help / ping 8.8.8.8 / screenshot / ps / say 你好" style="flex:1"><button class="primary" onclick="sendCmd()">执行</button></div><textarea id="detail" readonly placeholder="选中任务的输出会出现在这里"></textarea></div></div>
<div class="panel" style="grid-column:1/-1"><h2>工作台 · Mote 图鉴 · 自治</h2><div id="workspaceSummary" class="sub">加载中…</div><div id="moteRoster" class="row" style="flex-wrap:wrap"></div><div class="row"><button class="primary" onclick="stopAutonomy()">Emergency Stop</button><button onclick="refreshWorkspace()">刷新工作台</button></div></div>
<script>
let selected='';
function esc(s){return String(s??'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
function sel(id){selected=id;let t=(window.TASKS||{})[id];detail.value=t?t.detail:''}
function tab(name,b){document.querySelectorAll('.tabs button').forEach(x=>x.classList.remove('active'));b.classList.add('active');['tasks','log','sensors','frame'].forEach(x=>document.getElementById(x).hidden=x!==name)}
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>tab(b.dataset.tab,b));

async function api(p,o){
  let response=await fetch(p,o);
  if(response.status===401) location.reload();
  if(!response.ok) throw new Error('HTTP ' + response.status);
  return response.json();
}
async function logout(){
  await fetch('/logout', { method: 'POST' });
  location.reload();
}
async function sendCmd(){let c=cmd.value.trim();if(!c)return;await api('/api/command',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({text:c})});cmd.value='';refresh()}
async function device(a){await api('/api/device',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action:a})})}
async function say(){let t=speech.value.trim();if(t)await api('/say?text='+encodeURIComponent(t))}
setInterval(()=>{frame.src='/frame?'+Date.now();frame2.src='/frame?'+Date.now()},140);
setInterval(refresh,1000);setInterval(poll,220);
async function refresh(){
  try{
    let s=await api('/api/state');
    const sensor=s.stats.sensors||{};
    status.textContent='手机 ' + s.stats.clients + ' · 帧 ' + s.stats.frames + ' · 音频 ' + s.stats.audioChunks + ' · 空闲 ' + s.stats.idleSeconds + 's';
    tasks.innerHTML=s.tasks.map(t=>'<div class=item onclick=\\"sel(\\''+t.id+'\\')\\"><b>'+esc(t.title)+'</b> '+t.status+' '+t.progress+'%<div class=taskbar><i style=width:'+t.progress+'%></i><pre>'+esc(t.detail.slice(-500))+'</pre></div>').join('')||'<div class=item>暂无任务</div>';
    log.innerHTML=s.logs.map(x=>'<div class=item><small>'+x.time+'</small> '+esc(x.message)).join('');
    sensors.textContent=JSON.stringify({telemetry:s.telemetry,idleTimeoutMinutes:s.stats.idleTimeoutMinutes,screenOffExitMinutes:s.stats.screenOffExitMinutes,sensor},null,2);
    cpu.textContent=s.telemetry.cpu+'%';mem.textContent=s.telemetry.memory+'%';bat.textContent=s.telemetry.battery+'%';temp.textContent=s.telemetry.temperature+'°C';
    idleTimeout.value=String(s.stats.idleTimeoutMinutes);
    screenOffTimeout.value=String(s.stats.screenOffExitMinutes);
    if(!selected&&s.tasks[0])sel(s.tasks[0].id);
  }catch(e){if(String(e.message).includes('HTTP 401'))status.textContent='令牌无效'}
}
async function poll(){
  try{
    let s=await api('/api/state');
    window.TASKS={};s.tasks.forEach(x=>TASKS[x.id]=x);
    if(selected&&TASKS[selected]){detail.value=TASKS[selected].detail||''}
  }catch(e){}
}
async function setIdleTimeout(){
  await api('/api/idle-timeout',{method:'POST',headers:{'content-type':'application/json'},body:'{"minutes":'+idleTimeout.value+'}'});
  refresh();
}
async function setScreenOffTimeout(){
  await api('/api/screen-off-timeout',{method:'POST',headers:{'content-type':'application/json'},body:'{"minutes":'+screenOffTimeout.value+'}'});
  refresh();
}
async function taskAction(id,patch){await api('/api/tasks/'+encodeURIComponent(id),{method:'PATCH',headers:{'content-type':'application/json'},body:JSON.stringify(patch)});refreshWorkspace()}
async function activateMote(id){await api('/api/motes/active',{method:'PATCH',headers:{'content-type':'application/json'},body:JSON.stringify({id})});refreshWorkspace()}
async function chooseMote(id){await api('/api/motes/exploration',{method:'PATCH',headers:{'content-type':'application/json'},body:JSON.stringify({targetId:id})});refreshWorkspace()}
async function stopAutonomy(){await api('/api/tools/emergency-stop',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({reason:'web'})});refreshWorkspace()}
async function refreshWorkspace(){try{let s=await api('/api/state'),w=s.workspace||{},p=s.autonomy||w.autonomy||{};workspaceSummary.textContent='自治：'+(p.level||'-')+' · 白名单 '+(p.allowedTools||[]).length+' 项 · 急停 '+(w.emergencyStop?.active?'已启用':'未启用')+' · 任务 '+(w.tasks||[]).length+' 个';
  const tasksHtml=(w.tasks||[]).slice(0,10).map(t=>'<div class=item><b>'+esc(t.title)+'</b> · '+esc(t.state)+' · '+t.progress+'% <button onclick="taskAction(\''+esc(t.id)+'\',{state:\'paused\'})">暂停</button> <button onclick="taskAction(\''+esc(t.id)+'\',{state:\'running\'})">继续</button> <button onclick="taskAction(\''+esc(t.id)+'\',{retry:true})">重试</button> <button onclick="taskAction(\''+esc(t.id)+'\',{state:\'cancelled\'})">取消</button> <button onclick="taskAction(\''+esc(t.id)+'\',{state:\'archived\'})">归档</button></div>').join('');
  const roster=(s.motes?.roster||w.motes?.roster||[]).map(m=>'<button '+(m.unlocked?'':'disabled')+' class="'+(m.active?'primary':'')+'" onclick="activateMote(\''+m.id+'\')">'+esc(m.name)+(m.unlocked?'':' 🔒')+'</button>').join('');
  moteRoster.innerHTML=tasksHtml+'<div style="width:100%;margin-top:8px">'+roster+'</div>'; }catch(e){workspaceSummary.textContent='工作台暂不可用'}}
refreshWorkspace();
</script>`;

const server = http.createServer(async (req, res) => {
  res.setHeader('Vary', 'Origin');
  if (req.headers.origin && req.headers.origin === `http://${req.headers.host}`) {
    res.setHeader('Access-Control-Allow-Origin', req.headers.origin);
  }
  res.setHeader('Access-Control-Allow-Headers', 'content-type, x-phonebridge-token, authorization');
  const parsedUrl = new URL(req.url, 'http://localhost');

  try {
    if (parsedUrl.pathname === '/login' && req.method === 'POST') {
      const body = await readBody(req);
      const payload = JSON.parse(body || '{}');
      if (payload.token === ACCESS_TOKEN) {
        res.writeHead(200, {
          'Content-Type': 'application/json',
          'Set-Cookie': `phonebridge_token=${ACCESS_TOKEN}; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000`
        });
        return res.end(JSON.stringify({ ok: true }));
      } else {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ ok: false, error: '令牌错误' }));
      }
    }
    if (parsedUrl.pathname === '/logout' && req.method === 'POST') {
      res.writeHead(200, {
        'Content-Type': 'application/json',
        'Set-Cookie': `phonebridge_token=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`
      });
      return res.end(JSON.stringify({ ok: true }));
    }

    if (!requestHasAccess(req)) {
      if (parsedUrl.pathname === '/') {
        res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
        return res.end(getLoginHtml());
      }
      denyAccess(res);
      return;
    }

    if (parsedUrl.pathname === '/api/workspace' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, ...workspaceSnapshot() });
    }
    if (parsedUrl.pathname === '/api/device/health' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, health: deviceHealthStore.snapshot() });
    }
    if (parsedUrl.pathname === '/api/proactive' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, paused: proactiveState.paused, quietStart: proactiveState.quietStart, quietEnd: proactiveState.quietEnd, maxPerHour: proactiveState.maxPerHour, dedupeMinutes: proactiveState.dedupeMinutes });
    }
    if (parsedUrl.pathname === '/api/proactive' && req.method === 'PATCH') {
      const payload = await readJson(req);
      if (payload.paused !== undefined) proactiveState.paused = Boolean(payload.paused);
      if (payload.quietStart !== undefined) proactiveState.quietStart = Math.max(0, Math.min(23, Math.round(Number(payload.quietStart))));
      if (payload.quietEnd !== undefined) proactiveState.quietEnd = Math.max(0, Math.min(23, Math.round(Number(payload.quietEnd))));
      if (payload.maxPerHour !== undefined) proactiveState.maxPerHour = Math.max(0, Math.min(60, Math.round(Number(payload.maxPerHour))));
      if (payload.dedupeMinutes !== undefined) proactiveState.dedupeMinutes = Math.max(0, Math.min(1440, Math.round(Number(payload.dedupeMinutes))));
      return sendJson(res, 200, { ok: true, paused: proactiveState.paused, quietStart: proactiveState.quietStart, quietEnd: proactiveState.quietEnd, maxPerHour: proactiveState.maxPerHour, dedupeMinutes: proactiveState.dedupeMinutes });
    }
    if (parsedUrl.pathname === '/api/autonomy' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, policy: workspaceStore.getAutonomyPolicy(), emergencyStop: workspaceStore.emergencyStopState() });
    }
    if (parsedUrl.pathname === '/api/autonomy' && req.method === 'PATCH') {
      try {
        const policy = workspaceStore.updateAutonomyPolicy(await readJson(req));
        broadcast({ type: 'workspace.policy', scope: 'global', policy });
        return sendJson(res, 200, { ok: true, policy });
      } catch (error) {
        return sendJson(res, 400, { ok: false, error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/motes' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, state: moteStore.getState(), roster: moteStore.roster() });
    }
    if (parsedUrl.pathname === '/api/motes/active' && req.method === 'PATCH') {
      try {
        const state = moteStore.setActive((await readJson(req)).id);
        broadcastMoteState();
        return sendJson(res, 200, { ok: true, state, profile: moteStore.roster().find(item => item.active) });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/motes/exploration' && req.method === 'PATCH') {
      try {
        const state = moteStore.setExplorationTarget((await readJson(req)).targetId);
        broadcastMoteState();
        return sendJson(res, 200, { ok: true, state });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/motes/exploration/clues' && req.method === 'POST') {
      try {
        const result = applyMoteClue(await readJson(req));
        return sendJson(res, result.duplicate ? 200 : 201, { ok: true, ...result });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/auth/rotate' && req.method === 'POST') {
      try {
        const token = rotateAccessToken();
        addLog('warn', '节点访问令牌已轮换，旧令牌立即失效');
        return sendJson(res, 200, { ok: true, token });
      } catch (error) {
        return sendJson(res, 409, { ok: false, error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/workspace/events' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, events: workspaceStore.events() });
    }
    if (parsedUrl.pathname === '/api/workspace/events' && req.method === 'POST') {
      const payload = await readJson(req);
      const event = payload.event || payload;
      const accepted = workspaceStore.acceptEvent(event && event.type
        ? event
        : createEventEnvelope({ origin: String(payload.origin || 'phone'), sequence: Number(payload.sequence || 0), type: String(payload.type || 'workspace.event'), payload: payload.payload || payload }));
      if (accepted.accepted) applyWorkspaceEvent(accepted.event);
      broadcast({ type: 'workspace.event', event: accepted.event });
      return sendJson(res, accepted.accepted ? 202 : 200, { ok: true, ...accepted });
    }
    if (parsedUrl.pathname === '/api/workspace/providers' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, providers: codexInfo.providers || [], currentProviderId: codexInfo.currentProviderId, currentProviderName: codexInfo.currentProviderName, currentModel: codexInfo.currentModel });
    }
    if (parsedUrl.pathname === '/api/workspace/sessions' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, sessions: workspaceStore.listSessions() });
    }
    if (parsedUrl.pathname === '/api/workspace/sessions' && req.method === 'POST') {
      const session = workspaceStore.createSession(await readJson(req));
      broadcast({ type: 'workspace.session', session });
      return sendJson(res, 201, { ok: true, session });
    }
    const sessionMatch = parsedUrl.pathname.match(/^\/api\/workspace\/sessions\/([^/]+)(?:\/(messages|authorize|revoke|policy))?$/);
    if (sessionMatch) {
      const sessionId = decodeURIComponent(sessionMatch[1]);
      const suffix = sessionMatch[2] || '';
      if (!workspaceStore.getSession(sessionId)) return sendJson(res, 404, { ok: false, error: 'session not found' });
      if (suffix === 'messages' && req.method === 'POST') {
        const payload = await readJson(req);
        const message = workspaceStore.appendMessage(sessionId, payload);
        const task = workspaceStore.createTask({ source: 'conversation', title: `会话：${String(message.text).slice(0, 36)}`, detail: message.text, metadata: { sessionId, messageId: message.id } });
        broadcast({ type: 'workspace.message', sessionId, message });
        broadcast({ type: 'workspace.task', task });
        if (payload.runModel !== false && message.role === 'user') {
          runWorkspaceMessage(sessionId, message, task.id, Array.isArray(payload.memories) ? payload.memories : []).catch(() => {});
        }
        return sendJson(res, 202, { ok: true, message, task });
      }
      if (suffix === 'messages' && req.method === 'GET') {
        return sendJson(res, 200, { ok: true, messages: workspaceStore.getSession(sessionId).messages });
      }
      if (suffix === 'authorize' && req.method === 'POST') {
        const payload = await readJson(req);
        const authorization = workspaceStore.armSession(sessionId, Number(payload.durationMs) || 15 * 60 * 1000);
        addLog('info', `AI 工具会话已授权：${sessionId}`);
        return sendJson(res, 200, { ok: true, authorization });
      }
      if (suffix === 'revoke' && req.method === 'POST') {
        workspaceStore.revokeSession(sessionId);
        addLog('info', `AI 工具会话已撤销：${sessionId}`);
        return sendJson(res, 200, { ok: true, session: workspaceStore.getSession(sessionId) });
      }
      if (suffix === 'policy' && req.method === 'GET') {
        return sendJson(res, 200, { ok: true, policy: workspaceStore.getSessionPolicy(sessionId) });
      }
      if (suffix === 'policy' && req.method === 'PATCH') {
        const policy = workspaceStore.updateSessionPolicy(sessionId, await readJson(req));
        broadcast({ type: 'workspace.policy', scope: 'session', policy });
        return sendJson(res, 200, { ok: true, policy });
      }
      if (!suffix && req.method === 'GET') return sendJson(res, 200, { ok: true, session: workspaceStore.getSession(sessionId) });
      if (!suffix && req.method === 'PATCH') return sendJson(res, 200, { ok: true, session: workspaceStore.updateSession(sessionId, await readJson(req)) });
    }
    if (parsedUrl.pathname === '/api/tools' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, tools: workspaceStore.listTools(), emergencyStop: workspaceStore.emergencyStopState() });
    }
    if (parsedUrl.pathname === '/api/tools/invoke' && req.method === 'POST') {
      const payload = await readJson(req);
      try {
        const result = workspaceStore.invokeTool(String(payload.sessionId || ''), String(payload.toolId || ''), payload.args || {}, {
          taskId: payload.taskId || null,
          expiresAt: payload.expiresAt || null,
          confirmed: payload.confirmed === true,
          onUpdate: broadcastActionUpdate,
        });
        const linkedTask = result.actionRun?.taskId ? workspaceStore.getTask(result.actionRun.taskId) : null;
        if (linkedTask) broadcast({ type: 'workspace.task', task: linkedTask });
        addLog('info', `AI 工具调用：${payload.toolId}`);
        return sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        const status = /authorization|expired|emergency|policy|confirmation|disabled/i.test(error.message) ? 403 : 400;
        return sendJson(res, status, {
          ok: false,
          error: error.message,
          actionRunId: error.actionRunId || null,
          actionRun: error.actionRun || null,
        });
      }
    }
    if (parsedUrl.pathname === '/api/tools/emergency-stop' && req.method === 'POST') {
      const payload = await readJson(req);
      const state = workspaceStore.emergencyStop(String(payload.reason || 'manual'));
      addLog('warn', 'AI 工具急停已启用');
      broadcast({ type: 'workspace.emergency_stop', state });
      return sendJson(res, 200, { ok: true, state });
    }
    if (parsedUrl.pathname === '/api/tools/emergency-stop/clear' && req.method === 'POST') {
      const state = workspaceStore.clearEmergencyStop();
      broadcast({ type: 'workspace.emergency_stop', state });
      return sendJson(res, 200, { ok: true, state });
    }
    if (parsedUrl.pathname === '/api/tasks' && req.method === 'GET') return sendJson(res, 200, { ok: true, tasks: workspaceStore.listTasks() });
    if (parsedUrl.pathname === '/api/tasks' && req.method === 'POST') {
      const task = workspaceStore.createTask(await readJson(req));
      broadcast({ type: 'workspace.task', task });
      return sendJson(res, 201, { ok: true, task });
    }
    const taskMatch = parsedUrl.pathname.match(/^\/api\/tasks\/([^/]+)$/);
    if (taskMatch) {
      const taskId = decodeURIComponent(taskMatch[1]);
      if (req.method === 'GET') return sendJson(res, workspaceStore.getTask(taskId) ? 200 : 404, { ok: Boolean(workspaceStore.getTask(taskId)), task: workspaceStore.getTask(taskId) });
      if (req.method === 'PATCH') {
        const task = workspaceStore.updateTask(taskId, await readJson(req));
        broadcast({ type: 'workspace.task', task });
        broadcastTaskAttention(task);
        return sendJson(res, 200, { ok: true, task });
      }
    }
    if (parsedUrl.pathname === '/api/attention' && req.method === 'GET') {
      const status = parsedUrl.searchParams.get('status') || '';
      return sendJson(res, 200, { ok: true, attention: workspaceStore.listAttentionItems(status ? { status } : {}) });
    }
    if (parsedUrl.pathname === '/api/attention' && req.method === 'PATCH') {
      const payload = await readJson(req);
      if (!payload.id) return sendJson(res, 400, { ok: false, error: 'attention id is required' });
      const attention = workspaceStore.updateAttentionItem(String(payload.id), payload);
      broadcastAttention(attention);
      return sendJson(res, 200, { ok: true, attention });
    }
    const attentionMatch = parsedUrl.pathname.match(/^\/api\/attention\/([^/]+)$/);
    if (attentionMatch) {
      const attentionId = decodeURIComponent(attentionMatch[1]);
      if (req.method === 'GET') {
        const attention = workspaceStore.getAttentionItem(attentionId);
        return sendJson(res, attention ? 200 : 404, { ok: Boolean(attention), attention });
      }
      if (req.method === 'PATCH') {
        const attention = workspaceStore.updateAttentionItem(attentionId, await readJson(req));
        broadcastAttention(attention);
        return sendJson(res, 200, { ok: true, attention });
      }
    }
    if (parsedUrl.pathname === '/api/action-runs' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, actionRuns: workspaceStore.listActionRuns() });
    }
    const actionRunMatch = parsedUrl.pathname.match(/^\/api\/action-runs\/([^/]+)$/);
    if (actionRunMatch) {
      const actionRunId = decodeURIComponent(actionRunMatch[1]);
      if (req.method === 'GET') {
        const actionRun = workspaceStore.getActionRun(actionRunId);
        return sendJson(res, actionRun ? 200 : 404, { ok: Boolean(actionRun), actionRun });
      }
      if (req.method === 'PATCH') {
        const actionRun = workspaceStore.updateActionRun(actionRunId, await readJson(req));
        broadcastActionUpdate(actionRun);
        return sendJson(res, 200, { ok: true, actionRun });
      }
    }
    if (parsedUrl.pathname === '/api/automations' && req.method === 'GET') return sendJson(res, 200, { ok: true, automations: workspaceStore.listAutomations(), runs: workspaceStore.automationRuns().slice(-100) });
    if (parsedUrl.pathname === '/api/automations' && req.method === 'POST') {
      const automation = workspaceStore.createAutomation(await readJson(req));
      return sendJson(res, 201, { ok: true, automation });
    }
    const automationMatch = parsedUrl.pathname.match(/^\/api\/automations\/([^/]+)(?:\/(run|runs|policy))?$/);
    if (automationMatch) {
      const automationId = decodeURIComponent(automationMatch[1]);
      if (automationMatch[2] === 'run' && req.method === 'POST') {
        const run = workspaceStore.runAutomation(automationId, { source: 'manual' });
        if (run) {
          broadcast({ type: 'workspace.automation', run });
          runWorkspaceAutomation(run);
        }
        return sendJson(res, 202, { ok: true, run });
      }
      if (automationMatch[2] === 'runs' && req.method === 'GET') return sendJson(res, 200, { ok: true, runs: workspaceStore.automationRuns().filter(run => run.automationId === automationId) });
      if (automationMatch[2] === 'policy' && req.method === 'GET') return sendJson(res, 200, { ok: true, policy: workspaceStore.getAutomationPolicy(automationId) });
      if (automationMatch[2] === 'policy' && req.method === 'PATCH') {
        const policy = workspaceStore.updateAutomationPolicy(automationId, await readJson(req));
        broadcast({ type: 'workspace.policy', scope: 'automation', policy });
        return sendJson(res, 200, { ok: true, policy });
      }
      if (!automationMatch[2] && req.method === 'PATCH') return sendJson(res, 200, { ok: true, automation: workspaceStore.updateAutomation(automationId, await readJson(req)) });
    }
    if (parsedUrl.pathname === '/api/state') {
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({stats:statsSnapshot(),deviceHealth:deviceHealthStore.snapshot(),tasks:publicTasks(),logs:publicLogs(),telemetry:phoneTelemetry,pet:petState,codex:{...codexInfo,selectedTaskId:selectedCodexTaskId},chat:chatHistory.slice(-50),workspace:workspaceSnapshot(),motes:{state:moteStore.getState(),roster:moteStore.roster()},autonomy:workspaceStore.getAutonomyPolicy()}));
    }
    if (parsedUrl.pathname === '/api/command') {
      const body = await readBody(req);
      const text = parsedUrl.searchParams.get('text') || JSON.parse(body || '{}').text || '';
      markInteraction();
      handleCommand(text);
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:true}));
    }
    if (parsedUrl.pathname === '/api/device') {
      const body = await readBody(req);
      const action = JSON.parse(body || '{}').action || 'camera_on';
      markInteraction();
      sendDevice(action);
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:true}));
    }
    if (parsedUrl.pathname === '/api/idle-timeout') {
      const body = await readBody(req);
      const minutes = Math.round(Number(JSON.parse(body || '{}').minutes));
      if (!Number.isFinite(minutes) || minutes < 1 || minutes > 120) {
        res.writeHead(400, {'Content-Type':'application/json; charset=utf-8'});
        return res.end(JSON.stringify({ok:false,error:'minutes 必须是 1 到 120 的整数'}));
      }
      idleTimeoutMinutes = minutes;
      markInteraction();
      addLog('success', `空闲断流时间已设为 ${minutes} 分钟`);
      broadcast(snapshotPayload());
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:true, minutes}));
    }
    if (parsedUrl.pathname === '/api/screen-off-timeout') {
      const body = await readBody(req);
      const minutes = Math.round(Number(JSON.parse(body || '{}').minutes));
      if (!Number.isFinite(minutes) || minutes < 0 || minutes > 120) {
        res.writeHead(400, {'Content-Type':'application/json; charset=utf-8'});
        return res.end(JSON.stringify({ok:false,error:'minutes 必须是 0 到 120 的整数，0 表示禁用'}));
      }
      screenOffExitMinutes = minutes;
      addLog('success', `息屏自动退出时间已设为 ${minutes === 0 ? '禁用' : minutes + ' 分钟'}`);
      broadcast(snapshotPayload());
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:true, minutes}));
    }
    if (parsedUrl.pathname === '/api/chat') {
      const body = await readBody(req);
      const payload = JSON.parse(body || '{}');
      markInteraction();
      handleChat(payload.text, Array.isArray(payload.memories) ? payload.memories : []).then(result=>{
        res.writeHead(200,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(result));
      }).catch(err=>{fs.appendFileSync(path.join(__dirname,'chat_error.log'),`${new Date().toISOString()} ${err.stack}\n`);res.writeHead(500,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify({ok:false,error:err.message}))});
      return;
    }
    if (parsedUrl.pathname === '/api/handoff') {
      if (req.method === 'POST') {
        const body = await readBody(req);
        const payload = JSON.parse(body || '{}');
        const incoming = normalizeHandoff(payload.state || payload);
        const current = loadHandoff();
        if (newerHandoff(current, incoming)) saveHandoff(incoming);
        addLog('success', `电脑端交接文档已更新：v${loadHandoff().revision}`);
      }
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:true, state:loadHandoff()}));
    }
    if (parsedUrl.pathname === '/api/tts') {
      const body = await readBody(req);
      const text = String(JSON.parse(body || '{}').text || '').trim();
      synthesizeSpeech(text).then(pcm => {
        res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
        res.end(JSON.stringify({ok:true, sampleRate:16000, audio:pcm.toString('base64')}));
      }).catch(err => {
        res.writeHead(500, {'Content-Type':'application/json; charset=utf-8'});
        res.end(JSON.stringify({ok:false,error:err.message}));
      });
      return;
    }
    if (parsedUrl.pathname === '/api/codex/select_task') {
      const body = await readBody(req);
      selectedCodexTaskId = String(JSON.parse(body || '{}').id || '');
      selectedCodexTaskDetail = null;
      addLog('info',`已选定 Codex 任务：${selectedCodexTaskId}`);
      refreshSelectedCodexTask(true).then(()=>{
        broadcast(snapshotPayload());
        res.writeHead(200,{'Content-Type':'application/json'});return res.end(JSON.stringify({ok:true,task:selectedCodexTaskDetail}));
      }).catch(err=>{res.writeHead(500,{'Content-Type':'application/json'});res.end(JSON.stringify({ok:false,error:err.message}))});
      return;
    }
    if (parsedUrl.pathname === '/api/codex/task') {
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:true,task:selectedCodexTaskDetail}));
    }
    if (parsedUrl.pathname === '/api/codex/select_model') {
      const body = await readBody(req);
      const payload = JSON.parse(body || '{}');
      selectCodexModel(payload.providerId,payload.model).then(result=>{
        addLog('success',`模型已切换：${result.model}`);
        return refreshCodexInfo().then(()=>{
          broadcast(snapshotPayload());
          res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({ok:true,...result}));
        });
      }).catch(err=>{res.writeHead(500,{'Content-Type':'application/json'});res.end(JSON.stringify({ok:false,error:err.message}))});
      return;
    }
    if (parsedUrl.pathname === '/ptt/start' || parsedUrl.pathname === '/ptt/stop') {
      markInteraction();
      isRecording = parsedUrl.pathname.endsWith('/start');
      if (isRecording) { audioBuffer=[]; addLog('info','对讲录音开始'); }
      else {
        if (audioBuffer.length) { const combined=Buffer.concat(audioBuffer); fs.writeFileSync(pendingAudioPath,combined); addLog('success',`对讲录音完成 ${combined.length} bytes`); }
        audioBuffer=[];
        queuePttReply();
      }
      res.writeHead(200,{'Content-Type':'application/json'}); return res.end(JSON.stringify({ok:true,recording:isRecording}));
    }
    if (parsedUrl.pathname === '/say') {
      markInteraction();
      speakToPhone(parsedUrl.searchParams.get('text')).then(result=>{
        res.writeHead(result.ok?200:400,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(result));
      }).catch(err=>{res.writeHead(500,{'Content-Type':'application/json'});res.end(JSON.stringify({ok:false,error:err.message}))});
      return;
    }
    if (parsedUrl.pathname === '/frame') {
      if (fs.existsSync(latestFramePath)) { res.writeHead(200,{'Content-Type':'image/jpeg','Cache-Control':'no-store'}); return fs.createReadStream(latestFramePath).pipe(res); }
      res.writeHead(404); return res.end('No frame yet');
    }
    if (parsedUrl.pathname === '/audio') {
      if (fs.existsSync(pendingAudioPath)) { res.writeHead(200,{'Content-Type':'application/octet-stream'}); return fs.createReadStream(pendingAudioPath).pipe(res); }
      res.writeHead(404); return res.end('No audio');
    }
    if (parsedUrl.pathname === '/live_audio') {
      if (fs.existsSync(liveAudioPath)) { res.writeHead(200,{'Content-Type':'application/octet-stream'}); return fs.createReadStream(liveAudioPath).pipe(res); }
      res.writeHead(404); return res.end('No live audio');
    }
    if (parsedUrl.pathname === '/') {
      res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'}); return res.end(html);
    }
    res.writeHead(404); res.end();
  } catch (e) {
    res.writeHead(500, {'Content-Type':'application/json'}); res.end(JSON.stringify({ok:false,error:e.message}));
  }
});

const wss = new WebSocketServer({ server });
registerWorkspaceTools();
wss.on('connection', (ws, req) => {
  console.log(`[WS] Client connected from ${req.socket.remoteAddress}`);
  if (!requestHasAccess(req)) {
    ws.close(4401, 'invalid token');
    return;
  }
  ws.isAlive = true;
  ws.send(snapshotPayload());
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', (data, isBinary) => {
    try {
      if (!isBinary) {
        const json = JSON.parse(data.toString());
        switch(json.type) {
          case 'snapshot': ws.send(snapshotPayload()); break;
          case 'workspace.event': {
            const event = createEventEnvelope({
              eventId: String(json.eventId || crypto.randomUUID()),
              origin: String(json.origin || 'phone'),
              sequence: Number(json.sequence || 0),
              type: String(json.eventType || 'workspace.event'),
              payload: json.payload || {},
              createdAt: json.createdAt || new Date().toISOString(),
              ack: false,
            });
            const accepted = workspaceStore.acceptEvent(event);
            if (accepted.accepted) applyWorkspaceEvent(accepted.event);
            ws.send(JSON.stringify({ type: 'workspace.ack', eventId: event.eventId, accepted: accepted.accepted, origin: event.origin }));
            if (accepted.accepted) broadcast({ type: 'workspace.event', event });
            break;
          }
          case 'command': markInteraction(); handleCommand(json.text); break;
          case 'sensor_state': {
            if (!ws.isPhone) return;
            const camera = json.camera === true;
            const audio = json.audio === true;
            if (camera || audio) markInteraction();
            if (sensors.camera && !camera) sensorSuppressedUntil.camera = Date.now() + 2000;
            if (sensors.audio && !audio) sensorSuppressedUntil.audio = Date.now() + 2000;
            sensors.camera = camera;
            sensors.audio = audio;
            updateDeviceHealth({ camera: camera ? 'active' : 'inactive', microphone: audio ? 'active' : 'inactive', bridge: 'online', node: 'online' });
            evaluateWorkspaceAutomations({
              battery: phoneTelemetry.battery,
              temperature: phoneTelemetry.temperature,
              network: phoneTelemetry.network_rx > 0 || phoneTelemetry.network_tx > 0,
              camera,
              microphone: audio,
            });
            break;
          }
          case 'telemetry':
            phoneTelemetry={...json,updated:Date.now()};
            updateDeviceHealth({ bridge: 'online', node: 'online' });
            evaluateWorkspaceAutomations({
              battery: phoneTelemetry.battery,
              temperature: phoneTelemetry.temperature,
              network: phoneTelemetry.network_rx > 0 || phoneTelemetry.network_tx > 0,
              camera: sensors.camera,
              microphone: sensors.audio,
            });
            break;
          case 'pet': petState=json.state||{}; break;
          case 'chat': markInteraction(); handleChat(json.text, Array.isArray(json.memories) ? json.memories : []).catch(err=>addLog('error',err.message)); break;
          case 'handoff_sync': {
            const incoming=normalizeHandoff(json.state||{});
            const current=loadHandoff();
            if(newerHandoff(current,incoming)) {
              saveHandoff(incoming);
              broadcast({type:'handoff',state:current});
              addLog('success',`手机端交接文档已同步：v${incoming.revision}`);
            } else {
              ws.send(JSON.stringify({type:'handoff',state:current}));
            }
            break;
          }
          case 'handoff_get': ws.send(JSON.stringify({type:'handoff',state:loadHandoff()})); break;
          case 'select_codex_task':
            selectedCodexTaskId=String(json.id||'');
            selectedCodexTaskDetail=null;
            addLog('info',`已选定 Codex 任务：${selectedCodexTaskId}`);
            refreshSelectedCodexTask(true).then(()=>{ws.send(snapshotPayload())}).catch(()=>{});
            break;
          case 'select_model':
            selectCodexModel(json.providerId,json.model)
              .then(()=>refreshCodexInfo())
              .then(()=>{broadcast(snapshotPayload());addLog('success',`模型已切换：${json.model||''}`)})
              .catch(err=>addLog('error',err.message));
            break;
          case 'hello':
            petState=json.pet||{};
            ws.isPhone=true;
            updateDeviceHealth({ bridge: 'online', node: 'online', model: 'active', authorization: 'active' });
            addLog('success','Mote 已接入节点');
            break;
        }
        return;
      }
      const buf=Buffer.from(data); if(buf.length<2)return; const type=buf[0];
      ws.isPhone=true;
      if(type===0x01){
        if(!sensors.camera && Date.now() >= sensorSuppressedUntil.camera) sensors.camera = true;
        updateDeviceHealth({ bridge: 'online', node: 'online', camera: 'active' });
        fs.writeFileSync(latestFramePath,buf.subarray(2));frameCount++
      }
      else if(type===0x02){
        if(!sensors.audio && Date.now() >= sensorSuppressedUntil.audio) sensors.audio = true;
        updateDeviceHealth({ bridge: 'online', node: 'online', microphone: 'active' });
        appendCapped(liveAudioPath,buf.subarray(2),20*1024*1024);audioCount++;if(isRecording)audioBuffer.push(buf.subarray(2))
      }
    } catch(e){console.error(e)}
  });
  ws.on('close',()=>{
    console.log('[WS] Client disconnected');
    if(ws.isPhone){
      sensors.camera=false;
      sensors.audio=false;
      updateDeviceHealth({ bridge: 'disconnected', node: 'inactive', camera: 'inactive', microphone: 'inactive' });
      addLog('warn','Mote 已断开');
    }
  });
  ws.on('error',(err)=>console.error(`[WS] Error: ${err.message}`));
});

setInterval(()=>{wss.clients.forEach(ws=>{if(!ws.isAlive)return ws.terminate();ws.isAlive=false;try{ws.ping()}catch(_){}})},15000);
setInterval(()=>{refreshCodexInfo().finally(()=>broadcast({type:'snapshot',stats:statsSnapshot(),tasks:publicTasks(),logs:publicLogs(50),telemetry:phoneTelemetry,pet:petState,codex:{...codexInfo,selectedTaskId:selectedCodexTaskId},chat:chatHistory.slice(-30)}))},1000);
let stateSaveFailures = 0;
setInterval(() => {
  try {
    savePersistentState();
    if (stateSaveFailures) console.log('Persistent state saving recovered');
    stateSaveFailures = 0;
  } catch (error) {
    stateSaveFailures++;
    if (stateSaveFailures === 1) {
      const message = `${new Date().toISOString()} Persistent state save failed: ${error.stack}\n`;
      console.error(message.trim());
      try { fs.appendFileSync(path.join(__dirname, 'crash.log'), message); } catch (_) {}
    }
  }
},2000);
setInterval(()=>{if(selectedCodexTaskId)refreshSelectedCodexTask().finally(()=>broadcast(snapshotPayload()))},15000);
setInterval(checkIdleTimeout, 15000);
setInterval(() => evaluateWorkspaceAutomations({
  battery: phoneTelemetry.battery,
  temperature: phoneTelemetry.temperature,
  network: phoneTelemetry.network_rx > 0 || phoneTelemetry.network_tx > 0,
  camera: sensors.camera,
  microphone: sensors.audio,
}), 15000);

server.on('error', (error) => {
  console.error(`PhoneBridge server listen failed: ${error.message}`);
  if (error.code === 'EADDRINUSE') addLog('error', `端口 ${PORT} 已被占用，节点退出`);
  process.exit(1);
});

server.listen(PORT, BIND_HOST, ()=>{
  addLog('success','Mote 节点已启动');
  refreshCodexInfo();
  if(selectedCodexTaskId)refreshSelectedCodexTask();
  console.log(`PhoneBridge server running on ${BIND_HOST}:${PORT}`);
});
