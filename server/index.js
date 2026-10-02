const http = require('http');
const https = require('https');
const crypto = require('crypto');
const { execFile, spawn } = require('child_process');
const { WebSocketServer } = require('ws');
const QRCode = require('qrcode');
const { WorkspaceStore, createEventEnvelope, shouldApplyWorkspaceEvent, workspaceBusinessAck } = require('./workspace-core');
const { DeviceHealthStore } = require('./device-health');
const { MoteStore, deriveMoteBehavior, MOTE_PROFILES } = require('./mote-profiles');
const { TaskRunner } = require('./task-runner');
const { MoteRelationshipStore, MoteQuestStore } = require('./mote-expansion');
const { MoteStoryStore, deriveExclusiveTriggers, claimMoteStoryWithReward } = require('./mote-story');
const { RevisionSnapshotCache, BroadcastCoalescer } = require('./workspace-performance');
const { WorkspaceTimeline } = require('./workspace-timeline');
const { AiProviderManager } = require('./ai-provider');
const { DiagnosticsCollector } = require('./diagnostics');
const { RuntimePersistence } = require('./runtime-persistence');
const { PrivacyCenter } = require('./privacy-center');
const { ActiveRequestRegistry } = require('./active-request-registry');
const { HealthChecks } = require('./health');
const { createStructuredLogger } = require('./structured-log');
const { DeviceSimulator } = require('./device-simulator');
const { MemoryStore } = require('./ai-memory');
const { RealityEngine } = require('./reality-engine');
const { buildRealityLog } = require('./reality-log');
const { MoteGrowthStore } = require('./mote-growth');
const { DailyRoutinesStore } = require('./daily-routines');
const { GoalBoardService, GoalBoardError } = require('./goal-board');
const { createRealityCoordinator } = require('./reality-coordinator');
const { ProactivePolicy } = require('./proactive-policy');
const { PairingManager } = require('./pairing');
const { prepareConversation } = require('./session-context');
const { buildCompanionSummary } = require('./companion-summary');
const { loadTlsOptions, pairingTransport, pairingAvailabilityError, buildPairingQrPayload } = require('./tls-config');
const { DEFAULT_THEME_ID, UI_THEMES, buildThemeCss } = require('./ui-themes');
const { browserRendererScript } = require('./web-stage');
const fs = require('fs');
const path = require('path');
const os = require('os');

const WEB_THEME_CSS = buildThemeCss();
const WEB_THEME_IDS = JSON.stringify(UI_THEMES.map(theme => theme.id));
const WEB_THEME_OPTIONS = UI_THEMES.map(theme => `<option value="${theme.id}">${theme.label}</option>`).join('');
const MOTE_STAGE_SCRIPT = browserRendererScript();

const PORT = process.env.PHONEBRIDGE_PORT || 9501;
const BIND_HOST = process.env.PHONEBRIDGE_BIND || '127.0.0.1';
const RUNTIME_DIR = process.env.PHONEBRIDGE_RUNTIME_DIR || __dirname;
const TLS_CONFIG = loadTlsOptions({
  keyPath: process.env.PHONEBRIDGE_TLS_KEY || '',
  certPath: process.env.PHONEBRIDGE_TLS_CERT || '',
});
const TLS_OPTIONS = TLS_CONFIG.options;
const TLS_ENABLED = Boolean(TLS_OPTIONS);
const PAIRING_HOST = process.env.PHONEBRIDGE_PAIRING_HOST || BIND_HOST;
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
const runtimePersistence = new RuntimePersistence({
  dir: RUNTIME_DIR,
  onRecovery: event => console.warn(JSON.stringify({ event: 'runtime.recovered', ...event }))
});
const structuredLogger = createStructuredLogger({ sink: line => console.log(line), context: { component: 'phonebridge' } });
const deviceSimulator = process.env.PHONEBRIDGE_ENABLE_SIMULATOR === '1'
  ? new DeviceSimulator({ seed: process.env.PHONEBRIDGE_SIMULATOR_SEED || 'phonebridge-sim' })
  : null;

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
    const state = runtimePersistence.load('runtime-state', {});
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
  try {
    runtimePersistence.save('runtime-state', payload);
  } catch (error) {
    structuredLogger.error('runtime.persist_failed', { error: error.message });
    throw error;
  }
}

loadPersistentState();
loadHandoff();

function loadAccessToken() {
  if (process.env.PHONEBRIDGE_TOKEN) return process.env.PHONEBRIDGE_TOKEN;
  const tokenFile = path.join(__dirname, 'access.token');
  try {
    const existing = fs.readFileSync(tokenFile, 'utf8').trim();
    if (existing.length >= 24) {
      try { fs.chmodSync(tokenFile, 0o600); } catch (_) {}
      return existing;
    }
  } catch (_) {}
  const generated = crypto.randomBytes(24).toString('hex');
  fs.writeFileSync(tokenFile, `${generated}\n`, { mode: 0o600 });
  try { fs.chmodSync(tokenFile, 0o600); } catch (_) {}
  return generated;
}

function rotateAccessToken() {
  const tokenFile = path.join(__dirname, 'access.token');
  const rotated = crypto.randomBytes(24).toString('hex');
  if (process.env.PHONEBRIDGE_TOKEN) throw new Error('环境变量令牌不能在运行时轮换');
  fs.writeFileSync(tokenFile, `${rotated}\n`, { mode: 0o600 });
  try { fs.chmodSync(tokenFile, 0o600); } catch (_) {}
  ACCESS_TOKEN = rotated;
  return rotated;
}

let ACCESS_TOKEN = loadAccessToken();
const workspaceStore = new WorkspaceStore({
  journalPath: path.join(RUNTIME_DIR, 'workspace-events.jsonl'),
  snapshotPath: path.join(RUNTIME_DIR, 'workspace-state.json'),
  persistence: runtimePersistence,
  persistDebounceMs: 250,
});
process.on('exit', () => workspaceStore.persistenceScheduler?.flushNow());
const moteStore = new MoteStore({ snapshotPath: path.join(RUNTIME_DIR, 'mote-state.json'), persistence: runtimePersistence });
const moteRelationshipStore = new MoteRelationshipStore({ snapshotPath: path.join(RUNTIME_DIR, 'mote-relationship.json'), persistence: runtimePersistence });
const moteQuestStore = new MoteQuestStore({
  quests: [
    { id: 'daily-observer', title: '完成一次观察', reward: 5 },
    { id: 'task-companion', title: '完成一次任务', reward: 10 },
  ],
  snapshotPath: path.join(RUNTIME_DIR, 'mote-quests.json'),
  persistence: runtimePersistence,
});
const deviceHealthStore = new DeviceHealthStore({
  bridge: 'disconnected',
  node: 'inactive',
  camera: 'inactive',
  microphone: 'inactive',
  authorization: 'unknown',
  outbox: 'online',
});
const workspaceTimeline = new WorkspaceTimeline({ retention: 500, persistence: runtimePersistence });
const aiProviderManager = new AiProviderManager({
  persistence: runtimePersistence,
  adapters: {
    codex: {
      chat: async ({ prompt, memories, options, signal, requestId, onDelta }) => rawChatWithModel(prompt, memories, {
        ...options,
        signal,
        requestId,
        onDelta,
      }),
      probe: async () => ({ ok: true, latencyMs: 20, model: codexInfo.currentModel || 'codex', status: 'ready' })
    }
  }
});
const diagnosticsCollector = new DiagnosticsCollector({ startTime: startedAt });
const healthChecks = new HealthChecks({
  persistence: () => ({ ok: true, recovered: Boolean(runtimePersistence.lastRecovery) }),
  workspace: () => ({ ok: Boolean(workspaceStore) }),
  token: () => ({ ok: Boolean(ACCESS_TOKEN) }),
});
const memoryStore = new MemoryStore({ persistence: runtimePersistence });
const realityEngine = new RealityEngine({ persistence: runtimePersistence });
const dailyRoutinesStore = new DailyRoutinesStore({ persistence: runtimePersistence });
const moteGrowthStore = new MoteGrowthStore({ persistence: runtimePersistence });
const moteStoryStore = new MoteStoryStore({ persistence: runtimePersistence });
const realityCoordinator = createRealityCoordinator({
  moteStore,
  moteGrowthStore,
  realityEngine,
  broadcastMoteState: () => broadcastMoteState(),
});
const { applyMoteClue, buildProgress: buildRealityProgress } = realityCoordinator;
realityCoordinator.syncBoosts();
const pairingManager = new PairingManager();
const proactivePolicy = new ProactivePolicy({ persistence: runtimePersistence });
const activeChatRequests = new ActiveRequestRegistry();
const privacyCenter = new PrivacyCenter({
  persistence: runtimePersistence,
  categories: {
    memories: {
      label: '长期记忆',
      count: () => memoryStore.snapshot().count,
      export: () => memoryStore.export({ includeSensitive: true }),
      clear: () => memoryStore.clear(),
    },
    conversations: {
      label: '聊天与会话',
      count: () => chatHistory.length + workspaceStore.listSessions().reduce((sum, session) => sum + (session.messages?.length || 0), 0) + (handoffHasContent(loadHandoff()) ? 1 : 0),
      export: () => ({ legacyHistory: chatHistory, sessions: workspaceStore.listSessions(), handoff: loadHandoff() }),
      validateClear: () => { if (taskRunner?.list().some(task => !['succeeded', 'failed', 'cancelled'].includes(task.state))) throw new Error('finish or cancel running tasks before deleting conversations'); },
      prepareDelete: () => activeChatRequests.pauseCancelAndWait(),
      clear: async () => {
        const previousCount = chatHistory.length;
        const linkedTaskIds = new Set(workspaceStore.listTasks()
          .filter(task => task.metadata?.sessionId || task.metadata?.messageId)
          .map(task => String(task.id)));
        for (const task of taskRunner.list()) {
          if (task.metadata?.sessionId || linkedTaskIds.has(String(task.id))) linkedTaskIds.add(String(task.id));
        }
        const timelineBefore = workspaceTimeline.getSnapshot();
        for (const task of timelineBefore.tasks) {
          if (task.source === 'conversation' || task.relatedSessionId || linkedTaskIds.has(String(task.id))) linkedTaskIds.add(String(task.id));
        }
        const linkedAttentionIds = timelineBefore.attention
          .filter(item => item.relatedSessionId || linkedTaskIds.has(String(item.relatedTaskId || '')))
          .map(item => String(item.id));
        const timelineTypes = ['chat'];
        if (linkedTaskIds.size) timelineTypes.push('task');
        if (linkedAttentionIds.length) timelineTypes.push('attention');
        workspaceTimeline.purgePersonalData(timelineTypes, {
          task: [...linkedTaskIds],
          attention: linkedAttentionIds,
        });
        const result = workspaceStore.clearConversationData([...linkedTaskIds]);
        taskRunner.forget([...linkedTaskIds]);
        chatHistory.splice(0, chatHistory.length);
        for (let index = logs.length - 1; index >= 0; index -= 1) {
          const message = String(logs[index]?.message || '');
          if (message.startsWith('Mote 对话回复：') || message.startsWith('PTT 语音识别：')) logs.splice(index, 1);
        }
        const handoff = loadHandoff();
        const deletedHandoff = handoffHasContent(handoff) ? 1 : 0;
        if (deletedHandoff) {
          saveHandoff({ revision: handoff.revision + 1, updatedAtMs: Date.now(), updatedBy: 'privacy-delete' });
          broadcast({ type: 'handoff', state: loadHandoff() });
        }
        savePersistentState();
        await workspaceStore.flushPersistence();
        return { deleted: previousCount + result.deleted.sessions + result.deleted.messages + result.deleted.linkedTasks + deletedHandoff };
      },
    },
    tasks: {
      label: '任务与审计',
      count: () => workspaceStore.listTasks().length + tasks.size,
      export: () => ({
        tasks: workspaceStore.listTasks({ limit: 200 }),
        taskAudit: workspaceStore.listTasks({ limit: 200 }).flatMap(task => workspaceStore.listTaskAudit(task.id)),
        audit: workspaceStore.auditLog(),
      }),
      validateClear: () => { if (taskRunner?.list().some(task => !['succeeded', 'failed', 'cancelled'].includes(task.state))) throw new Error('finish or cancel running tasks before deleting task history'); },
      clear: async () => {
        const legacyCount = tasks.size;
        workspaceTimeline.purgePersonalData(['task', 'attention']);
        tasks.clear();
        const result = workspaceStore.clearTaskHistory();
        taskRunner.forgetAllTerminal();
        for (let index = logs.length - 1; index >= 0; index -= 1) {
          if (String(logs[index]?.level || '') === 'task') logs.splice(index, 1);
        }
        savePersistentState();
        await workspaceStore.flushPersistence();
        return { deleted: result.deleted + legacyCount };
      },
    },
    progress: {
      label: 'Mote 成长与探索',
      count: () => {
        const reality = realityEngine.snapshot();
        const growth = moteGrowthStore.snapshot();
        const story = moteStoryStore.snapshot();
        return reality.seenEventIds.length + growth.processedEvents.length + story.completed.length + story.claimed.length + moteRelationshipStore.snapshot().interactions;
      },
      export: () => ({
        reality: realityEngine.snapshot(),
        moteGrowth: moteGrowthStore.snapshot(),
        moteRoster: moteStore.getState(),
        relationship: moteRelationshipStore.snapshot(),
        quests: moteQuestStore.snapshot(),
        stories: moteStoryStore.snapshot(),
      }),
      prepareDelete: () => activeChatRequests.pauseCancelAndWait(),
      clear: () => {
        const deleted = privacyCenterProgressCount();
        workspaceTimeline.purgePersonalData(['mote']);
        workspaceStore.clearProgressEvents();
        realityEngine.reset();
        moteGrowthStore.reset();
        moteStore.reset();
        moteRelationshipStore.reset();
        moteQuestStore.reset();
        moteStoryStore.reset();
        realityCoordinator.syncBoosts();
        return { deleted };
      },
    },
    routines: {
      label: '日常与习惯',
      count: () => dailyRoutinesStore.count(),
      export: () => dailyRoutinesStore.export(),
      clear: () => dailyRoutinesStore.clear(),
    },
    goals: {
      label: '个人目标',
      count: () => goalBoard.countGoals(),
      export: () => goalBoard.exportGoals(),
      validateClear: () => goalBoard.validateClear(),
      clear: async () => {
        const goalTaskIds = workspaceStore.listGoalTaskIds();
        purgeGoalTaskTimeline(goalTaskIds);
        const result = goalBoard.clearGoals();
        await workspaceStore.flushPersistence();
        return { deleted: result.deleted + result.tasks };
      },
    },
  },
});

function acceptWorkspaceEvent(event) {
  return workspaceStore.acceptEvent(event, {
    categoryRevision: category => privacyCenter.categoryRevision(category),
    isMigrationRequired: category => privacyCenter.isMigrationRequired(category),
    observeCategoryRevision: (category, revision) => privacyCenter.observeCategoryRevision(category, revision),
    observeCategoryRevisions: revisions => privacyCenter.observeCategoryRevisions(revisions),
  });
}

function privacyCenterProgressCount() {
  const reality = realityEngine.snapshot();
  const growth = moteGrowthStore.snapshot();
  const story = moteStoryStore.snapshot();
  return reality.seenEventIds.length + growth.processedEvents.length + story.completed.length + story.claimed.length + moteRelationshipStore.snapshot().interactions;
}

function handoffHasContent(handoff) {
  return ['goal', 'currentTask', 'nextSteps', 'keyConstraints', 'recentDecisions', 'notes']
    .some(key => String(handoff?.[key] || '').trim().length > 0);
}

function purgeGoalTaskTimeline(taskIds) {
  const ids = [...new Set((Array.isArray(taskIds) ? taskIds : []).map(String).filter(Boolean))];
  if (!ids.length) return;
  const selected = new Set(ids);
  const attentionIds = workspaceTimeline.getSnapshot().attention
    .filter(item => selected.has(String(item.relatedTaskId || '')))
    .map(item => String(item.id));
  workspaceTimeline.purgePersonalData(['task', 'attention'], { task: ids, attention: attentionIds });
}

let snapshotRevision = 0;
const snapshotCache = new RevisionSnapshotCache({
  getRevision: () => `${workspaceStore.eventRevision}:${snapshotRevision}`,
  buildFull: () => JSON.stringify(buildSnapshotPayload()),
  buildSummary: () => JSON.stringify(buildSummaryPayload()),
});

function nowTime() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false });
}

function publicTasks() {
  return [...tasks.values()].sort((a, b) => b.createdAt - a.createdAt).slice(0, 80);
}

function publicLogs(count = 100) {
  return logs.slice(-count);
}

function moteStoryContext(extra = {}) {
  const growth = moteGrowthStore.snapshot();
  const tasks = workspaceStore.listTasks();
  const sessions = workspaceStore.listSessions();
  const clueCounts = Object.fromEntries(['location', 'object', 'light'].map(type => [
    type,
    (growth.processedEvents || []).filter(item => item.businessStatus === 'accepted' && item.clueType === type).length,
  ]));
  const successfulTasks = tasks.filter(task => task.state === 'succeeded').length;
  const recoveredTasks = tasks.filter(task => task.state === 'succeeded' && Number(task.runner?.retryCount) > 0).length;
  return {
    activeId: moteStore.getState().activeId,
    conversationCount: sessions.reduce((total, session) => total + (Array.isArray(session.messages) ? session.messages.filter(message => message.role === 'user').length : 0), 0) + chatHistory.filter(message => message.role === 'user').length,
    successfulTasks,
    explorationCount: (growth.processedEvents || []).filter(item => item.businessStatus === 'accepted').length,
    clueCounts,
    clues: Object.fromEntries(Object.entries(clueCounts).map(([type, count]) => [type, count > 0])),
    unlockedCount: moteStore.getState().unlockedIds.length,
    relationshipLevel: moteRelationshipStore.snapshot().level,
    boostCount: (growth.boosts || []).length,
    recoveredTasks,
    ...extra,
  };
}

function buildMoteProjection() {
  const state = moteStore.getState();
  const relationship = moteRelationshipStore.snapshot();
  return {
    state,
    roster: moteStore.roster(),
    behavior: deriveMoteBehavior({ profileId: state.activeId, relationshipLevel: relationship.level }),
    relationship,
    growth: moteGrowthStore.snapshot(),
    quests: moteQuestStore.list(),
    story: moteStoryStore.list(),
  };
}

function progressMoteStory(eventId, extra = {}, { emit = true } = {}) {
  const result = moteStoryStore.evaluate({
    ...moteStoryContext(extra),
    eventId,
    exclusiveTriggers: deriveExclusiveTriggers(extra),
  });
  if (emit && result.newlyCompleted.length) {
    broadcast({
      type: 'mote.story',
      story: result.events,
      newlyCompleted: result.newlyCompleted,
      state: result.state,
    });
  }
  return result;
}

function completeRealityEncounter(eventId, payload, result) {
  if (!result || result.businessStatus === 'rejected') return result;
  const previousRelationshipLevel = moteRelationshipStore.snapshot().level;
  const relationship = moteRelationshipStore.recordInteraction({
    eventId: `reality:${eventId}`,
    kind: 'reality',
    amount: Number(result.reward?.xp || result.growth?.reward?.xp) || 1,
  });
  result.relationship = relationship;
  result.story = progressMoteStory(`reality:${eventId}`, {
    explorationCount: 1,
    clueCounts: { [String(payload.clueType || result.event?.clueType || '').toLowerCase()]: 1 },
    boostCount: result.growth?.reward?.boost ? 1 : 0,
    relationshipLevel: relationship.level,
    previousRelationshipLevel,
  });
  result.state = realityEngine.snapshot();
  if (!result.duplicate) broadcast({ type: 'reality.progress', result, state: result.state });
  return result;
}

// Initial activation is a durable story milestone; it is evaluated once without broadcasting during boot.
progressMoteStory(`startup:${moteStore.getState().activeId}`, {}, { emit: false });

function syncTimelineFromBroadcast(payload) {
  if (!payload || typeof payload !== 'object') return;
  const type = payload.type;
  if (!type) return;
  let timelineEvent = null;

  if (type === 'workspace.task' && payload.task) {
    timelineEvent = workspaceTimeline.recordEvent({
      entityType: 'task',
      entityId: payload.task.id,
      operation: payload.task.deleted ? 'delete' : (payload.task.createdAt === payload.task.updatedAt ? 'create' : 'update'),
      deleted: Boolean(payload.task.deleted),
      payload: payload.task
    });
  } else if (type === 'workspace.message' && payload.message) {
    timelineEvent = workspaceTimeline.recordEvent({
      entityType: 'chat',
      entityId: payload.message.id,
      operation: 'create',
      payload: { ...payload.message, sessionId: payload.sessionId }
    });
  } else if (type === 'attention.upsert' && payload.attention) {
    timelineEvent = workspaceTimeline.recordEvent({
      entityType: 'attention',
      entityId: payload.attention.id,
      operation: 'update',
      payload: {
        ...payload.attention,
        deepLink: payload.attention.relatedTaskId ? `phonebridge://task/${payload.attention.relatedTaskId}` : `phonebridge://attention/${payload.attention.id}`
      }
    });
  } else if (type === 'mote.relationship' || type === 'mote.profile' || type === 'mote.quest' || type === 'mote.story') {
    const active = moteStore.roster().find(item => item.active) || moteStore.roster()[0];
    const rel = moteRelationshipStore.snapshot();
    timelineEvent = workspaceTimeline.recordEvent({
      entityType: 'mote',
      entityId: 'active',
      operation: 'update',
      payload: {
        profileId: active?.id || 'rimuru',
        name: active?.name || '利姆鲁',
        active: true,
        level: rel.level,
        xp: rel.xp,
        interactions: rel.interactions,
        storyCompleted: moteStoryStore.snapshot().completed.length,
        updatedAt: new Date().toISOString()
      }
    });
  } else if (type === 'device.health' && payload.state) {
    timelineEvent = workspaceTimeline.recordEvent({
      entityType: 'health',
      entityId: 'device',
      operation: 'update',
      payload: {
        ...payload.state,
        battery: phoneTelemetry.battery,
        temperature: phoneTelemetry.temperature,
        memory: phoneTelemetry.memory,
        networkRx: phoneTelemetry.network_rx,
        networkTx: phoneTelemetry.network_tx,
        updatedAt: new Date().toISOString()
      }
    });
  } else if (type === 'workspace.policy' || type === 'workspace.emergency_stop' || type === 'autonomy.approval') {
    const policy = workspaceStore.getAutonomyPolicy();
    const estop = workspaceStore.emergencyStopState();
    timelineEvent = workspaceTimeline.recordEvent({
      entityType: 'autonomy',
      entityId: 'global',
      operation: 'update',
      payload: {
        level: policy.level,
        allowedTools: policy.allowedTools,
        emergencyStop: estop.active,
        pendingApprovals: workspaceStore.listToolApprovals().filter(a => a.state === 'needs_confirmation').length,
        updatedAt: new Date().toISOString()
      }
    });
  }
  return timelineEvent;
}

function broadcast(payload) {
  const text = typeof payload === 'string' ? payload : JSON.stringify(payload);
  const isSnapshot = typeof payload === 'string' ? text.includes('"type":"snapshot"') : payload?.type === 'snapshot';
  if (!isSnapshot) snapshotRevision += 1;
  let timelineEvent = null;
  try {
    if (typeof payload === 'object') timelineEvent = syncTimelineFromBroadcast(payload);
  } catch (_) {}
  wss.clients.forEach((socket) => {
    if (socket.readyState === socket.OPEN) {
      socket.send(text);
      if (timelineEvent) socket.send(JSON.stringify({ type: 'workspace.timeline', revision: timelineEvent.revision, event: timelineEvent }));
    }
  });
}

const snapshotBroadcaster = new BroadcastCoalescer({ send: payload => broadcast(payload), delayMs: 16 });
function queueSnapshotBroadcast() { snapshotBroadcaster.enqueue(snapshotPayload()); }

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
  structuredLogger.write(level, 'runtime.log', { message });
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

function emitProactive(message, key = message) {
  const now = Date.now();
  const decision = proactivePolicy.attempt(key, now);
  const payload = {
    type: 'proactive',
    key,
    message: String(message).slice(0, 500),
    createdAt: new Date(now).toISOString(),
    delivery: decision.allowed ? 'sent' : 'suppressed',
    reason: decision.reason,
    explanation: decision.message,
  };
  const attention = workspaceStore.upsertAttentionItem({
    source: 'proactive',
    severity: 'medium',
    title: `主动提醒：${payload.message.slice(0, 36)}`,
    summary: payload.message,
    dedupeKey: String(key),
  });
  // Suppressed reminders stay in the inbox for inspection, but are not pushed
  // to the phone as a live notification or spoken cue.
  if (!decision.allowed) return false;
  broadcastAttention(attention);
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
    return completeRealityEncounter(payload.eventId, payload, applyMoteClue(payload));
  }
  return { businessStatus: 'accepted', reason: 'event_applied', resultRevision: event.revision || workspaceStore.eventRevision };
}

const taskRunner = new TaskRunner({ maxConcurrency: 1, maxRetries: 1, retryDelayMs: 250 });
const goalBoard = new GoalBoardService({
  workspaceStore,
  providerManager: aiProviderManager,
  isTaskActive: taskId => {
    const record = taskRunner.get(taskId);
    return taskRunner.active.has(String(taskId)) || Boolean(record && !['succeeded', 'failed', 'cancelled'].includes(record.state));
  },
  onTasksDeleted: taskIds => { if (taskIds?.length) taskRunner.forget(taskIds); },
  recordProviderAudit: entry => diagnosticsCollector.recordGoalDraftAudit(entry),
});

function publishRunnerState(update) {
  const current = workspaceStore.getTask(update.id);
  if (!current) return;
  const patch = {
    state: update.state,
    progress: update.progress,
    runner: {
      attempt: update.attempt,
      retryCount: update.retryCount || 0,
      queuePosition: update.queuePosition || 0,
      lastError: update.lastError || null,
      lastTransitionAt: update.lastTransitionAt || null,
    },
    log: `任务运行第 ${update.attempt} 次：${update.state}`,
  };
  if (['pending', 'running', 'succeeded'].includes(update.state)) patch.error = null;
  else if (update.error) patch.error = update.error;
  if (update.result?.assistantId) patch.artifactRefs = [update.result.assistantId];
  try {
    const task = workspaceStore.updateTask(update.id, patch);
    broadcast({ type: 'workspace.task', task });
    if (task.state === 'succeeded') {
      const previousRelationshipLevel = moteRelationshipStore.snapshot().level;
      const relationship = moteRelationshipStore.recordInteraction({
        eventId: `task-success:${task.id}`,
        kind: 'task',
        amount: 8,
      });
      if (!relationship.duplicate) broadcast({ type: 'mote.relationship', relationship });
      progressMoteStory(`task:${task.id}:${task.state}`, {
        successfulTasks: 1,
        recoveredTasks: Number(task.runner?.retryCount) > 0 ? 1 : 0,
        relationshipLevel: relationship.level,
        previousRelationshipLevel,
      });
    }
    if (['succeeded', 'failed', 'cancelled'].includes(task.state)) broadcastTaskAttention(task);
  } catch (error) {
    addLog('warn', `任务状态同步失败：${error.message}`);
  }
}

taskRunner.on('state', publishRunnerState);
taskRunner.setExecutor(async ({ task, signal, report, waitIfPaused }) => {
  const sessionId = task.metadata?.sessionId;
  const session = workspaceStore.getSession(sessionId);
  if (!session) throw new Error('session not found');
  await waitIfPaused();
  if (signal.aborted) throw new Error('cancelled');
  report(10);
  const requestId = String(task.metadata?.requestId || `ai_${task.id}`);
  const cancelOnAbort = () => aiProviderManager.cancel(requestId);
  signal.addEventListener('abort', cancelOnAbort, { once: true });
  const configuredMemories = task.metadata?.remember === false
    ? []
    : Array.isArray(task.metadata?.memories) && task.metadata.memories.length
      ? task.metadata.memories
      : memoryStore.selectForConversation({ remember: true, limit: 20 }).map(item => item.text);
  const result = await chatWithModel(String(task.metadata?.text || task.detail || ''), configuredMemories, {
    model: session.model,
    providerId: session.providerId,
    requestId,
    sessionId,
    history: session.messages,
    remember: task.metadata?.remember !== false,
  });
  signal.removeEventListener('abort', cancelOnAbort);
  if (signal.aborted) throw new Error('cancelled');
  const assistant = workspaceStore.appendMessage(sessionId, { role: 'assistant', text: result.reply, streamId: result.id });
  broadcast({ type: 'workspace.message', sessionId, message: assistant });
  report(100);
  return { assistantId: assistant.id };
});

function broadcastMoteState() {
  const state = moteStore.getState();
  const growth = moteGrowthStore.snapshot();
  broadcast({ type: 'mote.roster', roster: moteStore.roster(), state, growth, story: moteStoryStore.list() });
  broadcast({ type: 'mote.profile', profile: moteStore.roster().find(item => item.active) || null });
  broadcast({ type: 'mote.exploration', exploration: state.exploration, state, growth });
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

async function rawChatWithModel(text, memories = [], options = {}) {
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
  const streamId = String(options.requestId || `chat_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`);
  const body = wireApi === 'chat'
    ? { model, messages: context, stream: true }
    : { model, input: context, stream: true };
  const timeoutSignal = AbortSignal.timeout(120000);
  const signal = options.signal ? AbortSignal.any([options.signal, timeoutSignal]) : timeoutSignal;
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body),
    signal,
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
        options.onDelta?.(delta);
        streamChunkCount++;
        const now = Date.now();
        if (now - lastBroadcast > 90) {
          lastBroadcast = now;
          broadcast({ type:'chat_delta', id:streamId, requestId: streamId, source: options.source || 'chat', sessionId: options.sessionId || '', text:reply });
        }
      } catch (_) {}
    }
  }
  broadcast({ type:'chat_delta', id:streamId, requestId: streamId, source: options.source || 'chat', sessionId: options.sessionId || '', text:reply, terminal: true });
  reply = String(reply || '').trim();
  if (!reply) throw new Error('模型返回空响应');
  return { id: streamId, reply };
}

async function chatWithModel(text, memories = [], options = {}) {
  const context = prepareConversation({
    history: options.history || chatHistory.slice(-16),
    memories: options.remember === false ? [] : memories,
  });
  memoryStore.markUsed(context.memories.map(value => memoryStore.list({ query: value, limit: 1 })[0]?.id).filter(Boolean));
  const result = await aiProviderManager.chat({
    prompt: String(text || ''),
    messages: context.messages,
    memories: context.memories,
    providerId: options.providerId || null,
    requestId: options.requestId || null,
    signal: options.signal || null,
    options
  });
  diagnosticsCollector.recordProviderLatency(result.fallbackProvider || result.providerId, aiProviderManager.lastLatencyMs);
  if (result.degraded) diagnosticsCollector.recordDegradation();
  return {
    id: result.id || `chat_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
    reply: result.reply,
    providerId: result.providerId,
    fallbackProvider: result.fallbackProvider || null,
    degraded: Boolean(result.degraded)
  };
}

async function handleChat(text, memories = [], options = {}) {
  const clean = String(text || '').trim().slice(0, 2000);
  if (!clean) throw new Error('empty');
  const requestId = String(options.requestId || `chat_${crypto.randomUUID()}`);
  const controller = new AbortController();
  const externalSignal = options.signal;
  const abortFromParent = () => controller.abort();
  const finishRequest = activeChatRequests.begin(requestId, abortFromParent);
  if (externalSignal?.aborted) controller.abort();
  else externalSignal?.addEventListener('abort', abortFromParent, { once: true });
  try {
    if (controller.signal.aborted) throw new Error('AI request cancelled');
    const remember = options.remember !== false;
    if (remember) chatHistory.push({ role: 'user', text: clean, time: nowTime() });
    const result = await chatWithModel(clean, remember ? memories : [], {
      remember,
      history: remember ? chatHistory : chatHistory.slice(-16),
      requestId,
      sessionId: options.sessionId || '',
      source: options.source || 'chat',
      providerId: options.providerId || null,
      signal: controller.signal,
    });
    if (controller.signal.aborted) throw new Error('AI request cancelled');
    const reply = result.reply;
    if (remember) chatHistory.push({ role: 'assistant', text: reply, time: nowTime() });
    broadcast({ type: 'chat', requestId, source: options.source || 'chat', role: 'assistant', text: reply, time: nowTime() });
    progressMoteStory(`chat:${chatHistory.length}:${clean.slice(0, 48)}`, { conversationCount: 1 });
    addLog('success', `Mote 对话回复：${reply.slice(0, 100)}`);
    return { ok: true, reply };
  } finally {
    externalSignal?.removeEventListener('abort', abortFromParent);
    finishRequest();
  }
}

function buildSnapshotPayload() {
  const payload = {
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
    motes: buildMoteProjection(),
    autonomy: workspaceStore.getAutonomyPolicy(),
    approvals: workspaceStore.listToolApprovals().slice(0, 100),
  };
  payload.companionSummary = buildCompanionSummary({
    generatedAt: Date.now(),
    snapshot: payload,
    ai: aiProviderManager.getSettings(),
    memory: memoryStore.snapshot(),
    reality: { state: realityEngine.snapshot() },
  });
  return payload;
}

function buildSummaryPayload() {
  const tasks = publicTasks();
  const workspaceTasks = workspaceStore.listTasks();
  const attention = workspaceStore.listAttentionItems().slice(0, 100);
  const approvals = workspaceStore.listToolApprovals().slice(0, 100);
  const moteState = moteStore.getState();
  const moteRoster = moteStore.roster();
  const relationship = moteRelationshipStore.snapshot();
  const motes = buildMoteProjection();
  const autonomy = workspaceStore.getAutonomyPolicy();
  const workspace = {
    eventRevision: workspaceStore.eventRevision,
    emergencyStop: workspaceStore.emergencyStopState(),
    taskCount: workspaceTasks.length,
    attentionCount: attention.length,
    tasks: workspaceTasks.slice(0, 12),
    attention,
    approvals: approvals.slice(0, 6),
  };
  const summaryInput = {
    tasks,
    attention,
    deviceHealth: deviceHealthStore.snapshot(),
    workspace,
    motes,
    autonomy,
  };
  return {
    ok: true,
    view: 'summary',
    revision: workspaceStore.eventRevision,
    stats: statsSnapshot(),
    tasks: tasks.slice(0, 12),
    deviceHealth: summaryInput.deviceHealth,
    workspace,
    motes,
    autonomy,
    companionSummary: buildCompanionSummary({
      generatedAt: Date.now(),
      snapshot: summaryInput,
      ai: aiProviderManager.getSettings(),
      memory: memoryStore.snapshot(),
      reality: { state: realityEngine.snapshot() },
    }),
  };
}

function snapshotPayload() {
  return snapshotCache.get('full');
}

function summaryPayload() {
  return snapshotCache.get('summary');
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

function goalCategoryRevisionError(category, revision) {
  const requiredRevision = privacyCenter.categoryRevision(category);
  if (privacyCenter.isMigrationRequired(category)) {
    return { status: 409, body: { ok: false, code: `privacy_migration_required:${category}`, error: 'goal data migration requires a user decision', retryable: true, requiredPrivacyRevision: requiredRevision } };
  }
  if (revision === undefined && requiredRevision > 0) {
    return { status: 409, body: { ok: false, code: `privacy_revision_required:${category}`, error: 'privacy revision is required', retryable: true, requiredPrivacyRevision: requiredRevision } };
  }
  if (revision !== undefined && (!Number.isSafeInteger(revision) || revision !== requiredRevision)) {
    const code = Number.isSafeInteger(revision) && revision < requiredRevision
      ? `privacy_revision_stale:${category}`
      : `privacy_revision_mismatch:${category}`;
    return { status: 409, body: { ok: false, code, error: 'privacy revision does not match the current category revision', retryable: true, requiredPrivacyRevision: requiredRevision } };
  }
  return null;
}

function sendGoalError(res, error) {
  const message = String(error?.message || '');
  const status = Number(error?.statusCode) ||
    (/not found/i.test(message) ? 404 :
      (/active goal task|running goal task|already bound to different input/i.test(message) ? 409 : 500));
  const code = String(error?.code || (status === 404 ? 'goal_not_found' : status === 409 ? 'goal_conflict' : status >= 500 ? 'goal_internal_error' : 'invalid_goal'));
  return sendJson(res, status, {
    ok: false,
    code,
    error: status >= 500 ? 'goal operation failed' : message,
    ...(status >= 500 ? { retryable: true } : {}),
  });
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
    taskAudit: workspaceStore.listTasks().slice(0, 100).flatMap(task => workspaceStore.listTaskAudit(task.id)),
    eventRevision: workspaceStore.eventRevision,
    emergencyStop: workspaceStore.emergencyStopState(),
    autonomy: workspaceStore.getAutonomyPolicy(),
    moteRelationship: moteRelationshipStore.snapshot(),
    moteQuests: moteQuestStore.list(),
    motes: buildMoteProjection(),
    timeline: workspaceTimeline.getSnapshot(),
  };
}

workspaceTimeline.seedSnapshot({
  tasks: workspaceStore.listTasks(),
  attention: workspaceStore.listAttentionItems(),
  mote: {
    profileId: moteStore.getState().activeId || 'rimuru',
    name: moteStore.roster().find(item => item.active)?.name || '利姆鲁',
    active: true,
    ...moteRelationshipStore.snapshot()
  },
  health: {
    connected: deviceHealthStore.snapshot().bridge === 'connected',
    ...phoneTelemetry
  },
  autonomy: {
    ...workspaceStore.getAutonomyPolicy(),
    emergencyStop: workspaceStore.emergencyStopState().active,
    pendingApprovals: workspaceStore.listToolApprovals().filter(item => item.state === 'needs_confirmation').length
  }
});

async function runWorkspaceMessage(sessionId, message, taskId, memories, remember = true) {
  const task = workspaceStore.getTask(taskId);
  if (!task) throw new Error('task not found');
  taskRunner.enqueue({ ...task, metadata: { ...task.metadata, sessionId, text: message.text, memories, remember } });
  return taskRunner.get(taskId);
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

const html = `<!doctype html><html lang="zh-CN" data-theme="${DEFAULT_THEME_ID}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>Mote · PhoneBridge</title>
<script>try{const ids=${WEB_THEME_IDS};const saved=localStorage.getItem('phonebridge:web:theme');document.documentElement.dataset.theme=ids.includes(saved)?saved:'${DEFAULT_THEME_ID}'}catch(_){document.documentElement.dataset.theme='${DEFAULT_THEME_ID}'}</script>
<style>
${WEB_THEME_CSS}
:root{--bg:var(--background-top);--line:var(--stroke);--mint:var(--success);--amber:var(--warning);--coral:var(--danger);--text:var(--text-primary);--muted:var(--text-secondary)}
*{box-sizing:border-box}[hidden]{display:none!important}html,body{min-height:100%;margin:0;background:linear-gradient(155deg,var(--background-top),var(--background-bottom));color:var(--text);font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}body{min-height:100svh}.app-shell{min-height:100svh;overflow:hidden}.app-top{height:72px;max-width:1440px;margin:auto;padding:12px 26px;display:flex;align-items:center;gap:12px;position:relative;z-index:2}.brand{display:flex;align-items:baseline;gap:12px}.logo{font-size:23px;font-weight:750;letter-spacing:.08em;color:var(--text-primary)}.sub{color:var(--muted);font-size:12px}.pill{border:1px solid var(--line);background:var(--panel);border-radius:99px;padding:7px 13px;font-size:12px;white-space:nowrap}.header-actions{margin-left:auto;display:flex;gap:8px;align-items:center}
button,input,select,textarea{font:inherit}button,select,input{min-height:44px;background:var(--button-fill);border:1px solid var(--line);color:var(--button-text);border-radius:12px;padding:9px 13px}button{cursor:pointer;transition:transform 140ms ease-out,border-color 160ms ease-out,background-color 160ms ease-out}button:active{transform:scale(.98)}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:3px solid var(--focus);outline-offset:2px}.primary{background:var(--accent);border-color:var(--accent);color:var(--background-top);font-weight:700}.quiet-button{background:transparent}.wrap{max-width:1280px;margin:auto;padding:0 8px 20px}.grid{display:grid;grid-template-columns:minmax(280px,380px) minmax(340px,1fr);gap:16px;margin-top:14px}.panel{min-width:0;background:linear-gradient(160deg,var(--panel),color-mix(in srgb,var(--panel) 82%,var(--background-top)));border:1px solid var(--line);border-radius:18px;padding:16px}.panel h2{font-size:15px;margin:0 0 12px;color:var(--text-primary)}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.metric{min-width:0;background:var(--card);border-radius:12px;padding:11px}.metric b{display:block;color:var(--accent)}.metric span{font-size:11px;color:var(--muted)}
.tabs{display:flex;gap:6px;margin-bottom:10px}.tabs button{flex:1;background:var(--card);color:var(--button-text);border:1px solid var(--line);border-radius:9px}.tabs button.active{background:var(--button-pressed);color:var(--accent)}
#log,#tasks,#sensors{height:min(48vh,430px);overflow:auto;padding-right:6px}.item{border-left:2px solid var(--accent);padding:6px 9px;margin-bottom:6px;background:var(--card);white-space:pre-wrap}.taskbar{height:4px;background:var(--card);margin-top:5px}.taskbar i{display:block;height:100%;background:var(--success)}.row{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}input,select{min-width:0}textarea{width:100%;min-height:110px;background:var(--input);color:var(--text-primary);border:1px solid var(--line);border-radius:12px;padding:10px;font:12px ui-monospace}#frame{width:100%;aspect-ratio:3/2;object-fit:cover;border-radius:16px;border:2px solid var(--accent)}
.routine-goal-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:14px}.workspace-card{min-width:0;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px}.workspace-card h3{margin:0 0 8px;font-size:14px;color:var(--accent)}.workspace-card input,.workspace-card textarea{max-width:100%}.workspace-card textarea{min-height:64px;resize:vertical}.routine-actions,.goal-actions{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}.routine-actions button,.goal-actions button{padding:6px 9px}.goal-step{border:1px solid var(--line);border-radius:10px;padding:10px;margin:8px 0}.goal-step input,.goal-step textarea{width:100%;margin-top:6px}.privacy-checklist{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:6px;margin-top:10px}.privacy-check{display:flex;align-items:center;gap:8px;background:var(--card);border:1px solid var(--line);border-radius:9px;padding:8px}.privacy-check input{accent-color:var(--accent)}.status-note{min-height:18px;margin-top:6px;color:var(--warning);font-size:12px;white-space:pre-wrap}.status-note.error{color:var(--danger)}
.immersive-stage{height:calc(100svh - 72px);min-height:460px;max-height:900px;position:relative;isolation:isolate;display:grid;place-items:center;overflow:hidden;background:radial-gradient(ellipse at 50% 54%,color-mix(in srgb,var(--accent) 13%,transparent),transparent 44%),linear-gradient(180deg,transparent 58%,color-mix(in srgb,var(--secondary) 7%,transparent));}.stage-horizon{position:absolute;inset:12% 5% 5%;border:1px solid color-mix(in srgb,var(--accent) 13%,transparent);border-radius:50%;transform:perspective(420px) rotateX(67deg);box-shadow:0 0 80px color-mix(in srgb,var(--accent) 8%,transparent);pointer-events:none}.stage-radar{position:absolute;width:min(76vw,620px);aspect-ratio:1;border:1px solid color-mix(in srgb,var(--secondary) 17%,transparent);border-radius:50%;opacity:.72;pointer-events:none;background:repeating-radial-gradient(circle,transparent 0 19%,color-mix(in srgb,var(--secondary) 7%,transparent) 19.15% 19.35%);mask-image:linear-gradient(to bottom,transparent 8%,black 36%,transparent 91%)}.stage-radar:after{content:"";position:absolute;inset:6%;border-radius:50%;border-top:1px solid color-mix(in srgb,var(--accent) 48%,transparent);transform:rotate(-28deg)}.stage-copy{position:absolute;left:clamp(20px,7vw,110px);top:clamp(24px,8vh,84px);z-index:1;max-width:min(360px,38vw)}.stage-eyebrow{color:var(--accent);font-size:11px;letter-spacing:.22em;text-transform:uppercase}.stage-copy h1{margin:8px 0 3px;font-size:clamp(26px,4vw,42px);letter-spacing:.04em}.stage-copy p{margin:0;color:var(--muted)}.stage-status{margin-top:14px;display:inline-flex;align-items:center;gap:8px;padding:7px 11px;border:1px solid var(--line);border-radius:99px;background:var(--panel);font-size:12px}.status-dot{width:7px;height:7px;border-radius:50%;background:var(--success);box-shadow:0 0 12px currentColor}.stage-status[data-online="false"] .status-dot{background:var(--warning)}.stage-offline{display:block;max-width:300px;margin-top:9px;color:var(--warning);font-size:12px}.mote-visual{position:relative;z-index:1;width:min(65vw,600px);height:min(66vh,510px);display:grid;place-items:center;animation:mote-drift 5s ease-in-out infinite;filter:drop-shadow(0 0 24px color-mix(in srgb,var(--mote-primary,var(--accent)) 18%,transparent))}.mote-svg{width:100%;height:100%;overflow:visible}.mote-silhouette{transform-origin:240px 180px;animation:mote-breathe 3.6s ease-in-out infinite;transform:translateY(calc(var(--motion-intensity)*-1px))}.stage-actions{position:absolute;bottom:clamp(24px,6vh,58px);left:0;right:0;display:flex;justify-content:center;gap:10px;z-index:2}.stage-actions button{min-width:132px}.stage-help{position:absolute;bottom:14px;left:0;right:0;text-align:center;color:var(--muted);font-size:11px;pointer-events:none}
.drawer-scrim{position:fixed;z-index:4;inset:0;border:0;border-radius:0;background:rgba(0,0,0,.55);opacity:0;transition:opacity 220ms ease-out}body.drawer-open .drawer-scrim{opacity:1}.command-drawer{position:fixed;z-index:5;top:16px;right:16px;bottom:16px;width:min(760px,calc(100vw - 32px));overflow:auto;overscroll-behavior:contain;padding:18px;background:color-mix(in srgb,var(--background-top) 94%,transparent);border:1px solid var(--line);border-radius:20px;box-shadow:0 24px 90px rgba(0,0,0,.56);visibility:hidden;transform:translateX(calc(100% + 22px));transition:transform 240ms cubic-bezier(.32,.72,0,1),visibility 240ms}.command-drawer.open{visibility:visible;transform:translateX(0)}.drawer-bar{display:flex;align-items:center;gap:14px;position:sticky;top:-18px;z-index:3;margin:-18px -18px 12px;padding:12px 18px;background:var(--background-top);border-bottom:1px solid var(--line)}.drawer-bar h1{margin:0;font-size:18px}.drawer-bar p{margin:0;color:var(--muted);font-size:12px}.drawer-close{margin-left:auto;min-width:48px}.appearance-settings{scroll-margin-top:74px;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:14px;margin-bottom:14px}.appearance-settings h2{margin:0 0 8px;font-size:14px}.appearance-settings label{display:flex;align-items:center;gap:10px;margin-top:8px;color:var(--muted)}.appearance-settings input[type=checkbox]{width:20px;min-width:20px;min-height:20px;accent-color:var(--accent)}.appearance-settings .sub{display:block;margin-top:5px}
@keyframes mote-drift{0%,100%{transform:translateY(0)}50%{transform:translateY(-7px)}}@keyframes mote-breathe{0%,100%{scale:1}50%{scale:1.025}}.mote-silhouette[data-motion="active"]{animation-duration:2.8s}.mote-silhouette[data-motion="steady"]{animation-duration:3.7s}.mote-silhouette[data-motion="soft"]{animation-duration:4.7s}
.appearance-settings label{min-height:44px}
button{transition:transform 140ms cubic-bezier(.23,1,.32,1),border-color 160ms ease,background-color 160ms ease}.drawer-scrim{transition:opacity 220ms cubic-bezier(.23,1,.32,1)}
@media(max-width:850px){.grid{grid-template-columns:1fr}.immersive-stage{min-height:420px}.stage-copy{left:6vw;top:5vh;max-width:48vw}.mote-visual{width:min(90vw,540px);height:min(62vh,460px)}.command-drawer{inset:0;width:100%;border-radius:0;border:0;padding:14px}.drawer-bar{top:-14px;margin:-14px -14px 12px;padding:12px 14px}}
@media(max-width:600px){.app-top{height:64px;padding:8px 12px}.brand{display:block}.logo{font-size:18px;line-height:1.15}.brand .sub{font-size:10px}.header-actions{gap:5px}.header-actions button{padding-inline:9px}.pill{padding:6px 9px;font-size:11px}.immersive-stage{height:calc(100svh - 64px);min-height:420px}.stage-copy{left:18px;top:22px;max-width:70vw}.stage-copy h1{font-size:28px}.stage-radar{width:104vw;top:18%}.mote-visual{width:104vw;height:min(60vh,420px);transform:translateY(28px)}.stage-actions{bottom:34px}.stage-actions button{min-width:0;flex:1;max-width:165px;padding-inline:9px}.stage-help{bottom:10px}.wrap{padding:0 0 18px}.panel{padding:13px}.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.routine-goal-grid{grid-template-columns:1fr}.command-drawer .row>*{max-width:100%}}
@media(max-width:370px){.header-actions #status{display:none}.stage-actions{gap:7px}.stage-actions button{font-size:12px}}
@media(prefers-reduced-motion:reduce){.mote-visual,.mote-silhouette{animation:none!important}.drawer-scrim{transition:opacity 120ms ease-out!important}.command-drawer{opacity:0;transform:translateX(0)!important;transition:opacity 120ms ease-out,visibility 0s linear 120ms!important}.command-drawer.open{opacity:1;transition:opacity 120ms ease-out,visibility 0s!important}button{transition:background-color 120ms ease-out,border-color 120ms ease-out!important}button:active{transform:none}}
:root[data-reduce-motion="true"] .mote-visual,:root[data-reduce-motion="true"] .mote-silhouette{animation:none!important}
@media(hover:hover) and (pointer:fine){button:hover{border-color:var(--focus)}}
body.keyboard-action .drawer-scrim,body.keyboard-action .command-drawer,body.keyboard-action button{transition:none!important}
</style><div class="app-shell">
<header class="app-top"><div class="brand"><div class="logo">Mote</div><div class="sub">PhoneBridge · 感官同伴</div></div><div class="header-actions"><div class="pill" id="status" aria-live="polite">连接中</div><button id="appearanceButton" class="quiet-button" aria-label="打开外观设置">外观</button><button id="openDrawer" class="primary" aria-haspopup="dialog" aria-controls="commandDrawer">指挥台</button><button onclick="logout()" class="quiet-button">退出</button></div></header>
<main class="immersive-stage" id="moteStage" role="region" aria-labelledby="stageName"><div class="stage-horizon" aria-hidden="true"></div><div class="stage-radar" aria-hidden="true"></div><div class="stage-copy"><div class="stage-eyebrow">PHONEBRIDGE · COMPANION LINK</div><h1 id="stageName">星核</h1><p id="stageVoice">正在读取伙伴状态…</p><div class="stage-status" id="stageConnection" data-online="false"><span class="status-dot" aria-hidden="true"></span><span id="stageConnectionText">连接中</span></div><span class="stage-offline" id="stageOffline" role="status" aria-live="polite" hidden></span></div><div class="mote-visual" id="moteVisual" aria-hidden="true"></div><div class="stage-actions"><button class="primary" id="stageChatButton">打开指挥台</button><button id="stageAppearanceButton" class="quiet-button">外观与动效</button></div><div class="stage-help">伙伴舞台 · 控制与复杂操作收在指挥台中</div></main>
<button class="drawer-scrim" id="drawerScrim" aria-label="关闭指挥台" hidden></button><aside class="command-drawer" id="commandDrawer" role="dialog" aria-modal="true" aria-labelledby="drawerTitle" aria-hidden="true" inert><div class="drawer-bar"><div><h1 id="drawerTitle">指挥台</h1><p>任务、设备、探索与伙伴设置</p></div><button id="closeDrawer" class="drawer-close" aria-label="关闭指挥台">关闭</button></div><section class="appearance-settings" id="appearanceSettings" aria-labelledby="appearanceTitle"><h2 id="appearanceTitle">外观与动效</h2><label for="themeSelect">主题</label><select id="themeSelect" aria-label="选择主题">${WEB_THEME_OPTIONS}</select><label><input id="reduceMotionToggle" type="checkbox">减弱舞台动画</label><span class="sub">主题与动效偏好只保存在此浏览器；系统减弱动态效果设置始终生效。</span></section><div class="wrap">
<div class="grid"><div class="panel"><h2>实时感官</h2><img id="frame"><div class="metrics" style="margin-top:12px"><div class="metric"><b id="cpu">-</b><span>手机 CPU</span></div><div class="metric"><b id="mem">-</b><span>内存</span></div><div class="metric"><b id="bat">-</b><span>电量</span></div><div class="metric"><b id="temp">-</b><span>温度</span></div></div><div class="row"><button class="primary" onclick="device('camera_on')">开眼</button><button onclick="device('camera_front')">前眼</button><button onclick="device('camera_back')">后眼</button><button onclick="device('listen_on')">监听</button><button onclick="say()">说话</button></div><div class="row"><input id="speech" placeholder="输入要在手机上播放的话" style="flex:1"></div><div class=row><select id=idleTimeout title="空闲断流时间"><option value=1>1 分钟</option><option value=3>3 分钟</option><option value=5 selected>5 分钟</option><option value=10>10 分钟</option><option value=30>30 分钟</option></select><button onclick=setIdleTimeout()>空闲断流</button></div><div class=row><select id=screenOffTimeout title="息屏自动退出时间"><option value=0>不自动退出</option><option value=1>1 分钟</option><option value=3>3 分钟</option><option value=5>5 分钟</option><option value=10 selected>10 分钟</option><option value=30>30 分钟</option><option value=60>60 分钟</option></select><button onclick=setScreenOffTimeout()>息屏退出</button></div></div>
<div class="panel"><h2>指挥台</h2><div class="tabs"><button class="active" data-tab="tasks">任务</button><button data-tab="log">日志</button><button data-tab="sensors">传感器</button><button data-tab="frame">画面</button></div><div id="tasks"></div><div id="log" hidden></div><div id="sensors" hidden></div><div id="framebox" hidden><img id="frame2"></div><div class="row"><input id="cmd" placeholder="help / ping 8.8.8.8 / screenshot / ps / say 你好" style="flex:1"><button class="primary" onclick="sendCmd()">执行</button></div><textarea id="detail" readonly placeholder="选中任务的输出会出现在这里"></textarea></div></div>
<div class="panel" style="grid-column:1/-1"><h2>工作台 · Mote 图鉴 · 自治 · 诊断与时间线</h2><div id="diagnosticsSummary" class="sub" style="color:var(--mint);margin-bottom:6px">诊断数据加载中…</div><div id="workspaceSummary" class="sub">加载中…</div><div id="companionSummary" class="sub" style="margin-top:8px;color:var(--amber)">统一伴侣摘要加载中…</div><div class="row"><select id="aiProviderSelect" style="min-width:180px"></select><button onclick="probeSelectedProvider()">探测 Provider</button><span id="aiProbeResult" class="sub" style="align-self:center"></span></div><div id="moteRoster" class="row" style="flex-wrap:wrap"></div><div class="row"><button class="primary" onclick="stopAutonomy()">Emergency Stop</button><button onclick="refreshWorkspace()">刷新工作台</button></div>
<div id="dailyWorkspace" class="routine-goal-grid"><section class="workspace-card"><h3>日常与习惯</h3><div id="routineStatus" class="status-note" role="status" aria-live="polite">日常记录加载中…</div><div id="routineList"></div></section><section class="workspace-card"><h3>个人目标</h3><label class="sub" for="goalTitle">写下一个想推进的目标</label><div class="row"><input id="goalTitle" maxlength="120" placeholder="例如：完成一个小型作品" style="flex:1;min-width:0"></div><textarea id="goalDescription" maxlength="2000" placeholder="可选说明；仅在点击生成草案时发给当前 provider"></textarea><div class="goal-actions"><button id="goalCreateButton" class="primary" onclick="createGoalFromWeb()">创建目标</button><button onclick="refreshDailyWorkspace()">刷新日常与目标</button></div><div id="goalStatus" class="status-note" role="status" aria-live="polite">目标加载中…</div><div id="goalList"></div></section></div><div id="goalDraftPanel" class="workspace-card" style="margin-top:12px" hidden></div></div>
<div class="panel" style="grid-column:1/-1"><h2>手机安全配对</h2><div class="row"><button onclick="startPairing()">生成五分钟二维码</button><span id="pairingStatus" class="sub" aria-live="polite">在手机“节点”中选择“扫码配对”</span></div><img id="pairingQr" alt="手机配对二维码" style="display:none;width:min(300px,100%);margin-top:12px;background:white;border-radius:12px;padding:8px"></div>
<div class="panel" id="realityPanel" style="grid-column:1/-1"><h2>现实探索</h2><div class="sub">只输入粗区域 ID，不上传精确位置；例如 <code>cell:1561:6073</code>。</div><div class="row"><input id="realityRegion" placeholder="粗区域 ID" style="flex:1"><button class="primary" onclick="refreshReality()">刷新事件</button></div><div id="realitySummary" class="sub" style="margin-top:8px">尚未加载现实事件</div></div>
<div class="panel" style="grid-column:1/-1"><h2>探索记录</h2><div id="realityLogStatus" class="status-note" role="status" aria-live="polite">探索记录加载中…</div><div id="realityLogList" aria-label="分页探索记录"></div><div class="row"><button id="realityLogMore" onclick="refreshRealityLog({cursor:realityLogCursor})" hidden>加载更早记录</button></div><div id="realityLogDetail" class="workspace-card" style="margin-top:10px" aria-live="polite">选择一条记录查看安全收据详情。</div></div>
<div class="panel" style="grid-column:1/-1"><h2>隐私与数据</h2><div id="privacySummary" class="sub">数据概览加载中…</div><div id="privacyCategories" class="privacy-checklist" aria-label="选择要处理的数据类别"></div><div id="privacyMigration" class="sub" role="status" style="margin-top:8px"></div><div class="sub" style="margin-top:8px">导出使用口令加密；口令仅本次请求使用。删除需输入确认语句，服务端只保留类别与结果收据。请在上方逐项勾选类别；日常与个人目标可单独导出或删除。</div><div class="row"><button class="primary" onclick="exportPrivacy()">导出选定类别</button><button onclick="deletePrivacy()">删除选定类别</button><button onclick="refreshPrivacy()">刷新概览</button></div><div id="privacyResult" class="sub" aria-live="polite" style="margin-top:8px"></div></div>
</div></aside></div>
<script>${MOTE_STAGE_SCRIPT}</script><script>
let selected='';
let companionSummaryRevision = '';
let currentCompanionSummary = null;
let providerOptionsRevision = '';
let privacyCategoryIds = [];
let dailyRoutineSnapshot = { catalog: [], current: [], history: [], privacyRevision: 0, migrationRequired: false };
let dailyGoalSnapshot = { goals: [], privacyRevision: 0, taskPrivacyRevision: 0, migrationRequired: false };
let routineRenderSignature = '';
let goalRenderSignature = '';
let dailyRefreshPromise = null;
let activeGoalDraft = null;
let realityLogEntries = [];
let realityLogCursor = null;
let realityLogSelectedId = '';
let realityLogGeneration = 0;
const routineRetryPayloads = new Map();
const routineInFlight = new Set();
const goalDraftInFlight = new Set();
const goalContextValues = new Map();
let goalCreateInFlight = false;
function webEventId(prefix){const random=globalThis.crypto?.randomUUID?.()||Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);return prefix+'-'+random.replace(/[^A-Za-z0-9_-]/g,'')}
function esc(s){return String(s??'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
function jsAttr(s){return JSON.stringify(String(s??'')).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function sel(id){selected=id;let t=(window.TASKS||{})[id];detail.value=t?t.detail:''}
function tab(name,b){document.querySelectorAll('.tabs button').forEach(x=>x.classList.remove('active'));b.classList.add('active');['tasks','log','sensors','frame'].forEach(x=>document.getElementById(x).hidden=x!==name)}
document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>tab(b.dataset.tab,b));

const etags = new Map();
async function api(p,o={}){
  const options={...o};
  const method=String(options.method||'GET').toUpperCase();
  const headers=new Headers(options.headers||{});
  if(method==='GET' && etags.has(p)) headers.set('If-None-Match', etags.get(p));
  options.headers=headers;
  let response=await fetch(p,options);
  if(response.status===304) return {_status:304};
  if(response.status===401) location.reload();
  if(!response.ok){let problem={};try{problem=await response.json()}catch(_){}const error=new Error(problem.error||('HTTP '+response.status));error.status=response.status;error.code=problem.code||'http_error';error.retryable=problem.retryable===true;throw error}
  const etag=response.headers.get('etag');
  if(method==='GET' && etag) etags.set(p,etag);
  return response.json();
}
const webThemeIds=${WEB_THEME_IDS};
const themeSelect=document.getElementById('themeSelect');
const reduceMotionToggle=document.getElementById('reduceMotionToggle');
function applyWebTheme(id,persist=true){
  const theme=webThemeIds.includes(id)?id:'${DEFAULT_THEME_ID}';
  document.documentElement.dataset.theme=theme;
  if(themeSelect)themeSelect.value=theme;
  if(persist){try{localStorage.setItem('phonebridge:web:theme',theme)}catch(_){}}
}
applyWebTheme(document.documentElement.dataset.theme,false);
themeSelect?.addEventListener('change',()=>applyWebTheme(themeSelect.value));
function applyReducedMotion(enabled,persist=true){
  document.documentElement.dataset.reduceMotion=enabled?'true':'false';
  if(reduceMotionToggle)reduceMotionToggle.checked=enabled;
  if(persist){try{localStorage.setItem('phonebridge:web:reduce-motion',enabled?'true':'false')}catch(_){}}
}
let savedReduceMotion=null;
try{savedReduceMotion=localStorage.getItem('phonebridge:web:reduce-motion')}catch(_){}
applyReducedMotion(savedReduceMotion==='true');
reduceMotionToggle?.addEventListener('change',()=>applyReducedMotion(reduceMotionToggle.checked));
const commandDrawer=document.getElementById('commandDrawer');
const drawerScrim=document.getElementById('drawerScrim');
let lastDrawerTrigger=null;
function setCommandDrawer(open,trigger=document.activeElement,focusAppearance=false){
  if(open){
    lastDrawerTrigger=trigger;
    drawerScrim.hidden=false;
    commandDrawer.inert=false;
    commandDrawer.setAttribute('aria-hidden','false');
    commandDrawer.classList.add('open');
    document.body.classList.add('drawer-open');
    const focusWhenVisible=()=>{
      if(!commandDrawer.classList.contains('open'))return;
      if(getComputedStyle(commandDrawer).visibility!=='visible'){requestAnimationFrame(focusWhenVisible);return;}
      if(focusAppearance)document.getElementById('appearanceSettings').scrollIntoView({block:'start'});
      (focusAppearance?themeSelect:document.getElementById('closeDrawer')).focus();
    };
    requestAnimationFrame(focusWhenVisible);
    return;
  }
  commandDrawer.classList.remove('open');
  commandDrawer.setAttribute('aria-hidden','true');
  commandDrawer.inert=true;
  document.body.classList.remove('drawer-open');
  setTimeout(()=>{if(!commandDrawer.classList.contains('open'))drawerScrim.hidden=true},245);
  if(lastDrawerTrigger?.isConnected)lastDrawerTrigger.focus();
}
document.getElementById('openDrawer').addEventListener('click',event=>setCommandDrawer(true,event.currentTarget));
document.getElementById('stageChatButton').addEventListener('click',event=>setCommandDrawer(true,event.currentTarget));
document.getElementById('appearanceButton').addEventListener('click',event=>setCommandDrawer(true,event.currentTarget,true));
document.getElementById('stageAppearanceButton').addEventListener('click',event=>setCommandDrawer(true,event.currentTarget,true));
document.getElementById('closeDrawer').addEventListener('click',()=>setCommandDrawer(false));
drawerScrim.addEventListener('click',()=>setCommandDrawer(false));
document.addEventListener('pointerdown',()=>document.body.classList.remove('keyboard-action'),true);
document.addEventListener('keydown',event=>{
  if(event.key==='Escape'||((event.key==='Enter'||event.key===' ')&&event.target?.closest?.('button,a,[role="button"]')))document.body.classList.add('keyboard-action');
  if(!commandDrawer.classList.contains('open'))return;
  if(event.key==='Escape'){event.preventDefault();setCommandDrawer(false);return}
  if(event.key!=='Tab')return;
  const items=[...commandDrawer.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])')]
    .filter(item=>item.offsetParent!==null);
  if(!items.length)return;
  const first=items[0],last=items[items.length-1];
  if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}
});
const MOTE_STAGE_CACHE_KEY='phonebridge:web:stage:v1';
const DEFAULT_STAGE_PROFILE={id:'mote',name:'星核',voice:'理性稳重',visualPreset:'star-core',colors:{primary:'#8ea7ff',secondary:'#d8e2ff'}};
function readMoteStageCache(){
  try{const value=JSON.parse(localStorage.getItem(MOTE_STAGE_CACHE_KEY)||'null');return value&&value.version===1&&value.profile?value:null}catch(_){return null}
}
let moteStageCache=readMoteStageCache();
let moteStageSignature='';
function renderMoteStage(profile,behavior,summary,offline,usedCache=false,initial=false){
  const safeProfile=profile||DEFAULT_STAGE_PROFILE;
  const level=Math.max(1,Number(summary?.mote?.level)||1);
  const online=summary?.connection?.online===true;
  const connection=document.getElementById('stageConnection');
  connection.dataset.online=online?'true':'false';
  document.getElementById('stageConnectionText').textContent=initial?'正在检查节点':online?'PhoneBridge 在线':'节点离线';
  document.getElementById('stageName').textContent=safeProfile.name||'星核';
  document.getElementById('stageVoice').textContent=(safeProfile.voice||'伙伴')+' · Lv.'+level;
  const offlineNote=document.getElementById('stageOffline');
  offlineNote.hidden=!offline||initial;
  offlineNote.textContent=offline?(usedCache?'连接暂不可用 · 正在显示上次缓存的伙伴状态':'节点暂时不可用 · 伙伴舞台仍可查看'):'';
  const renderer=window.renderMoteStageSvg;
  const stageKey=JSON.stringify([safeProfile,behavior,level,online,offline]);
  if(renderer&&moteStageSignature!==stageKey){
    document.getElementById('moteVisual').innerHTML=renderer(safeProfile,behavior||{});
    moteStageSignature=stageKey;
  }
}
renderMoteStage(moteStageCache?.profile||DEFAULT_STAGE_PROFILE,moteStageCache?.behavior||{},moteStageCache?.summary||null,true,Boolean(moteStageCache),true);
async function refreshMoteStage(){
  const cached=moteStageCache;
  const results=await Promise.allSettled([
    api('/api/motes'),api('/api/motes/behavior')
  ]);
  const fresh=(index)=>results[index].status==='fulfilled'&&results[index].value?results[index].value:null;
  const motesResponse=fresh(0),behaviorResponse=fresh(1);
  const summary=currentCompanionSummary||cached?.summary||null;
  const roster=motesResponse?.roster;
  const activeId=summary?.mote?.id||motesResponse?.state?.activeId||cached?.profile?.id;
  const profile=roster?.find(item=>item.id===activeId)||roster?.find(item=>item.active)||(cached?.profile?.id===activeId?cached.profile:null)||DEFAULT_STAGE_PROFILE;
  const behavior=(behaviorResponse?behaviorResponse.behavior:null)||motesResponse?.behavior||cached?.behavior||{};
  const requestsUnavailable=results.every(result=>result.status==='rejected');
  const offline=requestsUnavailable||!summary||summary.connection?.online!==true;
  const anyFresh=[motesResponse,behaviorResponse].some(value=>value&&value._status!==304);
  if(anyFresh&&profile){
    const snapshot={version:1,savedAt:Date.now(),profile:{id:profile.id,name:profile.name,voice:profile.voice,visualPreset:profile.visualPreset,colors:profile.colors},summary:summary?{mote:summary.mote,connection:summary.connection}:null,behavior};
    moteStageCache=snapshot;
    try{localStorage.setItem(MOTE_STAGE_CACHE_KEY,JSON.stringify(snapshot))}catch(_){}
  }
  const cacheUnavailable=requestsUnavailable&&Boolean(cached);
  renderMoteStage(profile,behavior,summary,offline,cacheUnavailable);
  if(results.every(result=>result.status==='rejected')&&!cached){
    renderMoteStage(DEFAULT_STAGE_PROFILE,{},null,true,false);
  }
}
refreshMoteStage();
setInterval(refreshMoteStage,5000);
async function logout(){
  await fetch('/logout', { method: 'POST' });
  location.reload();
}
async function sendCmd(){let c=cmd.value.trim();if(!c)return;await api('/api/command',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({text:c})});cmd.value='';refresh()}
async function device(a){await api('/api/device',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action:a})})}
async function say(){let t=speech.value.trim();if(t)await api('/say?text='+encodeURIComponent(t))}
setInterval(()=>{frame.src='/frame?'+Date.now();frame2.src='/frame?'+Date.now()},140);
setInterval(refresh,3000);setInterval(poll,3000);
async function refresh(){
  try{
    let s=await api('/api/state');
    if(s._status===304) return;
    const sensor=s.stats.sensors||{};
    window.TASKS={};s.tasks.forEach(x=>{TASKS[x.id]=x});
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
async function poll(){if(selected&&TASKS[selected])detail.value=TASKS[selected].detail||''}
async function setIdleTimeout(){
  await api('/api/idle-timeout',{method:'POST',headers:{'content-type':'application/json'},body:'{"minutes":'+idleTimeout.value+'}'});
  refresh();
}
async function setScreenOffTimeout(){
  await api('/api/screen-off-timeout',{method:'POST',headers:{'content-type':'application/json'},body:'{"minutes":'+screenOffTimeout.value+'}'});
  refresh();
}
window.taskViewMode = window.taskViewMode || 'all';
window.timelineCursor = window.timelineCursor || 0;

async function refreshDiagnosticsAndTimeline() {
  try {
    const diag = await api('/api/diagnostics');
    if (diag && diag.ok && document.getElementById('diagnosticsSummary')) {
      diagnosticsSummary.textContent = '诊断摘要: 启动耗时 ' + (diag.startupDurationMs||0) + 'ms · 同步延迟 ' + (diag.syncLatencyMs||0) + 'ms · 积压 ' + (diag.eventBacklog||0) + ' · 降级计数 ' + (diag.provider?.degradationCount||0) + ' · 目标 ' + (diag.performance?.fpsTarget||30) + 'fps (' + (diag.performance?.throttlingStrategy||'-') + ')';
    }
    const tl = await api('/api/workspace/timeline?cursor=' + window.timelineCursor + '&limit=10');
    if (tl && tl.ok && tl.cursor) {
      window.timelineCursor = tl.cursor;
    }
  } catch (_) {}
}
setInterval(refreshDiagnosticsAndTimeline, 5000);
refreshDiagnosticsAndTimeline();

async function refreshCompanionSummary(){
  try{
    const response=await api('/api/companion/summary');
    if(response._status===304 || !response.summary) return;
    const s=response.summary;
    currentCompanionSummary=s;
    const signature=JSON.stringify(s);
    if(companionSummaryRevision!==signature){
      companionSummaryRevision=signature;
      const growth=s.mote.growth||{};
      const daily=growth.daily||{};
      const clues=daily.clues||{};
      const clueCount=Number(clues.location||0)+Number(clues.object||0)+Number(clues.light||0);
      companionSummary.textContent=(s.mote.name||'Mote')+' Lv.'+s.mote.level+' · 探索 Lv.'+(growth.level||1)+' · 今日线索 '+clueCount+'/3 · '+(s.connection.online?'在线':'离线')+' · 任务 '+s.tasks.running+'/'+s.tasks.total+' · 待确认 '+s.tasks.needsConfirmation+' · 提醒 '+s.attention.open+' · 现实 '+s.reality.eventCount+' · AI '+s.ai.providerName+' · 记忆 '+s.ai.memoryCount+' · '+(s.safety.emergencyStop?'急停中':'自治 '+s.safety.autonomyLevel);
    }
    const providers=await api('/api/ai/providers');
    const providerSignature=JSON.stringify(providers.providers||[]);
    if(providerOptionsRevision!==providerSignature){
      providerOptionsRevision=providerSignature;
      aiProviderSelect.innerHTML=(providers.providers||[]).map(p=>'<option value="'+esc(p.id)+'" '+(p.isActive?'selected':'')+'>'+esc(p.name||p.id)+' · '+esc((p.capabilities||[]).join('/'))+'</option>').join('');
    }
  }catch(error){ companionSummary.textContent='伴侣摘要不可用：'+error.message; }
}
async function startPairing(){
  const qr=document.getElementById('pairingQr');
  const status=document.getElementById('pairingStatus');
  qr.style.display='none';qr.removeAttribute('src');
  try{
    const result=await api('/api/pairing/start',{method:'POST'});
    qr.src=result.qrImage;qr.style.display='block';
    status.textContent='请在手机扫码，二维码将在五分钟内过期。完成后此浏览器需要重新登录。';
    const expiresAt=result.offer.expiresAt;
    setTimeout(()=>{if(Date.now()>=expiresAt){qr.removeAttribute('src');qr.style.display='none';status.textContent='配对码已过期，请重新生成。'}},Math.max(0,expiresAt-Date.now())+100);
  }catch(error){status.textContent='无法生成配对码：'+error.message}
}
async function refreshPrivacy(){
  try{
    const result=await api('/api/privacy/overview');
    if(result._status===304)return;
    privacyCategoryIds=Object.keys(result.categories||{});
    const lines=Object.entries(result.categories||{}).map(([id,item])=>id+'：'+item.label+' '+item.count+' 条');
    privacySummary.textContent='可管理数据：'+lines.join(' · ')+'。排除：'+(result.excluded||[]).join('、');
    renderPrivacyCategories(result.categories||{});
    renderPrivacyMigration(result.migration,result.categories||{});
  }catch(error){privacySummary.textContent='数据概览读取失败：'+error.message}
}
function renderPrivacyCategories(categories){
  const root=document.getElementById('privacyCategories');if(!root)return;
  const selected=new Set([...root.querySelectorAll('input:checked')].map(input=>input.value));
  const ids=Object.keys(categories);
  if(root.dataset.ids!==JSON.stringify(ids)){
    root.replaceChildren();
    for(const id of ids){
      const item=categories[id]||{};
      const label=document.createElement('label');label.className='privacy-check';
      const checkbox=document.createElement('input');checkbox.type='checkbox';checkbox.value=id;checkbox.checked=selected.has(id);checkbox.setAttribute('aria-label',(item.label||id)+'（'+(item.count||0)+' 条）');
      const text=document.createElement('span');text.textContent=(item.label||id)+' · '+(Number(item.count)||0)+' 条';
      label.append(checkbox,text);root.appendChild(label);
    }
    root.dataset.ids=JSON.stringify(ids);
    return;
  }
  for(const label of root.querySelectorAll('.privacy-check')){
    const checkbox=label.querySelector('input');const item=categories[checkbox?.value]||{};
    if(checkbox)checkbox.setAttribute('aria-label',(item.label||checkbox.value)+'（'+(item.count||0)+' 条）');
    const text=label.querySelector('span');if(text)text.textContent=(item.label||checkbox?.value||'')+' · '+(Number(item.count)||0)+' 条';
  }
}
function renderPrivacyMigration(migration,categories){
  const root=document.getElementById('privacyMigration');root.replaceChildren();
  const required=Array.isArray(migration?.requiredCategories)?migration.requiredCategories:[];
  const decisions=migration?.decisions||{};
  const pending=required.filter(id=>!['clear','keep'].includes(decisions[id]));
  if(!pending.length){root.textContent=migration?.status==='complete'?'历史隐私迁移已确认，数据同步正常。':'没有待确认的历史数据迁移。';return}
  const notice=document.createElement('div');notice.textContent='历史记录存在歧义；手机同步已暂停。逐类选择清除或保留并隔离：';root.appendChild(notice);
  for(const id of pending){
    const row=document.createElement('div');row.className='row';row.style.alignItems='center';
    const label=document.createElement('span');label.textContent=(categories[id]?.label||id)+' · 节点 '+(categories[id]?.count||0)+' 条';row.appendChild(label);
    for(const [decision,title] of [['clear','清除旧数据'],['keep','保留并隔离']]){
      const button=document.createElement('button');button.textContent=title;
      button.onclick=()=>resolvePrivacyMigration(id,decision);row.appendChild(button);
    }
    root.appendChild(row);
  }
}
async function resolvePrivacyMigration(category,decision){
  if(decision==='clear'&&!confirm('将清除节点上的“'+(category)+'”历史数据。此操作不可撤销，继续吗？'))return;
  try{
    const result=await api('/api/privacy/migration/resolve',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({category,decision})});
    privacyResult.textContent=(decision==='clear'?'已清除并确认迁移：':'已保留并隔离确认：')+category+' · revision '+result.migration.categoryRevision;
    await refreshPrivacy();
  }catch(error){privacyResult.textContent='迁移确认未完成：'+error.message}
}
function selectedPrivacyCategories(){
  const ids=[...document.querySelectorAll('#privacyCategories input[type="checkbox"]:checked')].map(input=>input.value).filter(id=>privacyCategoryIds.includes(id));
  if(!ids.length){privacyResult.textContent='请先勾选至少一个数据类别。';return null}
  return [...new Set(ids)];
}
async function exportPrivacy(){
  let passphrase='';
  try{
    const categories=selectedPrivacyCategories();if(!categories)return;
    passphrase=prompt('设置本次导出加密口令（至少 12 个字符；请自行妥善保存）：')||'';
    if(passphrase.length<12)throw new Error('口令至少需要 12 个字符');
    const repeated=prompt('再次输入加密口令：')||'';
    if(passphrase!==repeated)throw new Error('两次口令不一致');
    const result=await api('/api/privacy/export',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({categories,passphrase})});
    const blob=new Blob([JSON.stringify(result.archive)],{type:'application/json'});
    const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='phonebridge-private-export-'+new Date().toISOString().replace(/[:.]/g,'-')+'.pbenc.json';link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000);
    privacyResult.textContent='已生成加密档案；没有向服务端或浏览器存储口令。';
  }catch(error){privacyResult.textContent='导出失败：'+error.message}
  finally{passphrase=''}
}
async function deletePrivacy(){
  try{
    const categories=selectedPrivacyCategories();if(!categories)return;
    const phrase=prompt('此操作不可撤销。输入 DELETE SELECTED DATA 确认删除所选类别：');
    if(phrase===null)return;
    const result=await api('/api/privacy/delete',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({requestId:webEventId('web'),categories,confirmation:phrase})});
    privacyResult.textContent='删除已完成：'+result.receipt.categories.join('、')+'。收据 '+result.receipt.requestId;
    if(result.receipt.categories.includes('progress')){
      for(const key of [...etags.keys()])if(key.startsWith('/api/reality/log?'))etags.delete(key);
      realityLogEntries=[];realityLogCursor=null;realityLogSelectedId='';renderRealityLog();
      const region=document.getElementById('realityRegion');if(region)region.value='';
      const summary=document.getElementById('realitySummary');if(summary)summary.textContent='成长与探索数据已清除。';
      await refreshRealityLog({reset:true});
    }
    await refreshPrivacy();
  }catch(error){privacyResult.textContent='删除未完成：'+error.message}
}
function workspaceStatus(id,message,error=false){const node=document.getElementById(id);if(!node)return;node.className='status-note'+(error?' error':'');node.textContent=message}
function routineElapsedSeconds(entry){if(!entry)return 0;const stored=Math.max(0,Number(entry.elapsedSeconds)||0);if(entry.status!=='active')return stored;const last=Date.parse(entry.lastEventAt||entry.updatedAt||entry.startedAt);return stored+(Number.isFinite(last)?Math.max(0,Math.floor((Date.now()-last)/1000)):0)}
function routineStatusLabel(entry){if(!entry)return '未开始';return ({active:'进行中',paused:'已暂停',finished:'已完成',skipped:'已跳过',interrupted:'已中断'})[entry.status]||'状态未知'}
function makeWorkspaceButton(title,handler,primary=false,disabled=false){const button=document.createElement('button');button.type='button';button.textContent=title;if(primary)button.className='primary';button.disabled=disabled;button.addEventListener('click',handler);return button}
function renderRoutineWorkspace(){
  const root=document.getElementById('routineList');if(!root)return;root.replaceChildren();
  const currentById=new Map((dailyRoutineSnapshot.current||[]).map(entry=>[entry.routineId,entry]));
  const blocked=dailyRoutineSnapshot.migrationRequired;
  for(const activity of dailyRoutineSnapshot.catalog||[]){
    const entry=currentById.get(activity.id);const retry=routineRetryPayloads.get(activity.id);const busy=routineInFlight.has(activity.id);const card=document.createElement('div');card.className='item';
    const title=document.createElement('b');title.textContent=activity.title||activity.id;card.appendChild(title);
    const description=document.createElement('div');description.className='sub';description.textContent=(activity.description||'')+' · '+routineStatusLabel(entry);card.appendChild(description);
    if(entry){
      const elapsed=document.createElement('div');elapsed.className='sub';elapsed.dataset.routineElapsed=activity.id;elapsed.textContent='已记录 '+routineElapsedSeconds(entry)+' 秒 · revision '+entry.revision;card.appendChild(elapsed);
      if(activity.id==='bedtime-review'&&entry.status!=='finished'){
        const reflection=document.createElement('textarea');reflection.id='routine-reflection-'+activity.id;reflection.maxLength=1000;reflection.placeholder='可选睡前回顾，仅在点击“完成”时提交';card.appendChild(reflection);
      }
    }
    const actions=document.createElement('div');actions.className='routine-actions';
    const allowed=!entry||['finished','skipped','interrupted'].includes(entry.status)?['start']:entry.status==='active'?['pause','finish','skip','interrupt']:['resume','finish','skip','interrupt'];
    const labels={start:'开始',pause:'暂停',resume:'继续',finish:'完成',skip:'跳过',interrupt:'中断'};
    for(const action of allowed)actions.appendChild(makeWorkspaceButton(labels[action],()=>routineAction(activity.id,action),action==='start'||action==='finish',blocked||busy||Boolean(retry)));
    if(retry)actions.appendChild(makeWorkspaceButton('重试上次同步',()=>sendRoutineEvent(activity.id,retry),false,blocked||busy));
    card.appendChild(actions);root.appendChild(card);
  }
  const history=(dailyRoutineSnapshot.history||[]).slice(0,3);
  if(history.length){const recent=document.createElement('div');recent.className='sub';recent.textContent='最近记录：'+history.map(entry=>(entry.title||entry.routineId)+' · '+routineStatusLabel(entry)).join('；');root.appendChild(recent)}
  if(blocked)workspaceStatus('routineStatus','日常数据迁移待确认，当前仅可查看；请先在 Android 隐私迁移流程中逐类确认。',true);
  else workspaceStatus('routineStatus','节点记录已同步 · revision '+dailyRoutineSnapshot.revision+' · 活动时间仅在前台操作时提交。');
  renderRoutineTimers();
}
function renderRoutineTimers(){for(const node of document.querySelectorAll('[data-routine-elapsed]')){const entry=dailyRoutineSnapshot.current.find(item=>item.routineId===node.dataset.routineElapsed);if(entry)node.textContent='已记录 '+routineElapsedSeconds(entry)+' 秒 · revision '+entry.revision}}
function routineAction(routineId,action){
  if(dailyRoutineSnapshot.migrationRequired)return workspaceStatus('routineStatus','日常数据迁移待确认，暂不能修改。',true);
  const entry=dailyRoutineSnapshot.current.find(item=>item.routineId===routineId);const payload={eventId:webEventId('web-routine'),action,occurredAt:new Date().toISOString(),privacyRevision:dailyRoutineSnapshot.privacyRevision};
  if(action!=='start'&&entry)payload.elapsedSeconds=routineElapsedSeconds(entry);
  if(routineId==='bedtime-review'&&action==='finish'){const reflection=document.getElementById('routine-reflection-'+routineId);if(reflection&&reflection.value.trim())payload.reflection=reflection.value.trim()}
  return sendRoutineEvent(routineId,payload);
}
async function sendRoutineEvent(routineId,payload){
  if(routineInFlight.has(routineId))return;
  routineInFlight.add(routineId);renderRoutineWorkspace();
  try{
    const result=await api('/api/routines/'+encodeURIComponent(routineId)+'/events',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(payload)});
    routineInFlight.delete(routineId);routineRetryPayloads.delete(routineId);await refreshDailyWorkspace();renderRoutineWorkspace();workspaceStatus('routineStatus',(result.duplicate?'操作已确认（重复请求安全去重）':'操作已由节点确认')+' · revision '+result.revision);
  }catch(error){
    routineInFlight.delete(routineId);
    if(!Number.isFinite(error.status)||error.status>=500)routineRetryPayloads.set(routineId,payload);
    else routineRetryPayloads.delete(routineId);
    const message='日常操作未确认：'+error.message+(error.code?' · '+error.code:'')+(routineRetryPayloads.has(routineId)?'。可用同一事件 ID 安全重试。':'。刷新状态后再尝试。');
    renderRoutineWorkspace();
    if(String(error.code||'').startsWith('privacy_'))await refreshDailyWorkspace();
    workspaceStatus('routineStatus',message,true);
  }
}
function renderGoalWorkspace(){
  const root=document.getElementById('goalList');if(!root)return;root.replaceChildren();const blocked=dailyGoalSnapshot.migrationRequired;
  for(const goal of dailyGoalSnapshot.goals||[]){
    const card=document.createElement('div');card.className='item';const title=document.createElement('b');title.textContent=goal.title;card.appendChild(title);
    const description=document.createElement('div');description.className='sub';description.textContent=(goal.description||'')+' · '+(goal.status||'active');card.appendChild(description);
    for(const milestone of goal.milestones||[]){const row=document.createElement('div');row.className='sub';row.textContent=(milestone.status||'pending')+' · '+milestone.title;if(milestone.taskId){const taskButton=makeWorkspaceButton('打开关联任务',()=>selectTask(milestone.taskId));row.appendChild(document.createTextNode(' '));row.appendChild(taskButton)}card.appendChild(row)}
    const context=document.createElement('textarea');context.maxLength=1000;context.value=goalContextValues.get(goal.id)||'';context.placeholder='可选补充（最多 1000 字）；只在点击“生成草案”时提交给当前 provider';context.setAttribute('aria-label','目标补充上下文');context.addEventListener('input',()=>goalContextValues.set(goal.id,context.value));card.appendChild(context);
    const buttons=document.createElement('div');buttons.className='goal-actions';buttons.appendChild(makeWorkspaceButton('生成步骤草案',()=>requestGoalDraft(goal.id,context.value),true,blocked||goalDraftInFlight.has(goal.id)));card.appendChild(buttons);root.appendChild(card);
  }
  if(!(dailyGoalSnapshot.goals||[]).length){const empty=document.createElement('div');empty.className='sub';empty.textContent='还没有目标。创建后可选择是否让当前 AI provider 起草步骤。';root.appendChild(empty)}
  if(blocked)workspaceStatus('goalStatus','目标或任务隐私迁移待确认，当前仅可查看。',true);
  else if(['目标加载中…',''].includes(document.getElementById('goalStatus').textContent))workspaceStatus('goalStatus','目标 '+(dailyGoalSnapshot.goals||[]).length+' 个 · privacy revision '+dailyGoalSnapshot.privacyRevision);
  renderGoalDraft();
}
function renderGoalDraft(){
  const panel=document.getElementById('goalDraftPanel');if(!panel)return;panel.replaceChildren();const draft=activeGoalDraft;if(!draft){panel.hidden=true;return}panel.hidden=false;
  const heading=document.createElement('h3');heading.textContent='草案 · '+draft.goalTitle;panel.appendChild(heading);
  const provider=document.createElement('div');provider.className='sub';provider.textContent=draft.fallbackProvider?'当前 provider：'+(draft.providerId||'local')+'；已回退至本地规则（'+(draft.fallbackReason||'provider unavailable')+'）。':(draft.providerId==='local'?'由本地规则生成。':'当前 provider：'+(draft.providerId||'local')+'。');panel.appendChild(provider);
  const note=document.createElement('div');note.className='sub';note.textContent='草案仅保存在当前页面内存；修改并明确确认之前，不创建任务。刷新页面会丢弃未确认草案。';panel.appendChild(note);
  for(let index=0;index<draft.steps.length;index++){
    const step=draft.steps[index];const box=document.createElement('div');box.className='goal-step';const label=document.createElement('div');label.className='sub';label.textContent='步骤 '+(index+1);box.appendChild(label);
    const title=document.createElement('input');title.maxLength=120;title.value=step.title;title.setAttribute('aria-label','步骤 '+(index+1)+' 标题');title.disabled=Boolean(draft.retryPayload);title.addEventListener('input',()=>{draft.steps[index].title=title.value;draft.acceptEventId='';updateGoalDraftAcceptState()});box.appendChild(title);
    const detail=document.createElement('textarea');detail.maxLength=500;detail.value=step.description||'';detail.placeholder='步骤说明（可选）';detail.disabled=Boolean(draft.retryPayload);detail.setAttribute('aria-label','步骤 '+(index+1)+' 说明');detail.addEventListener('input',()=>{draft.steps[index].description=detail.value;draft.acceptEventId='';updateGoalDraftAcceptState()});box.appendChild(detail);
    if(!draft.retryPayload)box.appendChild(makeWorkspaceButton('移除此步',()=>{if(draft.steps.length<=1)return;draft.steps.splice(index,1);draft.acceptEventId='';renderGoalDraft()}));panel.appendChild(box);
  }
  const buttons=document.createElement('div');buttons.className='goal-actions';
  if(!draft.retryPayload&&draft.steps.length<8)buttons.appendChild(makeWorkspaceButton('添加步骤',()=>{draft.steps.push({title:'',description:''});draft.acceptEventId='';renderGoalDraft()}));
  buttons.appendChild(makeWorkspaceButton(draft.retryPayload?'重试确认结果':'确认草案并创建普通任务',()=>acceptGoalDraft(),true,Boolean(draft.submitting)));
  buttons.appendChild(makeWorkspaceButton('丢弃草案',()=>{activeGoalDraft=null;renderGoalDraft()},false,Boolean(draft.retryPayload||draft.submitting)));
  panel.appendChild(buttons);const status=document.createElement('div');status.className='status-note';status.id='goalDraftStatus';status.textContent=draft.retryPayload?'上次请求未收到确定结果；重试将使用完全相同的事件 ID 和内容。':'';panel.appendChild(status);updateGoalDraftAcceptState();
}
function goalDraftIsValid(draft){return draft.steps.length>=1&&draft.steps.length<=8&&draft.steps.every(step=>String(step.title||'').trim().length>0&&[...String(step.title||'').trim()].length<=120&&[...String(step.description||'')].length<=500)}
function updateGoalDraftAcceptState(){const draft=activeGoalDraft;const panel=document.getElementById('goalDraftPanel');const button=panel?.querySelector('.goal-actions button.primary');if(button&&!draft?.submitting)button.disabled=!draft?.retryPayload&&!goalDraftIsValid(draft)}
async function createGoalFromWeb(){
  if(goalCreateInFlight)return;
  const title=document.getElementById('goalTitle').value.trim();const description=document.getElementById('goalDescription').value.trim();if(!title)return workspaceStatus('goalStatus','请先输入目标名称。',true);
  if(dailyGoalSnapshot.migrationRequired)return workspaceStatus('goalStatus','隐私迁移待确认，暂不能创建目标。',true);
  goalCreateInFlight=true;document.getElementById('goalCreateButton').disabled=true;
  try{const result=await api('/api/goals',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({title,description,privacyRevision:dailyGoalSnapshot.privacyRevision})});document.getElementById('goalTitle').value='';document.getElementById('goalDescription').value='';workspaceStatus('goalStatus','目标已保存：'+result.goal.title);await refreshDailyWorkspace()}
  catch(error){workspaceStatus('goalStatus','创建未完成：'+error.message+(error.code?' · '+error.code:''),true);if(String(error.code||'').startsWith('privacy_'))await refreshDailyWorkspace()}
  finally{goalCreateInFlight=false;document.getElementById('goalCreateButton').disabled=false}
}
async function requestGoalDraft(goalId,context){
  if(goalDraftInFlight.has(goalId))return;
  if(dailyGoalSnapshot.migrationRequired)return workspaceStatus('goalStatus','隐私迁移待确认，暂不能生成草案。',true);
  goalDraftInFlight.add(goalId);renderGoalWorkspace();
  try{
    const response=await api('/api/goals/'+encodeURIComponent(goalId)+'/draft',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({context:String(context||''),privacyRevision:dailyGoalSnapshot.privacyRevision})});
    const goal=dailyGoalSnapshot.goals.find(item=>item.id===goalId);activeGoalDraft={goalId,goalTitle:goal?.title||'目标',eventId:'',steps:(response.steps||[]).map(step=>({title:String(step.title||''),description:String(step.description||'')})),providerId:response.providerId||'local',fallbackProvider:response.fallbackProvider||null,fallbackReason:response.fallbackReason||response.providerAudit?.fallbackReason||null,privacyRevision:response.privacyRevision,taskPrivacyRevision:dailyGoalSnapshot.taskPrivacyRevision,submitting:false,retryPayload:null};
    renderGoalDraft();workspaceStatus('goalStatus','草案已生成；请检查、编辑每一步，再明确确认创建任务。');
  }catch(error){workspaceStatus('goalStatus','草案生成失败：'+error.message+(error.code?' · '+error.code:''),true);if(String(error.code||'').startsWith('privacy_'))await refreshDailyWorkspace()}
  finally{goalDraftInFlight.delete(goalId);renderGoalWorkspace()}
}
async function acceptGoalDraft(){
  const draft=activeGoalDraft;if(!draft||draft.submitting)return;
  if(dailyGoalSnapshot.migrationRequired)return workspaceStatus('goalStatus','隐私迁移待确认，暂不能确认草案。',true);
  if(!draft.retryPayload&&!goalDraftIsValid(draft)){const status=document.getElementById('goalDraftStatus');if(status)status.textContent='请填写 1–8 个有效步骤（标题最多 120 字，说明最多 500 字）。';return}
  if(!draft.retryPayload&&!confirm('确认当前编辑后的 '+draft.steps.length+' 个步骤，并创建对应普通任务？'))return;
  if(!draft.retryPayload){draft.acceptEventId=webEventId('goal-accept');draft.retryPayload={eventId:draft.acceptEventId,steps:draft.steps.map(step=>({title:step.title.trim(),description:String(step.description||'').trim()})),privacyRevision:draft.privacyRevision,taskPrivacyRevision:draft.taskPrivacyRevision}}
  draft.submitting=true;renderGoalDraft();
  try{
    const result=await api('/api/goals/'+encodeURIComponent(draft.goalId)+'/accept',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(draft.retryPayload)});
    activeGoalDraft=null;workspaceStatus('goalStatus',(result.duplicate?'已确认（重复请求安全去重）':'已确认草案并创建 '+(result.tasks||[]).length+' 个普通任务')+'。');renderGoalDraft();await Promise.all([refreshDailyWorkspace(),refreshWorkspaceNow()]);if(result.tasks?.[0])selectTask(result.tasks[0].id);
  }catch(error){
    draft.submitting=false;
    if(!Number.isFinite(error.status)||error.status>=500){draft.retryPayload=draft.retryPayload||null;workspaceStatus('goalStatus','无法确认服务端是否已接受：'+error.message+'。请用“重试确认结果”核对，不要重新编辑。',true)}
    else{draft.retryPayload=null;draft.acceptEventId='';workspaceStatus('goalStatus','草案未接受：'+error.message+(error.code?' · '+error.code:''),true);if(String(error.code||'').startsWith('privacy_')){await refreshDailyWorkspace();draft.privacyRevision=dailyGoalSnapshot.privacyRevision;draft.taskPrivacyRevision=dailyGoalSnapshot.taskPrivacyRevision}}
    renderGoalDraft();
  }
}
function refreshDailyWorkspace(){
  if(dailyRefreshPromise)return dailyRefreshPromise;
  dailyRefreshPromise=Promise.allSettled([api('/api/routines?cursor=0&limit=20'),api('/api/goals')]).then(results=>{
    const routine=results[0],goals=results[1];
    if(routine.status==='fulfilled'){
      dailyRoutineSnapshot=routine.value;const signature=JSON.stringify({revision:routine.value.revision,privacyRevision:routine.value.privacyRevision,migrationRequired:routine.value.migrationRequired,current:routine.value.current,history:routine.value.history,catalog:routine.value.catalog});
      if(signature!==routineRenderSignature){routineRenderSignature=signature;renderRoutineWorkspace()}
    }else workspaceStatus('routineStatus','日常状态读取失败：'+routine.reason.message,true);
    if(goals.status==='fulfilled'){
      dailyGoalSnapshot=goals.value;const signature=JSON.stringify(goals.value);
      if(signature!==goalRenderSignature){goalRenderSignature=signature;renderGoalWorkspace()}
    }else workspaceStatus('goalStatus','目标状态读取失败：'+goals.reason.message,true);
  }).finally(()=>{dailyRefreshPromise=null});
  return dailyRefreshPromise;
}
setInterval(renderRoutineTimers,1000);
setInterval(refreshDailyWorkspace,10000);
refreshDailyWorkspace();
refreshPrivacy();
async function probeSelectedProvider(){
  const id=aiProviderSelect.value;
  if(!id) return;
  try{ const result=await api('/api/ai/providers/'+encodeURIComponent(id)+'/probe',{method:'POST'}); aiProbeResult.textContent='正常 · '+result.latencyMs+'ms'; }
  catch(error){ aiProbeResult.textContent='失败 · '+error.message; }
}
setInterval(refreshCompanionSummary, 5000);
refreshCompanionSummary();

async function refreshReality(){
  const region = realityRegion.value.trim();
  if(!region){ realitySummary.textContent='请输入粗区域 ID'; return; }
  try{
    const [state, events] = await Promise.all([api('/api/reality/state'), api('/api/reality/events?region='+encodeURIComponent(region))]);
    const inventoryCount=Object.values(state.state.inventory||{}).reduce((a,b)=>a+(Number(b)||0),0);
    realitySummary.innerHTML='<b>区域 '+esc(region)+'</b> · 事件 '+events.events.length+' · Mote Lv.'+(state.state.level||1)+' · XP '+(state.state.xp||0)+' · 物品 '+inventoryCount+'<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:8px;margin-top:10px">'+events.events.map(e=>'<div class=item><b>'+esc(e.kind)+' / '+esc(e.clueType)+'</b><div class=sub>'+esc(e.distanceBand)+' · '+e.bearing+'° · 难度 '+e.difficulty+'</div><div class=row><button onclick="realityAction('+jsAttr(e.id)+','+jsAttr('start')+','+jsAttr(e.clueType)+')">遭遇</button><button class=primary onclick="realityAction('+jsAttr(e.id)+','+jsAttr('resolve')+','+jsAttr(e.clueType)+')">收集</button></div></div>').join('')+'</div>';
  }catch(error){ realitySummary.textContent='现实事件加载失败：'+error.message; }
}
async function realityAction(id, action, clueType){
  const region = realityRegion.value.trim();
  if(!region) return;
  try{
    const response=await api('/api/reality/events/'+encodeURIComponent(id)+'/'+action,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({region,clueType,actions:action==='start'?[]:['observe']})});
    realitySummary.dataset.lastAction=id+':'+action+':'+JSON.stringify(response.reward||response.encounter||{});
    await refreshReality();
  }catch(error){ realitySummary.textContent='现实动作失败：'+error.message; }
}

function realityLogClueLabel(type){return ({location:'地点',object:'物体',light:'光线'})[String(type||'').toLowerCase()]||'线索'}
function realityLogRegionLabel(region){return region==='camera'?'仅镜头，未使用位置':'粗区域 '+String(region||'未知')}
function realityLogRewardLabel(entry){
  if(entry.status!=='confirmed')return '';
  const reward=entry.reward||{};const parts=[];
  const xp=Math.max(0,Math.trunc(Number(reward.xp)||0));if(xp>0)parts.push('经验 +'+xp);
  for(const item of Array.isArray(reward.items)?reward.items:[]){const amount=Math.max(0,Math.trunc(Number(item.amount)||0));if(amount>0)parts.push(String(item.id||'道具')+' ×'+amount)}
  if(reward.boost?.id==='field-focus')parts.push('专注增益 ×'+Number(reward.boost.multiplier||1));
  return parts.join(' · ')||'已确认 · 无经验或道具奖励';
}
function realityLogStatusLabel(entry){return entry.status==='confirmed'?'已确认':entry.status==='rejected'?'已拒绝 · 仅查看':'待同步 · 不显示奖励'}
function renderRealityLogDetail(entry){
  const root=document.getElementById('realityLogDetail');if(!root)return;root.replaceChildren();
  if(!entry){root.textContent='选择一条记录查看安全收据详情。';return}
  const title=document.createElement('h3');title.textContent=realityLogClueLabel(entry.clueType)+'线索 · '+realityLogStatusLabel(entry);root.appendChild(title);
  const metadata=document.createElement('div');metadata.className='sub';metadata.textContent=realityLogRegionLabel(entry.coarseRegion)+' · '+new Date(Number(entry.occurredAt)||0).toLocaleString();root.appendChild(metadata);
  const receiptId=document.createElement('div');receiptId.className='sub';receiptId.style.wordBreak='break-all';receiptId.textContent=entry.eventId;root.appendChild(receiptId);
  if(entry.moteId){const moteLine=document.createElement('div');moteLine.className='sub';moteLine.textContent='Mote '+String(entry.moteId);root.appendChild(moteLine)}
  if(entry.status==='confirmed'){
    const reward=document.createElement('div');reward.className='sub';reward.style.marginTop='8px';reward.textContent=realityLogRewardLabel(entry);root.appendChild(reward);
    const observation=document.createElement('div');observation.className='sub';observation.style.marginTop='6px';observation.textContent=String(entry.observation||'');root.appendChild(observation);
    const jump=makeWorkspaceButton('在 Reality 中只读查看',()=>openRealityLogRecord(entry.eventId),true);jump.style.marginTop='8px';root.appendChild(jump);
  }else{
    const note=document.createElement('div');note.className='sub';note.style.marginTop='8px';note.textContent='这条记录没有服务端确认收据；不会显示奖励，也不能再次提交。';root.appendChild(note);
  }
}
function renderRealityLog(){
  const root=document.getElementById('realityLogList');if(!root)return;root.replaceChildren();
  const status=document.getElementById('realityLogStatus');
  if(status)status.textContent=realityLogEntries.length?realityLogEntries.length+' 条 · 仅服务端确认收据显示真实奖励':'暂无探索记录；离线或权限关闭不会伪造区域与实地验证。';
  for(const entry of realityLogEntries){
    const row=document.createElement('button');row.type='button';row.className='item reality-log-row';row.style.width='100%';row.style.textAlign='left';row.style.marginTop='6px';
    const title=document.createElement('b');title.textContent=realityLogClueLabel(entry.clueType)+'线索 · '+realityLogStatusLabel(entry);row.appendChild(title);
    const metadata=document.createElement('div');metadata.className='sub';metadata.textContent=realityLogRegionLabel(entry.coarseRegion)+' · '+new Date(Number(entry.occurredAt)||0).toLocaleString();row.appendChild(metadata);
    row.setAttribute('aria-label',title.textContent+'，'+metadata.textContent);
    row.addEventListener('click',()=>{realityLogSelectedId=entry.eventId;renderRealityLogDetail(entry)});
    root.appendChild(row);
  }
  const more=document.getElementById('realityLogMore');if(more)more.hidden=!realityLogCursor;
  if(!realityLogEntries.some(entry=>entry.eventId===realityLogSelectedId)){realityLogSelectedId='';renderRealityLogDetail(null)}
}
async function refreshRealityLog({cursor=null,reset=false}={}){
  if(reset||!cursor)realityLogGeneration++;
  const generation=realityLogGeneration;
  const status=document.getElementById('realityLogStatus');if(status)status.textContent='正在同步探索记录…';
  try{
    const path='/api/reality/log?limit=50'+(cursor?'&cursor='+encodeURIComponent(cursor):'');
    const result=await api(path);
    if(generation!==realityLogGeneration)return;
    if(result._status===304){renderRealityLog();return}
    const received=Array.isArray(result.entries)?result.entries:[];
    if(reset||!cursor)realityLogEntries=[];
    const byId=new Map(realityLogEntries.map(entry=>[entry.eventId,entry]));
    for(const entry of received)if(entry&&typeof entry.eventId==='string'&&entry.status==='confirmed')byId.set(entry.eventId,entry);
    realityLogEntries=[...byId.values()].sort((a,b)=>(Number(b.occurredAt)||0)-(Number(a.occurredAt)||0)||String(b.eventId).localeCompare(String(a.eventId)));
    realityLogCursor=typeof result.nextCursor==='string'?result.nextCursor:null;
    renderRealityLog();
  }catch(error){if(generation===realityLogGeneration&&status)status.textContent='探索记录暂不可用：'+error.message}
}
async function openRealityLogRecord(eventId){
  const entry=realityLogEntries.find(item=>item.eventId===eventId);
  if(!entry||entry.status!=='confirmed')return;
  const panel=document.getElementById('realityPanel');
  const regionInput=document.getElementById('realityRegion');
  if(regionInput)regionInput.value=entry.coarseRegion;
  const summary=document.getElementById('realitySummary');summary.replaceChildren();
  const record=document.createElement('div');record.className='workspace-card';
  const title=document.createElement('h3');title.textContent='历史 Reality 收据 · '+realityLogClueLabel(entry.clueType)+' · 只读';record.appendChild(title);
  const details=document.createElement('div');details.className='sub';details.textContent=realityLogRegionLabel(entry.coarseRegion)+' · '+new Date(Number(entry.occurredAt)||0).toLocaleString()+' · '+realityLogRewardLabel(entry)+' · '+entry.eventId+(entry.moteId?' · Mote '+entry.moteId:'');record.appendChild(details);
  const note=document.createElement('div');note.className='sub';note.style.marginTop='6px';note.textContent='这是已确认的历史记录，不会重放遭遇或重复发放奖励。';record.appendChild(note);
  summary.appendChild(record);panel?.scrollIntoView({behavior:'smooth',block:'start'});
}

async function refreshWorkspaceNow(){try{
  let s=await api('/api/state?view=summary');
  if(s._status===304) return;
  let w=s.workspace||{},p=s.autonomy||{},b=s.motes?.behavior||{},r=s.motes?.relationship||{};
  workspaceSummary.textContent='策略：'+(p.level||'-')+' · 工具 '+(p.allowedTools||[]).length+' · 急停 '+(w.emergencyStop?.active?'是':'否')+' · 任务 '+(w.taskCount||0)+' · Mote Lv.'+(r.level||1)+' · '+(b.gaze||'ambient');
  const viewMode = window.taskViewMode || 'all';
  const filter = window.taskFilter ? taskFilter.value : '';
  let filteredTasks = w.tasks || [];
  if (viewMode === 'inbox') {
    filteredTasks = filteredTasks.filter(t => ['pending', 'needs_confirmation'].includes(t.state || t.status));
  } else if (viewMode === 'in_progress') {
    filteredTasks = filteredTasks.filter(t => ['running', 'paused'].includes(t.state || t.status));
  } else if (viewMode === 'history') {
    filteredTasks = filteredTasks.filter(t => ['succeeded', 'failed', 'cancelled', 'archived'].includes(t.state || t.status));
  }
  if (filter) filteredTasks = filteredTasks.filter(t => (t.state || t.status) === filter);

  const tasksHtml = filteredTasks.slice(0,10).map(t=>'<div class=item onclick="selectTask('+jsAttr(t.id)+')" style="cursor:pointer;border:1px solid #ccc;padding:4px;margin-bottom:4px;"><b>'+esc(t.title)+'</b> · '+esc(t.state||t.status)+' · '+(t.progress||0)+'% <br><button onclick="event.stopPropagation();taskAction(this,'+jsAttr(t.id)+','+jsAttr('pause')+')">暂停</button> <button onclick="event.stopPropagation();taskAction(this,'+jsAttr(t.id)+','+jsAttr('continue')+')">继续</button> <button onclick="event.stopPropagation();taskAction(this,'+jsAttr(t.id)+','+jsAttr('retry')+')">重试</button> <button onclick="event.stopPropagation();taskAction(this,'+jsAttr(t.id)+','+jsAttr('cancel')+')">取消</button> <button onclick="event.stopPropagation();taskAction(this,'+jsAttr(t.id)+','+jsAttr('archive')+')">归档</button></div>').join('');
  const counts=(w.tasks||[]).reduce((all,t)=>{const key=t.state||t.status||'unknown';all[key]=(all[key]||0)+1;return all},{});
  const statsHtml = '<div style="margin-bottom:8px">任务视图: <select id="taskViewSelect" onchange="window.taskViewMode=this.value;refreshWorkspace()"><option value="all" '+(viewMode==='all'?'selected':'')+'>全部任务</option><option value="inbox" '+(viewMode==='inbox'?'selected':'')+'>收件箱 (待处理/需确认)</option><option value="in_progress" '+(viewMode==='in_progress'?'selected':'')+'>进行中 (运行/暂停)</option><option value="history" '+(viewMode==='history'?'selected':'')+'>历史任务 (完成/失败/归档)</option></select> 状态筛选: <select id="taskFilter" onchange="refreshWorkspace()"><option value="">全部状态</option><option value="pending">待处理</option><option value="running">运行中</option><option value="needs_confirmation">需确认</option><option value="succeeded">已完成</option><option value="failed">失败</option><option value="archived">已归档</option></select> 统计: 共 '+(w.taskCount||0)+' · 待处理 '+(counts.pending||0)+' · 运行中 '+(counts.running||0)+' · 需确认 '+(counts.needs_confirmation||0)+' · 完成 '+(counts.succeeded||0)+' · 失败 '+(counts.failed||0)+'</div>';
  const attentionItems = (w.attention || s.attention || []);
  const attentionHtml = attentionItems.slice(0, 5).map(a => {
    const taskId = a.relatedTaskId || '';
    const sessId = a.relatedSessionId || '';
    const link = taskId ? ('phonebridge://task/' + esc(taskId)) : ('phonebridge://attention/' + esc(a.id));
    return '<div class="item attention-item" data-attention-id="' + esc(a.id) + '" data-task-id="' + esc(taskId) + '" data-session-id="' + esc(sessId) + '" data-deep-link="' + esc(link) + '" style="cursor:pointer;border-left:3px solid var(--amber);" onclick="onAttentionClick(' + jsAttr(taskId) + ')">' +
      '<b>[Attention/' + esc(a.severity) + ']</b> ' + esc(a.title) + ' - ' + esc(a.summary) +
      (taskId ? ' <span style="font-size:10px;color:var(--mint)">[关联任务: ' + esc(taskId) + ']</span>' : '') +
    '</div>';
  }).join('');
  const approvalsHtml=(w.approvals||[]).filter(a=>a.state==='needs_confirmation'||a.state==='approved').map(a=>'<div class=item>待批准：<b>'+esc(a.toolId)+'</b> · '+esc(a.state)+' <button class=primary onclick="approveApproval('+jsAttr(a.id)+')">批准并执行</button></div>').join('');
  const roster=(s.motes?.roster||[]).map(m=>'<button '+(m.unlocked?'':'disabled')+' class="'+(m.active?'primary':'')+'" onclick="activateMote('+jsAttr(m.id)+')">'+esc(m.name)+(m.unlocked?'':' 🔒')+'</button>').join('');
  const revision=String(w.eventRevision||s.revision||'');
  if(!moteRoster.querySelector('[data-workspace-shell]')){
    moteRoster.innerHTML='<div data-workspace-shell style="width:100%"><div data-workspace-stats></div><div data-workspace-attention></div><div data-workspace-tasks></div><div data-workspace-approvals></div><div data-workspace-roster style="width:100%;margin-top:8px"></div><div id="taskDetail" style="margin-top:8px;padding:8px;background:#101E18;font-size:12px;white-space:pre-wrap"></div></div>';
  }
  const section=(selector)=>moteRoster.querySelector(selector);
  const updateSection=(selector, html, signature)=>{
    const node=section(selector);
    if(!node || node.dataset.signature===signature) return;
    node.innerHTML=html;
    node.dataset.signature=signature;
  };
  updateSection('[data-workspace-stats]', statsHtml, statsHtml);
  updateSection('[data-workspace-attention]', attentionHtml, attentionHtml);
  updateSection('[data-workspace-tasks]', tasksHtml, tasksHtml);
  updateSection('[data-workspace-approvals]', approvalsHtml, approvalsHtml);
  updateSection('[data-workspace-roster]', roster, roster);
  moteRoster.dataset.revision=revision;
  moteRoster.dataset.filter=filter;
  moteRoster.dataset.viewMode=viewMode;
  if(window.taskFilter) taskFilter.value = filter;
  if(window.taskViewSelect) taskViewSelect.value = viewMode;
  if(window.selectedTaskId) selectTask(window.selectedTaskId);
}catch(e){workspaceSummary.textContent='工作台数据不可用'}}

function onAttentionClick(taskId) {
  if (taskId) selectTask(taskId);
}

async function selectTask(id) {
  window.selectedTaskId = id;
  const div = document.getElementById('taskDetail');
  if(!div) return;
  try {
    const taskRes = await api('/api/tasks/'+encodeURIComponent(id));
    const auditRes = await api('/api/tasks/'+encodeURIComponent(id)+'/audit');
    const t = taskRes.task;
    const runner = t.runner || {};
    let chatSnippet = '';
    const sessionId = t.metadata?.sessionId || t.relatedSessionId || '';
    if (sessionId) {
      try {
        const msgsRes = await api('/api/workspace/sessions/' + encodeURIComponent(sessionId) + '/messages');
        const msgs = msgsRes.messages || [];
        if (msgs.length > 0) {
          chatSnippet = '\\n关联会话 [' + esc(sessionId) + '] 消息:\\n' + msgs.slice(-2).map(m => '  [' + esc(m.role) + '] ' + esc(m.text)).join('\\n');
        }
      } catch (_) {}
    }
    let auditText = (auditRes.audit||[]).slice(-5).map(a => a.createdAt + ' ' + a.actor + ' ' + a.action).join('\\n');
    div.textContent = '任务: ' + t.title + '\\n状态: ' + t.state + '\\n进度: ' + t.progress + '%\\n重试: ' + (runner.retryCount||0) + '\\n结果: ' + (t.result||t.error||'-') + chatSnippet + '\\n近期审计:\\n' + auditText;
  } catch(e) {
    div.textContent = '加载失败: ' + e.message;
  }
}

async function taskAction(btn,id,action){
  if(btn && btn.disabled) return;
  if(btn) btn.disabled=true;
  try {
    await api('/api/tasks/'+encodeURIComponent(id)+'/actions',{method:'POST',headers:{'content-type':'application/json','idempotency-key':'web-'+action+'-'+Date.now()},body:JSON.stringify({action})});
    refreshWorkspace();
  } catch(e) {
    const div = document.getElementById('taskDetail');
    if(div && window.selectedTaskId === id) div.textContent += '\\n动作失败: ' + e.message;
  } finally {
    if(btn) btn.disabled=false;
  }
}
async function activateMote(id){await api('/api/motes/active',{method:'PATCH',headers:{'content-type':'application/json'},body:JSON.stringify({id})});refreshWorkspace()}
async function chooseMote(id){await api('/api/motes/exploration',{method:'PATCH',headers:{'content-type':'application/json'},body:JSON.stringify({targetId:id})});refreshWorkspace()}
async function stopAutonomy(){await api('/api/tools/emergency-stop',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({reason:'web'})});refreshWorkspace()}
async function approveApproval(id){await api('/api/autonomy/approvals/'+encodeURIComponent(id)+'/approve',{method:'POST'});await api('/api/autonomy/approvals/'+encodeURIComponent(id)+'/invoke',{method:'POST'});refreshWorkspace()}
let workspaceRefreshFrame=0;
function refreshWorkspace(){if(workspaceRefreshFrame)return;workspaceRefreshFrame=requestAnimationFrame(()=>{workspaceRefreshFrame=0;refreshWorkspaceNow()})}
refreshWorkspace();
refreshRealityLog();
</script>`;

const handleHttpRequest = async (req, res) => {
  res.setHeader('Vary', 'Origin');
  if (req.headers.origin && req.headers.origin === `http://${req.headers.host}`) {
    res.setHeader('Access-Control-Allow-Origin', req.headers.origin);
  }
  res.setHeader('Access-Control-Allow-Headers', 'content-type, x-phonebridge-token, authorization');
  const parsedUrl = new URL(req.url, 'http://localhost');

  try {
    if (req.method === 'GET' && ['/health/live', '/api/health/liveness'].includes(parsedUrl.pathname)) {
      return sendJson(res, 200, healthChecks.liveness());
    }
    if (req.method === 'GET' && ['/health/ready', '/api/health/readiness'].includes(parsedUrl.pathname)) {
      const readiness = healthChecks.readiness();
      return sendJson(res, readiness.ok ? 200 : 503, readiness);
    }
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

    if (parsedUrl.pathname === '/api/pairing/claim' && req.method === 'POST') {
      const remote = String(req.socket.remoteAddress || '');
      const loopback = remote === '127.0.0.1' || remote === '::1' || remote === '::ffff:127.0.0.1';
      const encrypted = Boolean(req.socket.encrypted);
      if (!loopback && !TLS_ENABLED) return sendJson(res, 409, { ok: false, error: 'remote pairing requires TLS' });
      if (!loopback && !encrypted) return sendJson(res, 403, { ok: false, error: 'remote pairing requires an encrypted connection' });
      if (process.env.PHONEBRIDGE_TOKEN) return sendJson(res, 409, { ok: false, error: 'pairing is disabled while a fixed environment token is configured' });
      try {
        const result = pairingManager.claim(await readJson(req));
        const token = rotateAccessToken();
        return sendJson(res, 200, { ok: true, ...result, token });
      } catch (error) { return sendJson(res, 409, { ok: false, error: error.message }); }
    }

    if (!requestHasAccess(req)) {
      if (parsedUrl.pathname === '/') {
        res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
        return res.end(getLoginHtml());
      }
      denyAccess(res);
      return;
    }

    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(req.method) && parsedUrl.pathname !== '/api/privacy/delete') {
      let releaseMutation;
      try { releaseMutation = privacyCenter.beginMutation(); }
      catch (error) { return sendJson(res, 409, { ok: false, error: error.message, retryable: true }); }
      let released = false;
      const releaseOnce = () => {
        if (released) return;
        released = true;
        releaseMutation();
      };
      res.once('finish', releaseOnce);
      res.once('close', releaseOnce);
    }

    if (parsedUrl.pathname === '/api/pairing/start' && req.method === 'POST') {
      const unavailableReason = pairingAvailabilityError({
        bindHost: BIND_HOST,
        pairingHost: PAIRING_HOST,
        tlsEnabled: TLS_ENABLED,
        fixedToken: Boolean(process.env.PHONEBRIDGE_TOKEN),
      });
      if (unavailableReason) return sendJson(res, 409, { ok: false, error: unavailableReason });
      const transport = pairingTransport({ tls: TLS_ENABLED, host: PAIRING_HOST, port: PORT, fingerprint: TLS_CONFIG.fingerprint });
      const offer = pairingManager.start({ host: PAIRING_HOST, port: PORT, fingerprint: TLS_CONFIG.fingerprint });
      const qrPayload = buildPairingQrPayload({ ...offer, transport });
      const qrImage = await QRCode.toDataURL(qrPayload, { errorCorrectionLevel: 'M', margin: 2, width: 300 });
      res.setHeader('Cache-Control', 'no-store');
      return sendJson(res, 201, { ok: true, offer: { ...offer, ...transport, qrPayload }, qrImage });
    }

    if (deviceSimulator && parsedUrl.pathname === '/api/dev/simulator' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, state: deviceSimulator.snapshot(), reality: deviceSimulator.realityEvent() });
    }
    if (deviceSimulator && parsedUrl.pathname === '/api/dev/simulator' && req.method === 'PATCH') {
      try {
        const state = deviceSimulator.apply(await readJson(req));
        return sendJson(res, 200, { ok: true, state, reality: deviceSimulator.realityEvent() });
      } catch (error) {
        return sendJson(res, 400, { ok: false, error: error.message });
      }
    }

    if (parsedUrl.pathname === '/api/workspace' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, ...workspaceSnapshot() });
    }
    if (parsedUrl.pathname === '/api/workspace/timeline' && req.method === 'GET') {
      const cursor = parsedUrl.searchParams.get('cursor') ?? parsedUrl.searchParams.get('since') ?? 0;
      const limit = parsedUrl.searchParams.get('limit') || 50;
      const entityType = parsedUrl.searchParams.get('entityType') || null;
      const includeSnapshot = parsedUrl.searchParams.get('includeSnapshot') === 'true' || Number(cursor) === 0;

      const etag = `"timeline-${workspaceTimeline.headRevision}"`;
      if (req.headers['if-none-match'] === etag) {
        res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-store' });
        return res.end();
      }

      const queryResult = workspaceTimeline.sync({ cursor: Number(cursor), limit: Number(limit), entityType });
      const response = {
        ok: true,
        ...queryResult,
        headRevision: workspaceTimeline.headRevision
      };
      if (includeSnapshot && !response.snapshot) {
        response.snapshot = workspaceTimeline.getSnapshot();
      }
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', ETag: etag, 'Cache-Control': 'no-store' });
      return res.end(JSON.stringify(response));
    }
    if (parsedUrl.pathname === '/api/runtime/flush' && req.method === 'POST') {
      try {
        await workspaceStore.flushPersistence();
        savePersistentState();
        return sendJson(res, 200, {
          ok: true,
          flushedAt: new Date().toISOString(),
          persistence: runtimePersistence.snapshot(),
        });
      } catch (_) {
        return sendJson(res, 503, { ok: false, error: 'runtime state flush failed' });
      }
    }
    if (parsedUrl.pathname === '/api/diagnostics' && req.method === 'GET') {
      diagnosticsCollector.updateTelemetry(phoneTelemetry);
      diagnosticsCollector.setEventBacklog(taskRunner.list().filter(r => r.state === 'queued' || r.state === 'running').length);
      return sendJson(res, 200, { ...diagnosticsCollector.snapshot(), persistence: runtimePersistence.snapshot(), snapshotCache: snapshotCache.stats() });
    }
    if (parsedUrl.pathname === '/api/diagnostics/export' && req.method === 'GET') {
      diagnosticsCollector.updateTelemetry(phoneTelemetry);
      diagnosticsCollector.setEventBacklog(taskRunner.list().filter(r => r.state === 'queued' || r.state === 'running').length);
      return sendJson(res, 200, {
        ok: true,
        formatVersion: 1,
        generatedAt: new Date().toISOString(),
        readiness: healthChecks.readiness(),
        diagnostics: diagnosticsCollector.snapshot(),
        persistence: runtimePersistence.snapshot(),
        snapshotCache: snapshotCache.stats(),
        companionSummary: JSON.parse(summaryPayload()).companionSummary,
        privacy: {
          secrets: false,
          originalFrames: false,
          preciseLocation: false,
          continuousTrack: false,
        },
      });
    }
    if (parsedUrl.pathname === '/api/companion/summary' && req.method === 'GET') {
      const etag = `"companion-${workspaceStore.eventRevision}-${snapshotRevision}"`;
      if (req.headers['if-none-match'] === etag) {
        res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-store' });
        return res.end();
      }
      const summary = JSON.parse(summaryPayload()).companionSummary;
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', ETag: etag, 'Cache-Control': 'no-store' });
      return res.end(JSON.stringify({ ok: true, summary }));
    }
    if (parsedUrl.pathname === '/api/ai/providers' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, providers: aiProviderManager.getProviders() });
    }
    if (parsedUrl.pathname === '/api/ai/settings' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, settings: aiProviderManager.getSettings() });
    }
    if (parsedUrl.pathname === '/api/ai/settings' && req.method === 'PATCH') {
      try {
        const patch = await readJson(req);
        const updated = aiProviderManager.updateSettings(patch);
        return sendJson(res, 200, { ok: true, settings: updated });
      } catch (error) {
        return sendJson(res, 400, { ok: false, error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/ai/capabilities' && req.method === 'GET') {
      return sendJson(res, 200, {
        ok: true,
        providers: aiProviderManager.getProviders().map(provider => ({ id: provider.id, capabilities: provider.capabilities || ['text'] })),
        defaults: { timeoutMs: aiProviderManager.timeoutMs, maxOutputTokens: aiProviderManager.maxOutputTokens, dailyOutputTokenBudget: aiProviderManager.dailyOutputTokenBudget }
      });
    }
    if (parsedUrl.pathname === '/api/privacy/overview' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, ...privacyCenter.overview() });
    }
    if (parsedUrl.pathname === '/api/routines' && req.method === 'GET') {
      try {
        const result = dailyRoutinesStore.list({
          cursor: parsedUrl.searchParams.get('cursor') ?? 0,
          limit: parsedUrl.searchParams.get('limit') ?? 20,
        });
        return sendJson(res, 200, {
          ok: true,
          ...result,
          privacyRevision: privacyCenter.categoryRevision('routines'),
          migrationRequired: privacyCenter.isMigrationRequired('routines'),
        });
      } catch (error) {
        return sendJson(res, error.statusCode || 400, { ok: false, code: error.code || 'invalid_query', error: error.message });
      }
    }
    const routineEventsMatch = parsedUrl.pathname.match(/^\/api\/routines\/([^/]+)\/events$/);
    if (routineEventsMatch && req.method === 'POST') {
      try {
        const routineId = decodeURIComponent(routineEventsMatch[1]);
        const payload = await readJson(req);
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
          return sendJson(res, 400, { ok: false, code: 'invalid_event', error: 'event body must be an object' });
        }
        const categoryRevision = privacyCenter.categoryRevision('routines');
        if (privacyCenter.isMigrationRequired('routines')) {
          return sendJson(res, 409, { ok: false, code: 'privacy_migration_required:routines', error: 'routine data migration requires a user decision', retryable: true, requiredPrivacyRevision: categoryRevision });
        }
        if (payload.privacyRevision === undefined && categoryRevision > 0) {
          return sendJson(res, 409, { ok: false, code: 'privacy_revision_required:routines', error: 'privacyRevision is required for routine events', retryable: true, requiredPrivacyRevision: categoryRevision });
        }
        if (payload.privacyRevision !== undefined && (!Number.isSafeInteger(payload.privacyRevision) || payload.privacyRevision !== categoryRevision)) {
          const code = Number.isSafeInteger(payload.privacyRevision) && payload.privacyRevision < categoryRevision
            ? 'privacy_revision_stale:routines'
            : 'privacy_revision_mismatch:routines';
          return sendJson(res, 409, { ok: false, code, error: 'routine event privacy revision does not match the current category revision', retryable: true, requiredPrivacyRevision: categoryRevision });
        }
        const result = dailyRoutinesStore.recordEvent(routineId, payload);
        const status = result.duplicate ? 200 : result.action === 'start' ? 201 : 200;
        return sendJson(res, status, { ok: true, ...result });
      } catch (error) {
        return sendJson(res, error.statusCode || 400, { ok: false, code: error.code || 'invalid_event', error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/goals' && req.method === 'GET') {
      return sendJson(res, 200, {
        ok: true,
        goals: goalBoard.listGoals(),
        privacyRevision: privacyCenter.categoryRevision('goals'),
        taskPrivacyRevision: privacyCenter.categoryRevision('tasks'),
        migrationRequired: privacyCenter.isMigrationRequired('goals') || privacyCenter.isMigrationRequired('tasks'),
      });
    }
    if (parsedUrl.pathname === '/api/goals' && req.method === 'POST') {
      try {
        const payload = await readJson(req);
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new GoalBoardError('goal body must be an object');
        const revisionError = goalCategoryRevisionError('goals', payload.privacyRevision);
        if (revisionError) return sendJson(res, revisionError.status, revisionError.body);
        const { privacyRevision: _privacyRevision, ...goalPayload } = payload;
        const goal = goalBoard.createGoal(goalPayload);
        broadcast({ type: 'workspace.goal', operation: 'upsert', goal });
        return sendJson(res, 201, { ok: true, goal, privacyRevision: privacyCenter.categoryRevision('goals') });
      } catch (error) { return sendGoalError(res, error); }
    }
    const goalDraftMatch = parsedUrl.pathname.match(/^\/api\/goals\/([^/]+)\/draft$/);
    if (goalDraftMatch && req.method === 'POST') {
      try {
        const goalId = decodeURIComponent(goalDraftMatch[1]);
        const payload = await readJson(req);
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new GoalBoardError('goal draft body must be an object');
        const revisionError = goalCategoryRevisionError('goals', payload.privacyRevision);
        if (revisionError) return sendJson(res, revisionError.status, revisionError.body);
        const extraFields = Object.keys(payload).filter(key => !['context', 'privacyRevision'].includes(key));
        if (extraFields.length) throw new GoalBoardError(`unsupported goal draft field: ${extraFields[0]}`);
        const draft = await goalBoard.draftGoal(goalId, { context: payload.context });
        return sendJson(res, 200, { ok: true, ...draft, fallbackReason: draft.providerAudit?.fallbackReason || null, privacyRevision: privacyCenter.categoryRevision('goals') });
      } catch (error) { return sendGoalError(res, error); }
    }
    const goalAcceptMatch = parsedUrl.pathname.match(/^\/api\/goals\/([^/]+)\/accept$/);
    if (goalAcceptMatch && req.method === 'POST') {
      try {
        const goalId = decodeURIComponent(goalAcceptMatch[1]);
        const payload = await readJson(req);
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new GoalBoardError('goal acceptance body must be an object');
        const goalRevisionError = goalCategoryRevisionError('goals', payload.privacyRevision);
        if (goalRevisionError) return sendJson(res, goalRevisionError.status, goalRevisionError.body);
        const taskRevisionError = goalCategoryRevisionError('tasks', payload.taskPrivacyRevision);
        if (taskRevisionError) return sendJson(res, taskRevisionError.status, taskRevisionError.body);
        const extraFields = Object.keys(payload).filter(key => !['eventId', 'steps', 'privacyRevision', 'taskPrivacyRevision'].includes(key));
        if (extraFields.length) throw new GoalBoardError(`unsupported goal acceptance field: ${extraFields[0]}`);
        const result = goalBoard.acceptDraft(goalId, { eventId: payload.eventId, steps: payload.steps });
        if (!result.duplicate) {
          broadcast({ type: 'workspace.goal', operation: 'upsert', goal: result.goal, milestones: result.milestones });
          for (const task of result.tasks) broadcast({ type: 'workspace.task', task });
        }
        return sendJson(res, result.duplicate ? 200 : 201, {
          ok: true,
          ...result,
          privacyRevision: privacyCenter.categoryRevision('goals'),
          taskPrivacyRevision: privacyCenter.categoryRevision('tasks'),
        });
      } catch (error) { return sendGoalError(res, error); }
    }
    const goalMatch = parsedUrl.pathname.match(/^\/api\/goals\/([^/]+)$/);
    if (goalMatch && req.method === 'GET') {
      const goal = goalBoard.getGoal(decodeURIComponent(goalMatch[1]));
      return sendJson(res, goal ? 200 : 404, {
        ok: Boolean(goal),
        goal,
        privacyRevision: privacyCenter.categoryRevision('goals'),
        taskPrivacyRevision: privacyCenter.categoryRevision('tasks'),
      });
    }
    if (goalMatch && req.method === 'PATCH') {
      try {
        const goalId = decodeURIComponent(goalMatch[1]);
        const payload = await readJson(req);
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new GoalBoardError('goal patch must be an object');
        const revisionError = goalCategoryRevisionError('goals', payload.privacyRevision);
        if (revisionError) return sendJson(res, revisionError.status, revisionError.body);
        const { privacyRevision: _privacyRevision, ...patch } = payload;
        const goal = goalBoard.updateGoal(goalId, patch);
        broadcast({ type: 'workspace.goal', operation: 'upsert', goal });
        return sendJson(res, 200, { ok: true, goal, privacyRevision: privacyCenter.categoryRevision('goals') });
      } catch (error) { return sendGoalError(res, error); }
    }
    if (goalMatch && req.method === 'DELETE') {
      try {
        const goalId = decodeURIComponent(goalMatch[1]);
        const payload = await readJson(req);
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new GoalBoardError('goal deletion body must be an object');
        const extraFields = Object.keys(payload).filter(key => key !== 'privacyRevision');
        if (extraFields.length) throw new GoalBoardError(`unsupported goal deletion field: ${extraFields[0]}`);
        const revisionError = goalCategoryRevisionError('goals', payload.privacyRevision);
        if (revisionError) return sendJson(res, revisionError.status, revisionError.body);
        const goal = goalBoard.assertGoalIdle(goalId);
        if (!goal) return sendJson(res, 404, { ok: false, code: 'goal_not_found', error: 'goal not found' });
        let privacyRevision = privacyCenter.categoryRevision('goals');
        const result = goalBoard.deleteGoal(goalId, {
          beforeDelete: () => { privacyRevision = privacyCenter.advanceCategoryRevision('goals'); },
        });
        purgeGoalTaskTimeline(result.taskIds);
        for (const taskId of result.taskIds) broadcast({ type: 'workspace.task', task: { id: taskId, deleted: true } });
        broadcast({ type: 'workspace.goal', operation: 'delete', id: goalId, privacyRevision });
        return sendJson(res, 200, { ok: true, ...result, privacyRevision });
      } catch (error) { return sendGoalError(res, error); }
    }
    if (parsedUrl.pathname === '/api/privacy/migration/resolve' && req.method === 'POST') {
      try {
        const payload = await readJson(req);
        const result = await privacyCenter.resolveMigrationCategory(
          String(payload.category || ''),
          String(payload.decision || '')
        );
        if (!result.duplicate) {
          workspaceTimeline.recordEvent({
            eventId: `privacy-migration-${result.category}-${result.categoryRevision}`,
            entityType: 'privacy',
            entityId: `migration-${result.category}`,
            operation: 'upsert',
            payload: {
              category: result.category,
              decision: result.decision,
              categoryRevision: result.categoryRevision,
              status: result.status,
            },
          });
          broadcast({
            type: 'privacy.migration',
            category: result.category,
            decision: result.decision,
            categoryRevision: result.categoryRevision,
            categoryRevisions: result.categoryRevisions,
            migration: result,
          });
        }
        return sendJson(res, 200, { ok: true, migration: result });
      } catch (error) {
        const conflict = /decision conflict|not pending/i.test(String(error.message || ''));
        return sendJson(res, conflict ? 409 : 400, { ok: false, error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/privacy/export' && req.method === 'POST') {
      const remote = String(req.socket.remoteAddress || '');
      const loopback = remote === '127.0.0.1' || remote === '::1' || remote === '::ffff:127.0.0.1';
      if (!loopback && !req.socket.encrypted) return sendJson(res, 403, { ok: false, error: 'encrypted export requires HTTPS outside loopback' });
      try {
        const payload = await readJson(req);
        const archive = privacyCenter.exportEncrypted(payload);
        return sendJson(res, 200, { ok: true, archive });
      } catch (error) {
        return sendJson(res, 400, { ok: false, error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/privacy/delete' && req.method === 'POST') {
      try {
        const payload = await readJson(req);
        const receipt = await privacyCenter.delete(payload);
        if (!receipt.duplicate) {
          workspaceTimeline.recordEvent({
            eventId: `privacy-${receipt.requestId}`,
            entityType: 'privacy',
            entityId: receipt.requestId,
            operation: 'upsert',
            payload: { categories: receipt.categories, categoryRevisions: receipt.categoryRevisions || {}, completedAt: receipt.completedAt },
          });
          broadcast({ type: 'privacy.deleted', requestId: receipt.requestId, categories: receipt.categories, categoryRevisions: receipt.categoryRevisions || {}, completedAt: receipt.completedAt });
          if (receipt.categories.includes('progress')) broadcastMoteState();
        }
        return sendJson(res, 200, { ok: true, receipt });
      } catch (error) {
        const conflict = /(running tasks|privacy deletion is in progress)/i.test(String(error.message || ''));
        return sendJson(res, conflict ? 409 : 400, { ok: false, error: error.message });
      }
    }
    const aiCancelMatch = parsedUrl.pathname.match(/^\/api\/ai\/requests\/([^/]+)\/cancel$/);
    if (aiCancelMatch && req.method === 'POST') {
      const cancelled = aiProviderManager.cancel(decodeURIComponent(aiCancelMatch[1]));
      return sendJson(res, cancelled ? 200 : 404, { ok: cancelled, cancelled });
    }
    if (parsedUrl.pathname === '/api/memories' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, ...memoryStore.snapshot(), memories: memoryStore.list({ query: parsedUrl.searchParams.get('query') || '', limit: parsedUrl.searchParams.get('limit') || 50, sensitivity: parsedUrl.searchParams.get('sensitivity') || null, status: parsedUrl.searchParams.get('status') || null }) });
    }
    if (parsedUrl.pathname === '/api/memories' && req.method === 'POST') {
      try {
        const result = memoryStore.add(await readJson(req));
        broadcast({ type: 'workspace.memory', memory: result.entry, revision: result.revision });
        return sendJson(res, result.duplicate ? 200 : 201, { ok: true, ...result });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/memories/export' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, memories: memoryStore.export({ includeSensitive: parsedUrl.searchParams.get('includeSensitive') === 'true' }) });
    }
    const memoryMatch = parsedUrl.pathname.match(/^\/api\/memories\/([^/]+)$/);
    const memoryConfirmMatch = parsedUrl.pathname.match(/^\/api\/memories\/([^/]+)\/confirm$/);
    if (memoryConfirmMatch && req.method === 'POST') {
      try {
        const result = memoryStore.confirm(decodeURIComponent(memoryConfirmMatch[1]));
        broadcast({ type: 'workspace.memory', memory: result.entry, revision: result.revision });
        return sendJson(res, 200, { ok: true, ...result });
      }
      catch (error) { return sendJson(res, 404, { ok: false, error: error.message }); }
    }
    if (memoryMatch && req.method === 'PATCH') {
      try {
        const result = memoryStore.update(decodeURIComponent(memoryMatch[1]), await readJson(req));
        broadcast({ type: 'workspace.memory', memory: result.entry, revision: result.revision });
        return sendJson(res, 200, { ok: true, ...result });
      }
      catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (memoryMatch && req.method === 'DELETE') {
      const result = memoryStore.remove(decodeURIComponent(memoryMatch[1]));
      if (result.removed) broadcast({ type: 'workspace.memory', id: decodeURIComponent(memoryMatch[1]), operation: 'remove', revision: result.revision });
      return sendJson(res, result.removed ? 200 : 404, { ok: result.removed, ...result });
    }
    const aiProbeMatch = parsedUrl.pathname.match(/^\/api\/ai\/providers\/([^/]+)\/probe$/);
    if (aiProbeMatch && req.method === 'POST') {
      const providerId = decodeURIComponent(aiProbeMatch[1]);
      const probeResult = await aiProviderManager.probeProvider(providerId);
      if (probeResult.ok) {
        diagnosticsCollector.recordProviderLatency(providerId, probeResult.latencyMs);
      }
      return sendJson(res, probeResult.ok ? 200 : 400, probeResult);
    }
    if (parsedUrl.pathname === '/api/device/health' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, health: deviceHealthStore.snapshot() });
    }
    if (parsedUrl.pathname === '/api/proactive' && req.method === 'GET') {
      const policy = proactivePolicy.snapshot();
      return sendJson(res, 200, { ok: true, policy, ...policy });
    }
    if (parsedUrl.pathname === '/api/proactive/explain' && req.method === 'GET') {
      const key = parsedUrl.searchParams.get('key') || '';
      return sendJson(res, 200, { ok: true, explanation: proactivePolicy.explain(key) });
    }
    if (parsedUrl.pathname === '/api/proactive/mute' && req.method === 'POST') {
      try {
        const payload = await readJson(req);
        const policy = proactivePolicy.mute(Number(payload.minutes || 60) * 60 * 1000);
        return sendJson(res, 200, { ok: true, policy, ...policy });
      } catch (error) {
        return sendJson(res, 400, { ok: false, error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/proactive' && req.method === 'PATCH') {
      const payload = await readJson(req);
      const patch = { ...payload };
      if (patch.focus !== undefined && patch.focusActive === undefined) patch.focusActive = patch.focus;
      const policy = proactivePolicy.update(patch);
      if (payload.muteMinutes !== undefined) {
        const muted = proactivePolicy.mute(Number(payload.muteMinutes) * 60 * 1000);
        return sendJson(res, 200, { ok: true, policy: muted, ...muted });
      }
      return sendJson(res, 200, { ok: true, policy, ...policy });
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
    if (parsedUrl.pathname === '/api/autonomy/approvals' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, approvals: workspaceStore.listToolApprovals() });
    }
    if (parsedUrl.pathname === '/api/autonomy/approvals' && req.method === 'POST') {
      try {
        const approval = workspaceStore.requestToolApproval(await readJson(req));
        broadcast({ type: 'autonomy.approval', approval });
        return sendJson(res, 202, { ok: true, approval });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    const approvalMatch = parsedUrl.pathname.match(/^\/api\/autonomy\/approvals\/([^/]+)\/(approve|invoke)$/);
    if (approvalMatch && req.method === 'POST') {
      try {
        const approvalId = decodeURIComponent(approvalMatch[1]);
        if (approvalMatch[2] === 'approve') {
          const approval = workspaceStore.approveToolApproval(approvalId);
          broadcast({ type: 'autonomy.approval', approval });
          return sendJson(res, 200, { ok: true, approval });
        }
        const result = workspaceStore.invokeApprovedTool(approvalId, { onUpdate: broadcastActionUpdate });
        broadcast({ type: 'autonomy.approval', approval: result.approval });
        return sendJson(res, 200, { ok: true, ...result });
      } catch (error) { return sendJson(res, /expired|confirmation|consumed|blocked|policy/i.test(error.message) ? 403 : 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/motes' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, ...buildMoteProjection() });
    }
    if (parsedUrl.pathname === '/api/motes/active' && req.method === 'PATCH') {
      try {
        const state = moteStore.setActive((await readJson(req)).id);
        const story = progressMoteStory(`activation:${state.activeId}`);
        broadcastMoteState();
        return sendJson(res, 200, { ok: true, state, profile: moteStore.roster().find(item => item.active), story: story.events });
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
        const payload = await readJson(req);
        const result = applyMoteClue(payload);
        const story = result.businessStatus !== 'rejected'
          ? progressMoteStory(`clue:${payload.eventId}`, {
            clueCounts: { [String(payload.clueType).toLowerCase()]: 1 },
            unlockedCount: result.unlockedId ? 7 : 0,
          })
          : null;
        const status = result.businessStatus === 'rejected' ? 409 : result.duplicate || result.businessStatus === 'duplicate' ? 200 : 201;
        return sendJson(res, status, { ok: true, ...result, story: story?.events || moteStoryStore.list() });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/motes/story' && req.method === 'GET') {
      const story = progressMoteStory('story:snapshot');
      return sendJson(res, 200, { ok: true, story: story.events, state: story.state });
    }
    const storyBranchMatch = parsedUrl.pathname.match(/^\/api\/motes\/story\/([^/]+)\/branch$/);
    if (storyBranchMatch && req.method === 'POST') {
      try {
        const eventId = decodeURIComponent(storyBranchMatch[1]);
        const payload = await readJson(req);
        const result = moteStoryStore.chooseBranch(eventId, payload.choiceId);
        broadcast({ type: 'mote.story', story: moteStoryStore.list(), branch: result.branch, state: result.state });
        return sendJson(res, result.duplicate ? 200 : 201, { ok: true, ...result, story: moteStoryStore.list() });
      } catch (error) { return sendJson(res, /not complete|already chosen|already claimed|not found/i.test(error.message) ? 409 : 400, { ok: false, error: error.message }); }
    }
    const storyClaimMatch = parsedUrl.pathname.match(/^\/api\/motes\/story\/([^/]+)\/claim$/);
    if (storyClaimMatch && req.method === 'POST') {
      try {
        const eventId = decodeURIComponent(storyClaimMatch[1]);
        const payload = await readJson(req);
        const result = claimMoteStoryWithReward({
          storyStore: moteStoryStore,
          relationshipStore: moteRelationshipStore,
          eventId,
          claimId: payload.claimId || payload.eventId,
        });
        if (result.relationship && !result.relationship.duplicate) {
          broadcast({ type: 'mote.relationship', relationship: result.relationship });
        }
        broadcast({ type: 'mote.story', story: moteStoryStore.list(), claimed: result.event, state: result.state });
        return sendJson(res, result.duplicate ? 200 : 201, { ok: true, ...result, story: moteStoryStore.list() });
      } catch (error) { return sendJson(res, /not complete|not found/i.test(error.message) ? 409 : 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/motes/relationship' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, relationship: moteRelationshipStore.snapshot() });
    }
    if (parsedUrl.pathname === '/api/motes/relationship' && req.method === 'POST') {
      try {
        const payload = await readJson(req);
        const previousRelationshipLevel = moteRelationshipStore.snapshot().level;
        const result = moteRelationshipStore.recordInteraction(payload);
        progressMoteStory(`relationship:${payload.eventId || result.interactions}`, {
          relationshipLevel: result.level,
          previousRelationshipLevel,
        });
        broadcast({ type: 'mote.relationship', relationship: result });
        return sendJson(res, result.duplicate ? 200 : 201, { ok: true, ...result });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/motes/growth' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, growth: moteGrowthStore.snapshot() });
    }
    if (parsedUrl.pathname === '/api/reality/catalog' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, catalog: realityEngine.catalog() });
    }
    if (parsedUrl.pathname === '/api/reality/log' && req.method === 'GET') {
      try {
        const moteProfiles = { activeId: moteStore.getState().activeId, profiles: MOTE_PROFILES };
        const result = buildRealityLog({
          growthStore: moteGrowthStore,
          realityEngine,
          moteProfiles,
          cursor: parsedUrl.searchParams.has('cursor') ? parsedUrl.searchParams.get('cursor') : null,
          limit: parsedUrl.searchParams.has('limit') ? parsedUrl.searchParams.get('limit') : undefined,
        });
        return sendJson(res, 200, { ok: true, ...result });
      } catch (error) {
        if (error.code === 'invalid_cursor') return sendJson(res, 400, { ok: false, code: error.code, error: 'invalid reality log cursor' });
        if (error.code === 'invalid_limit') return sendJson(res, 400, { ok: false, code: error.code, error: 'invalid reality log limit' });
        return sendJson(res, 500, { ok: false, code: 'reality_log_unavailable', error: 'reality log is temporarily unavailable' });
      }
    }
    if (parsedUrl.pathname === '/api/reality/state' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, state: realityEngine.snapshot() });
    }
    if (parsedUrl.pathname === '/api/reality/progress' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, progress: buildRealityProgress() });
    }
    const realityReceiptMatch = parsedUrl.pathname.match(/^\/api\/reality\/receipts\/([^/]+)$/);
    if (realityReceiptMatch && req.method === 'GET') {
      const eventId = decodeURIComponent(realityReceiptMatch[1]);
      const growthReceipt = moteGrowthStore.getReceipt(eventId);
      const realityReceipt = realityEngine.getReceipt(eventId);
      if (!growthReceipt && !realityReceipt) return sendJson(res, 404, { ok: false, error: 'receipt not found' });
      return sendJson(res, 200, {
        ok: true,
        receipt: growthReceipt || realityReceipt,
        growthReceipt,
        realityReceipt,
      });
    }
    if (parsedUrl.pathname === '/api/reality/events' && req.method === 'GET') {
      const region = parsedUrl.searchParams.get('region') || realityEngine.snapshot().region || '';
      try { return sendJson(res, 200, { ok: true, events: realityEngine.eventsFor(region) }); }
      catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    const realityEventMatch = parsedUrl.pathname.match(/^\/api\/reality\/events\/([^/]+)\/(start|resolve)$/);
    if (realityEventMatch && req.method === 'POST') {
      try {
        const payload = await readJson(req);
        const eventId = decodeURIComponent(realityEventMatch[1]);
        if (realityEventMatch[2] === 'resolve') {
          MoteGrowthStore.validatePayload({
            eventId,
            clueType: payload.clueType,
            region: payload.region,
            activityAt: payload.activityAt,
          });
        }
        const activityAt = Number(payload.activityAt) || Date.now();
        const result = realityEventMatch[2] === 'start'
          ? realityEngine.startEncounter({ eventId, region: payload.region, at: activityAt })
          : applyMoteClue({
            ...payload,
            eventId,
            activityAt,
            offline: payload.offline === true,
          });
        if (realityEventMatch[2] === 'start') {
          result.story = progressMoteStory(`exploration-start:${eventId}`, { explorationCount: 1 });
        }
        if (realityEventMatch[2] === 'resolve') {
          completeRealityEncounter(eventId, payload, result);
        }
        const status = result.growth?.businessStatus === 'rejected'
          ? 409
          : result.duplicate || result.growth?.businessStatus === 'duplicate' ? 200 : 201;
        return sendJson(res, status, { ok: true, ...result });
      } catch (error) { return sendJson(res, /expired|invalid|region|clue/i.test(error.message) ? 409 : 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/reality/crafting' && req.method === 'POST') {
      try { const result = realityEngine.craft((await readJson(req)).recipeId); broadcast({ type: 'reality.inventory', state: result.state }); return sendJson(res, 201, { ok: true, ...result }); }
      catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/reality/loadout' && req.method === 'PATCH') {
      try { const state = realityEngine.setLoadout((await readJson(req)).items); broadcast({ type: 'reality.loadout', state }); return sendJson(res, 200, { ok: true, state }); }
      catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/reality/habitat' && req.method === 'PATCH') {
      try { const state = realityEngine.decorate((await readJson(req)).decorationId); broadcast({ type: 'reality.habitat', state }); return sendJson(res, 200, { ok: true, state }); }
      catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/motes/quests' && req.method === 'GET') {
      return sendJson(res, 200, { ok: true, quests: moteQuestStore.list() });
    }
    const questMatch = parsedUrl.pathname.match(/^\/api\/motes\/quests\/([^/]+)\/claim$/);
    if (questMatch && req.method === 'POST') {
      try {
        const result = moteQuestStore.claim(decodeURIComponent(questMatch[1]), (await readJson(req)).eventId);
        if (!result.duplicate && result.quest.reward) moteRelationshipStore.recordInteraction({ eventId: `quest:${result.state.claimed.at(-1).eventId}`, kind: 'quest', amount: result.quest.reward });
        broadcast({ type: 'mote.quest', quests: moteQuestStore.list(), result });
        return sendJson(res, result.duplicate ? 200 : 201, { ok: true, ...result });
      } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
    }
    if (parsedUrl.pathname === '/api/auth/rotate' && req.method === 'POST') {
      try {
        rotateAccessToken();
        addLog('warn', '节点访问令牌已轮换，旧令牌立即失效');
        return sendJson(res, 200, { ok: true, rotated: true });
      } catch (error) {
        return sendJson(res, 409, { ok: false, error: error.message });
      }
    }
    if (parsedUrl.pathname === '/api/workspace/events' && req.method === 'GET') {
      const since = Number(parsedUrl.searchParams.get('since') || 0);
      return sendJson(res, 200, { ok: true, revision: workspaceStore.eventRevision, ...workspaceStore.syncState(since) });
    }
    if (parsedUrl.pathname === '/api/workspace/events' && req.method === 'POST') {
      const payload = await readJson(req);
      const event = payload.event || payload;
      const accepted = acceptWorkspaceEvent(event && event.type
        ? event
        : createEventEnvelope({ origin: String(payload.origin || 'phone'), sequence: Number(payload.sequence || 0), type: String(payload.type || 'workspace.event'), payload: payload.payload || payload, privacyRevisions: payload.privacyRevisions }));
      let business = {
        ...workspaceBusinessAck(accepted),
        resultRevision: accepted.event.revision || workspaceStore.eventRevision,
      };
      if (shouldApplyWorkspaceEvent(accepted.event, accepted)) {
        try { business = applyWorkspaceEvent(accepted.event) || business; }
        catch (error) { business = { ...business, businessStatus: 'rejected', reason: error.message }; }
      }
      if (accepted.accepted) broadcast({ type: 'workspace.event', event: accepted.event });
      return sendJson(res, accepted.accepted ? 202 : 200, {
        ok: true,
        ...accepted,
        businessAccepted: business.businessStatus === 'accepted' || business.businessStatus === 'duplicate',
        businessStatus: business.businessStatus,
        reason: business.reason || null,
        businessReason: business.reason || null,
        resultRevision: business.resultRevision || accepted.event.revision || workspaceStore.eventRevision,
        businessResult: business,
      });
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
    const sessionMatch = parsedUrl.pathname.match(/^\/api\/workspace\/sessions\/([^/]+)(?:\/(messages|authorize|revoke|policy|ai))?$/);
    if (sessionMatch) {
      const sessionId = decodeURIComponent(sessionMatch[1]);
      const suffix = sessionMatch[2] || '';
      if (!workspaceStore.getSession(sessionId)) return sendJson(res, 404, { ok: false, error: 'session not found' });
      if (suffix === 'messages' && req.method === 'POST') {
        const payload = await readJson(req);
        const message = workspaceStore.appendMessage(sessionId, payload);
        const requestId = `ai_${crypto.randomUUID()}`;
        const remember = payload.remember !== false;
        const task = workspaceStore.createTask({ source: 'conversation', title: `会话：${String(message.text).slice(0, 36)}`, detail: message.text, metadata: { sessionId, messageId: message.id, requestId, remember, memories: remember && Array.isArray(payload.memories) ? payload.memories : [] } });
        broadcast({ type: 'workspace.message', sessionId, message });
        broadcast({ type: 'workspace.task', task });
        if (payload.runModel !== false && message.role === 'user') {
          runWorkspaceMessage(sessionId, message, task.id, remember && Array.isArray(payload.memories) ? payload.memories : [], remember).catch(() => {});
        }
        return sendJson(res, 202, { ok: true, message, task, requestId: task.metadata.requestId });
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
      if (suffix === 'ai' && req.method === 'PATCH') {
        try {
          const session = workspaceStore.updateSession(sessionId, { aiPolicy: await readJson(req) });
          broadcast({ type: 'workspace.session', session });
          return sendJson(res, 200, { ok: true, session });
        } catch (error) { return sendJson(res, 400, { ok: false, error: error.message }); }
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
      for (const run of taskRunner.list()) if (run.state === 'running' || run.state === 'paused') taskRunner.cancel(run.id);
      addLog('warn', 'AI 工具急停已启用');
      broadcast({ type: 'workspace.emergency_stop', state });
      return sendJson(res, 200, { ok: true, state });
    }
    if (parsedUrl.pathname === '/api/tools/emergency-stop/clear' && req.method === 'POST') {
      const state = workspaceStore.clearEmergencyStop();
      broadcast({ type: 'workspace.emergency_stop', state });
      return sendJson(res, 200, { ok: true, state });
    }
    if (parsedUrl.pathname === '/api/tasks' && req.method === 'GET') return sendJson(res, 200, { ok: true, tasks: workspaceStore.listTasks({ state: parsedUrl.searchParams.get('state'), source: parsedUrl.searchParams.get('source'), limit: parsedUrl.searchParams.get('limit') }) });
    if (parsedUrl.pathname === '/api/tasks' && req.method === 'POST') {
      const task = workspaceStore.createTask(await readJson(req));
      broadcast({ type: 'workspace.task', task });
      return sendJson(res, 201, { ok: true, task });
    }
    const taskMatch = parsedUrl.pathname.match(/^\/api\/tasks\/([^/]+)$/);
    const taskAuditMatch = parsedUrl.pathname.match(/^\/api\/tasks\/([^/]+)\/audit$/);
    const taskActionMatch = parsedUrl.pathname.match(/^\/api\/tasks\/([^/]+)\/actions$/);
    if (taskAuditMatch && req.method === 'GET') {
      const task = workspaceStore.getTask(decodeURIComponent(taskAuditMatch[1]));
      return sendJson(res, task ? 200 : 404, { ok: Boolean(task), taskId: task?.id || null, audit: task ? workspaceStore.listTaskAudit(task.id) : [] });
    }
    if (taskActionMatch && req.method === 'POST') {
      try {
        const taskId = decodeURIComponent(taskActionMatch[1]);
        const payload = await readJson(req);
        const task = workspaceStore.applyTaskAction(taskId, payload.action, { actor: payload.actor || 'web', idempotencyKey: payload.idempotencyKey || req.headers['idempotency-key'] || null, detail: payload.detail || '' });
        if (taskRunner.has(taskId)) {
          const action = String(payload.action || '').toLowerCase();
          if (action === 'pause') taskRunner.pause(taskId);
          else if (action === 'continue' || action === 'resume') taskRunner.resume(taskId);
          else if (action === 'cancel') taskRunner.cancel(taskId);
          else if (action === 'retry') taskRunner.retry(taskId);
        }
        broadcast({ type: 'workspace.task', task });
        broadcast({ type: 'task.audit', taskId, audit: workspaceStore.listTaskAudit(taskId).at(-1) || null });
        broadcastTaskAttention(task);
        return sendJson(res, 200, { ok: true, task, audit: workspaceStore.listTaskAudit(taskId).at(-1) || null });
      } catch (error) { return sendJson(res, /not found/i.test(error.message) ? 404 : 409, { ok: false, error: error.message }); }
    }
    if (taskMatch) {
      const taskId = decodeURIComponent(taskMatch[1]);
      if (req.method === 'GET') {
        const task = workspaceStore.getTask(taskId);
        return sendJson(res, task ? 200 : 404, { ok: Boolean(task), task, audit: task ? workspaceStore.listTaskAudit(taskId) : [] });
      }
      if (req.method === 'PATCH') {
        const task = workspaceStore.updateTask(taskId, await readJson(req));
        broadcast({ type: 'workspace.task', task });
        broadcastTaskAttention(task);
        return sendJson(res, 200, { ok: true, task });
      }
    }
    if (parsedUrl.pathname === '/api/motes/behavior' && req.method === 'GET') {
      const active = moteStore.roster().find(item => item.active) || moteStore.roster()[0];
      return sendJson(res, 200, { ok: true, behavior: deriveMoteBehavior({ profileId: active?.id, taskState: parsedUrl.searchParams.get('taskState') || 'idle', deviceHealth: parsedUrl.searchParams.get('deviceHealth') || 'unknown', interaction: parsedUrl.searchParams.get('interaction') || 'none', explorationActive: parsedUrl.searchParams.get('explorationActive') === 'true', mood: Number(parsedUrl.searchParams.get('mood') || 0), relationshipLevel: moteRelationshipStore.snapshot().level }) });
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
      const view = parsedUrl.searchParams.get('view') === 'summary' ? 'summary' : 'full';
      const body = view === 'summary' ? summaryPayload() : snapshotPayload();
      const etag = `"workspace-${snapshotCache.stats().revision}-${view}"`;
      if (req.headers['if-none-match'] === etag) {
        res.writeHead(304, { ETag: etag, 'Cache-Control': 'no-store' });
        return res.end();
      }
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', ETag: etag, 'Cache-Control': 'no-store' });
      return res.end(body);
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
      queueSnapshotBroadcast();
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
      queueSnapshotBroadcast();
      res.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      return res.end(JSON.stringify({ok:true, minutes}));
    }
    if (parsedUrl.pathname === '/api/chat') {
      const body = await readBody(req);
      const payload = JSON.parse(body || '{}');
      markInteraction();
      const remember = payload.remember !== false;
      const requestId = /^[A-Za-z0-9_.:-]{1,120}$/.test(String(payload.requestId || ''))
        ? String(payload.requestId)
        : `chat_${crypto.randomUUID()}`;
      handleChat(payload.text, remember && Array.isArray(payload.memories) ? payload.memories : [], { remember, requestId, source: payload.source || 'chat' }).then(result=>{
        res.writeHead(200,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(result));
      }).catch(err=>{const cancelled=/AI request cancelled/i.test(String(err?.message||''));if(!cancelled)fs.appendFileSync(path.join(__dirname,'chat_error.log'),`${new Date().toISOString()} ${err.stack}\n`);res.writeHead(cancelled?409:500,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify({ok:false,error:err.message}))});
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
        queueSnapshotBroadcast();
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
          queueSnapshotBroadcast();
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
};
const server = TLS_OPTIONS
  ? https.createServer(TLS_OPTIONS, handleHttpRequest)
  : http.createServer(handleHttpRequest);

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
        if (privacyCenter.isDeleting() && !['snapshot', 'handoff_get'].includes(String(json.type || ''))) {
          if (json.type === 'workspace.event') {
            ws.send(JSON.stringify({
              type: 'workspace.ack',
              eventId: String(json.eventId || ''),
              accepted: false,
              status: 'privacy_deletion_in_progress',
              businessStatus: 'rejected',
              businessAccepted: false,
              reason: 'privacy_deletion_in_progress',
              resultRevision: workspaceStore.eventRevision,
            }));
          } else {
            ws.send(JSON.stringify({ type: 'privacy.write_blocked', reason: 'privacy_deletion_in_progress', requestId: json.requestId || null }));
          }
          return;
        }
        switch(json.type) {
          case 'snapshot': {
            const since = Number(json.since || 0);
            if (since > 0) ws.send(JSON.stringify({ type: 'workspace.events', revision: workspaceStore.eventRevision, events: workspaceStore.eventsAfter(since) }));
            else ws.send(snapshotPayload());
            break;
          }
          case 'workspace.event': {
            const event = createEventEnvelope({
              eventId: String(json.eventId || crypto.randomUUID()),
              origin: String(json.origin || 'phone'),
              sequence: Number(json.sequence || 0),
              type: String(json.eventType || 'workspace.event'),
              payload: json.payload || {},
              createdAt: json.createdAt || new Date().toISOString(),
              privacyRevisions: json.privacyRevisions,
              ack: false,
            });
            const accepted = acceptWorkspaceEvent(event);
            let business = {
              ...workspaceBusinessAck(accepted),
              resultRevision: accepted.event.revision || workspaceStore.eventRevision,
            };
            if (shouldApplyWorkspaceEvent(accepted.event, accepted)) {
              try {
                business = applyWorkspaceEvent(accepted.event) || business;
              } catch (error) {
                business = { businessStatus: 'rejected', reason: error.message, resultRevision: accepted.event.revision || workspaceStore.eventRevision };
              }
            }
            ws.send(JSON.stringify({
              type: 'workspace.ack',
              eventId: event.eventId,
              accepted: accepted.accepted,
              status: accepted.status,
              businessStatus: business.businessStatus || (accepted.accepted ? 'accepted' : 'rejected'),
              businessAccepted: business.businessStatus === 'accepted' || business.businessStatus === 'duplicate',
              reason: business.reason || null,
              resultRevision: business.resultRevision || accepted.event.revision || workspaceStore.eventRevision,
              revision: accepted.event.revision || workspaceStore.eventRevision,
              origin: event.origin,
            }));
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
          case 'chat': {
            markInteraction();
            const requestId = /^[A-Za-z0-9_.:-]{1,120}$/.test(String(json.requestId || ''))
              ? String(json.requestId)
              : `chat_${crypto.randomUUID()}`;
            handleChat(json.text, json.remember === false ? [] : (Array.isArray(json.memories) ? json.memories : []), {
              remember: json.remember !== false,
              requestId,
              source: json.source || 'chat',
            }).catch(error => {
              const cancelled = /cancel/i.test(String(error?.message || ''));
              try {
                ws.send(JSON.stringify({
                  type: cancelled ? 'chat_cancelled' : 'chat_error',
                  requestId,
                  error: cancelled ? '本轮已停止。' : '回复未完成；可以重试，或继续刚才的话题。',
                }));
              } catch (_) {}
              if (!cancelled) addLog('error', '聊天生成未完成');
            });
            break;
          }
          case 'chat_cancel': {
            const requestId = String(json.requestId || '').slice(0, 120);
            const cancelled = /^[A-Za-z0-9_.:-]{1,120}$/.test(requestId) && aiProviderManager.cancel(requestId);
            try { ws.send(JSON.stringify({ type: 'chat_cancelled', requestId, cancelled })); } catch (_) {}
            break;
          }
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
              .then(()=>{queueSnapshotBroadcast();addLog('success',`模型已切换：${json.model||''}`)})
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
setInterval(()=>{refreshCodexInfo().finally(()=>queueSnapshotBroadcast())},5000);
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
setInterval(()=>{if(selectedCodexTaskId)refreshSelectedCodexTask().finally(()=>queueSnapshotBroadcast())},15000);
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
  console.log(`PhoneBridge server running on ${TLS_ENABLED ? 'https' : 'http'}://${BIND_HOST}:${PORT}`);
});
