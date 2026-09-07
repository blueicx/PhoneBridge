const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const TASK_STATES = new Set(['pending', 'running', 'paused', 'needs_confirmation', 'succeeded', 'failed', 'cancelled', 'archived']);
const ATTENTION_STATUSES = new Set(['open', 'read', 'snoozed', 'resolved', 'dismissed']);
const ATTENTION_SEVERITIES = new Set(['low', 'medium', 'high', 'critical']);
const AUTONOMY_LEVELS = new Set(['observe', 'reversible', 'whitelist']);
const ACTION_RUN_STATES = new Set(['queued', 'running', 'succeeded', 'failed', 'cancelled', 'blocked']);
const ACTION_RUN_APPROVALS = new Set(['pending', 'granted', 'denied']);
const TERMINAL_ACTION_RUN_STATES = new Set(['succeeded', 'failed', 'cancelled', 'blocked']);
const SENSITIVE_KEY_PATTERN = /(token|secret|password|authorization|cookie|audio|image|frame|pcm|base64)/i;
const SENSITIVE_VALUE_PATTERN = /(bearer\s+[a-z0-9._-]+|password\s*=|secret|token|sk-[a-z0-9_-]+)/i;
const HARD_DENIED_TOOL_PATTERN = /(^|[._-])(shell|exec|delete|destroy|credential|secret|token|publish|release|reset|wipe)([._-]|$)/i;

function id(prefix) {
  return `${prefix}_${crypto.randomUUID()}`;
}

function iso(now) {
  return new Date(now).toISOString();
}

function parseTimestamp(value) {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function limitText(value, maxLength = 240) {
  const text = String(value ?? '');
  if (text.length <= maxLength) return text;
  return `${text.slice(0, Math.max(0, maxLength - 1))}\u2026`;
}

function createEventEnvelope({
  eventId = crypto.randomUUID(),
  origin = 'node',
  sequence = 0,
  type,
  payload = {},
  createdAt = iso(Date.now()),
  ack = false,
} = {}) {
  if (!type) throw new Error('event type is required');
  return { eventId, origin, sequence, type, payload, createdAt, ack };
}

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value));
}

function compare(actual, op, expected) {
  switch (op) {
    case 'lt': return actual < expected;
    case 'lte': return actual <= expected;
    case 'eq': return actual === expected;
    case 'gte': return actual >= expected;
    case 'gt': return actual > expected;
    case 'in': return Array.isArray(expected) && expected.includes(actual);
    default: return false;
  }
}

function sanitizeValue(value, key = '', depth = 0) {
  if (value == null) return value;
  if (depth >= 4) return '[TRUNCATED]';
  if (typeof value === 'string') {
    if (SENSITIVE_KEY_PATTERN.test(key) || SENSITIVE_VALUE_PATTERN.test(value)) return '[REDACTED]';
    return limitText(value, 160);
  }
  if (typeof value === 'number' || typeof value === 'boolean') return value;
  if (Array.isArray(value)) {
    const items = value.slice(0, 8).map((item) => sanitizeValue(item, key, depth + 1));
    if (value.length > 8) items.push(`[+${value.length - 8} more]`);
    return items;
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value);
    const result = {};
    for (const [entryKey, entryValue] of entries.slice(0, 12)) {
      result[entryKey] = sanitizeValue(entryValue, entryKey, depth + 1);
    }
    if (entries.length > 12) result.__truncatedKeys = entries.length - 12;
    return result;
  }
  return String(value);
}

function jsonSummary(value, maxLength = 320) {
  return limitText(JSON.stringify(sanitizeValue(value)), maxLength);
}

function policyKey(scopeType, targetId) {
  return `${scopeType}:${targetId}`;
}

function sortByUpdatedAtDesc(values) {
  return values.sort((left, right) => String(right.updatedAt || '').localeCompare(String(left.updatedAt || '')));
}

function normalizeIsoOrNull(value) {
  if (value == null || value === '') return null;
  const parsed = parseTimestamp(value);
  return parsed == null ? null : iso(parsed);
}

function isHardDeniedToolId(toolId) {
  return HARD_DENIED_TOOL_PATTERN.test(String(toolId || ''));
}

class WorkspaceStore {
  constructor({ now = () => Date.now(), journalPath = null, snapshotPath = null } = {}) {
    this.now = now;
    this.journalPath = journalPath;
    this.snapshotPath = snapshotPath;
    this.sessions = new Map();
    this.tools = new Map();
    this.tasks = new Map();
    this.automations = new Map();
    this.runs = [];
    this.attention = new Map();
    this.policies = new Map();
    this.actionRuns = new Map();
    this.eventLog = [];
    this.eventKeys = new Set();
    this.audit = [];
    this.sequence = 0;
    this.emergency = { active: false, reason: null, updatedAt: iso(this.now()) };
    this._loadSnapshot();
  }

  _loadSnapshot() {
    if (!this.snapshotPath) return;
    try {
      const data = JSON.parse(fs.readFileSync(this.snapshotPath, 'utf8'));
      for (const session of data.sessions || []) this.sessions.set(session.id, session);
      for (const task of data.tasks || []) this.tasks.set(task.id, task);
      for (const automation of data.automations || []) this.automations.set(automation.id, automation);
      this.runs = data.runs || [];
      for (const item of data.attention || data.attentionItems || []) this.attention.set(item.id, item);
      for (const policy of data.policies || []) this.policies.set(policyKey(policy.scopeType, policy.targetId), policy);
      for (const run of data.actionRuns || []) this.actionRuns.set(run.id, run);
      this.audit = data.audit || [];
      this.eventLog = data.eventLog || [];
      this.eventKeys = new Set(data.eventKeys || []);
      for (const event of this.eventLog) {
        this.eventKeys.add(`${event.origin}:${event.sequence}`);
        if (event.eventId) this.eventKeys.add(`event:${event.eventId}`);
      }
      this.sequence = Number(data.sequence) || 0;
      if (data.emergency && typeof data.emergency === 'object') {
        this.emergency = {
          active: Boolean(data.emergency.active),
          reason: data.emergency.reason == null ? null : String(data.emergency.reason),
          updatedAt: data.emergency.updatedAt || iso(this.now()),
        };
      }
    } catch (error) {
      if (error.code !== 'ENOENT') this.loadError = error;
    }
  }

  _persist() {
    if (!this.snapshotPath) return;
    const directory = path.dirname(this.snapshotPath);
    fs.mkdirSync(directory, { recursive: true });
    const temporary = `${this.snapshotPath}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify({
      sessions: [...this.sessions.values()],
      tasks: [...this.tasks.values()],
      automations: [...this.automations.values()],
      runs: this.runs,
      attention: [...this.attention.values()],
      policies: [...this.policies.values()],
      actionRuns: [...this.actionRuns.values()],
      audit: this.audit,
      eventLog: this.eventLog,
      eventKeys: [...this.eventKeys],
      sequence: this.sequence,
      emergency: this.emergency,
    }, null, 2));
    fs.renameSync(temporary, this.snapshotPath);
  }

  _appendJournal(event) {
    if (!this.journalPath) return;
    fs.mkdirSync(path.dirname(this.journalPath), { recursive: true });
    fs.appendFileSync(this.journalPath, `${JSON.stringify(event)}\n`);
  }

  _defaultPolicy(scopeType, targetId) {
    const timestamp = iso(this.now());
    return {
      id: `policy:${scopeType}:${targetId}`,
      scopeType,
      targetId,
      level: scopeType === 'global' ? 'whitelist' : 'reversible',
      allowedTools: scopeType === 'global' ? this.listTools().filter(tool => tool.readOnly).map(tool => tool.id) : this.listTools().map(tool => tool.id),
      expiresAt: null,
      continuousMic: true,
      confirmationRules: [],
      createdAt: timestamp,
      updatedAt: timestamp,
      persisted: false,
    };
  }

  _getPolicy(scopeType, targetId) {
    const existing = this.policies.get(policyKey(scopeType, targetId));
    if (existing) return clone(existing);
    return this._defaultPolicy(scopeType, targetId);
  }

  _updatePolicy(scopeType, targetId, patch = {}) {
    const existing = this.policies.get(policyKey(scopeType, targetId)) || this._defaultPolicy(scopeType, targetId);
    const timestamp = iso(this.now());
    if (patch.level !== undefined && !AUTONOMY_LEVELS.has(String(patch.level))) throw new Error(`invalid autonomy level: ${patch.level}`);
    if (scopeType === 'global' && patch.level !== undefined && String(patch.level) !== 'whitelist') throw new Error('global autonomy must use whitelist level');
    const requestedTools = patch.allowedTools !== undefined
      ? [...new Set((Array.isArray(patch.allowedTools) ? patch.allowedTools : []).map(item => String(item)).filter(Boolean))]
      : existing.allowedTools;
    if (scopeType === 'global') {
      for (const toolId of requestedTools) {
        if (isHardDeniedToolId(toolId)) throw new Error(`hard-denied tool is forbidden: ${toolId}`);
        if (!this.tools.has(toolId)) throw new Error(`unregistered tool is forbidden: ${toolId}`);
      }
    }
    const policy = {
      ...existing,
      id: existing.id || `policy:${scopeType}:${targetId}`,
      scopeType,
      targetId,
      level: patch.level !== undefined ? String(patch.level) : existing.level,
      allowedTools: requestedTools,
      expiresAt: patch.expiresAt !== undefined ? normalizeIsoOrNull(patch.expiresAt) : normalizeIsoOrNull(existing.expiresAt),
      continuousMic: patch.continuousMic !== undefined ? Boolean(patch.continuousMic) : Boolean(existing.continuousMic),
      confirmationRules: patch.confirmationRules !== undefined
        ? clone(Array.isArray(patch.confirmationRules) ? patch.confirmationRules : [])
        : clone(existing.confirmationRules || []),
      createdAt: existing.createdAt || timestamp,
      updatedAt: timestamp,
    };
    delete policy.persisted;
    this.policies.set(policyKey(scopeType, targetId), policy);
    this._persist();
    return clone(policy);
  }

  _createActionRun({ origin, sessionId = null, automationId = null, taskId = null, toolId, args = {}, expiresAt = null }) {
    const run = {
      id: id('action'),
      origin,
      sessionId,
      automationId,
      taskId,
      toolId,
      argsSummary: jsonSummary(args, 320),
      state: 'queued',
      approval: 'pending',
      createdAt: iso(this.now()),
      startedAt: null,
      endedAt: null,
      resultRef: null,
      error: null,
      expiresAt: normalizeIsoOrNull(expiresAt),
      updatedAt: iso(this.now()),
    };
    this.actionRuns.set(run.id, run);
    this._persist();
    return run;
  }

  _touchActionRun(run, patch = {}) {
    if (patch.state !== undefined && !ACTION_RUN_STATES.has(String(patch.state))) throw new Error(`invalid action run state: ${patch.state}`);
    if (patch.approval !== undefined && !ACTION_RUN_APPROVALS.has(String(patch.approval))) throw new Error(`invalid action approval: ${patch.approval}`);
    if (patch.state !== undefined) run.state = String(patch.state);
    if (patch.approval !== undefined) run.approval = String(patch.approval);
    if (patch.taskId !== undefined) run.taskId = patch.taskId ? String(patch.taskId) : null;
    if (patch.resultRef !== undefined) run.resultRef = patch.resultRef == null ? null : String(patch.resultRef);
    if (patch.error !== undefined) run.error = patch.error == null ? null : limitText(String(patch.error), 240);
    if (patch.startedAt !== undefined) run.startedAt = patch.startedAt;
    if (patch.endedAt !== undefined) run.endedAt = patch.endedAt;
    if (patch.expiresAt !== undefined) run.expiresAt = normalizeIsoOrNull(patch.expiresAt);
    run.updatedAt = iso(this.now());
    this._persist();
    return clone(run);
  }

  _writeAudit({ actor, toolId, args, status, result = null, error = null, actionRunId = null }) {
    const entry = {
      id: id('audit'),
      sessionId: actor,
      toolId,
      args: sanitizeValue(args),
      createdAt: iso(this.now()),
      status,
      actionRunId,
    };
    if (result !== null) entry.result = sanitizeValue(result);
    if (error) entry.error = limitText(error, 240);
    this.audit.push(entry);
    this._persist();
    return entry;
  }

  _authorizationFailure(state, reason) {
    return { allowed: false, state, reason };
  }

  _toolCapability(tool) {
    return tool.autonomyLevel || (tool.readOnly ? 'observe' : 'reversible');
  }

  _confirmationRequired(policy, toolId, options = {}) {
    if (!Array.isArray(policy.confirmationRules)) return false;
    const matched = policy.confirmationRules.find(rule => rule && (rule.toolId === toolId || rule.toolId === '*'));
    return Boolean(matched && matched.requireConfirmation && options.confirmed !== true);
  }

  _authorizeInvocation({ origin, sessionId = null, automationId = null, toolId, tool, args = {}, expiresAt = null, confirmed = false }) {
    if (this.emergency.active) return this._authorizationFailure('blocked', 'emergency stop is active');
    const actionExpiresAt = normalizeIsoOrNull(expiresAt);
    if (actionExpiresAt && parseTimestamp(actionExpiresAt) <= this.now()) return this._authorizationFailure('cancelled', 'action expired');

    let policy;
    if (origin === 'session') {
      const session = this.sessions.get(sessionId);
      if (!session) return this._authorizationFailure('blocked', 'session not found');
      if (!session.armedUntil || this.now() >= session.armedUntil) return this._authorizationFailure('blocked', 'session authorization is required or expired');
      policy = this._getPolicy('session', sessionId);
    } else if (origin === 'automation') {
      const automation = this.automations.get(automationId);
      if (!automation) return this._authorizationFailure('blocked', 'automation not found');
      if (!automation.enabled) return this._authorizationFailure('blocked', 'automation is disabled');
      policy = this._getPolicy('automation', automationId);
    } else {
      policy = this._getPolicy('global', 'personal');
    }

    if (policy.expiresAt && parseTimestamp(policy.expiresAt) <= this.now()) return this._authorizationFailure('blocked', 'autonomy policy expired');
    if (policy.level === 'whitelist' && (!Array.isArray(policy.allowedTools) || !policy.allowedTools.includes(toolId))) {
      return this._authorizationFailure('blocked', 'tool is not allowed by autonomy policy');
    }
    if (policy.level !== 'whitelist' && Array.isArray(policy.allowedTools) && policy.allowedTools.length > 0 && !policy.allowedTools.includes(toolId)) {
      return this._authorizationFailure('blocked', 'tool is not allowed by autonomy policy');
    }
    if (isHardDeniedToolId(toolId)) return this._authorizationFailure('blocked', 'hard-denied tool is forbidden');
    if (policy.level === 'observe' && this._toolCapability(tool) !== 'observe') {
      return this._authorizationFailure('blocked', 'observe policy only allows observe tools');
    }
    if (origin === 'automation') {
      const globalPolicy = this._getPolicy('global', 'personal');
      if (globalPolicy.expiresAt && parseTimestamp(globalPolicy.expiresAt) <= this.now()) return this._authorizationFailure('blocked', 'global autonomy policy expired');
      if (globalPolicy.level === 'whitelist' && !globalPolicy.allowedTools.includes(toolId)) return this._authorizationFailure('blocked', 'tool is not allowed by global autonomy whitelist');
    }
    if (toolId === 'device.listen' && args && args.enabled !== false && policy.continuousMic === false) {
      return this._authorizationFailure('blocked', 'continuous microphone is disabled by autonomy policy');
    }
    if (this._confirmationRequired(policy, toolId, { confirmed })) {
      return this._authorizationFailure('blocked', 'confirmation is required by autonomy policy');
    }
    return { allowed: true, policy, actionExpiresAt };
  }

  _finalResultReference(result, run) {
    if (run.taskId) return `task:${run.taskId}`;
    return jsonSummary(result, 240);
  }

  _linkTaskToActionRun(run, result) {
    if (run.taskId) return run.taskId;
    if (result && result.id && this.tasks.has(result.id)) return result.id;
    if (result && result.taskId && this.tasks.has(result.taskId)) return result.taskId;
    return null;
  }

  _invokeRegisteredToolInvocation({
    origin,
    sessionId = null,
    automationId = null,
    taskId = null,
    toolId,
    args = {},
    expiresAt = null,
    confirmed = false,
    onUpdate = null,
  }) {
    const tool = this.tools.get(toolId);
    if (!tool) throw new Error(`unknown registered tool: ${toolId}`);
    const actor = origin === 'automation' ? `automation:${automationId}` : (sessionId || origin || 'direct');
    const run = this._createActionRun({ origin, sessionId, automationId, taskId, toolId, args, expiresAt });
    const emitUpdate = () => {
      if (typeof onUpdate === 'function') onUpdate(clone(run));
    };
    emitUpdate();

    const authorization = this._authorizeInvocation({ origin, sessionId, automationId, toolId, tool, args, expiresAt, confirmed });
    if (!authorization.allowed) {
      this._touchActionRun(run, {
        state: authorization.state,
        approval: 'denied',
        endedAt: iso(this.now()),
        error: authorization.reason,
      });
      emitUpdate();
      throw Object.assign(new Error(authorization.reason), { actionRunId: run.id, actionRun: clone(run) });
    }

    this._touchActionRun(run, {
      state: 'running',
      approval: 'granted',
      startedAt: iso(this.now()),
      expiresAt: authorization.actionExpiresAt,
    });
    emitUpdate();

    try {
      const result = tool.invoke(clone(args), {
        sessionId,
        automationId,
        taskId,
        origin,
        store: this,
        actionRunId: run.id,
      });
      if (result && typeof result.then === 'function') throw new Error('async tools must be invoked through invokeToolAsync');
      const linkedTaskId = this._linkTaskToActionRun(run, result);
      this._touchActionRun(run, {
        state: 'succeeded',
        taskId: linkedTaskId,
        resultRef: this._finalResultReference(result, { ...run, taskId: linkedTaskId }),
        endedAt: iso(this.now()),
      });
      emitUpdate();
      const audit = this._writeAudit({ actor, toolId, args, status: 'succeeded', result, actionRunId: run.id });
      return { toolId, result: clone(result), auditId: audit.id, actionRunId: run.id, actionRun: clone(run) };
    } catch (error) {
      this._touchActionRun(run, {
        state: 'failed',
        endedAt: iso(this.now()),
        error: error.message,
      });
      emitUpdate();
      const audit = this._writeAudit({ actor, toolId, args, status: 'failed', error: error.message, actionRunId: run.id });
      throw Object.assign(error, { auditId: audit.id, actionRunId: run.id, actionRun: clone(run) });
    }
  }

  createSession({ id: sessionId = id('session'), title = '新会话', providerId = 'codex', model = '', systemPrompt = '', tools = [] } = {}) {
    const timestamp = iso(this.now());
    const session = { id: sessionId, title, providerId, model, systemPrompt, tools, messages: [], createdAt: timestamp, updatedAt: timestamp, armedUntil: null };
    this.sessions.set(session.id, session);
    this._persist();
    return clone(session);
  }

  getSession(sessionId) {
    return clone(this.sessions.get(sessionId) || null);
  }

  listSessions() {
    return [...this.sessions.values()].map(clone).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  updateSession(sessionId, patch = {}) {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error('session not found');
    const allowed = ['title', 'providerId', 'model', 'systemPrompt', 'tools'];
    for (const key of allowed) if (patch[key] !== undefined) session[key] = patch[key];
    session.updatedAt = iso(this.now());
    this._persist();
    return clone(session);
  }

  appendMessage(sessionId, { role = 'user', text = '', ...extra } = {}) {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error('session not found');
    const message = { ...extra, id: extra.id || id('message'), role: String(role), text: String(text ?? ''), createdAt: extra.createdAt || iso(this.now()) };
    const existing = session.messages.find(item => item.id === message.id);
    if (existing) return clone(existing);
    session.messages.push(message);
    session.updatedAt = message.createdAt;
    this._persist();
    return clone(message);
  }

  acceptEvent(event) {
    const normalized = createEventEnvelope(event);
    const key = `${normalized.origin}:${normalized.sequence}`;
    if (this.eventKeys.has(key) || this.eventKeys.has(`event:${normalized.eventId}`)) return { accepted: false, event: clone(normalized) };
    this.eventKeys.add(key);
    this.eventKeys.add(`event:${normalized.eventId}`);
    this.eventLog.push(normalized);
    this._appendJournal(normalized);
    this._persist();
    return { accepted: true, event: clone(normalized) };
  }

  events() {
    return clone(this.eventLog);
  }

  registerTool({ id: toolId, title, description = '', readOnly = false, autonomyLevel = null, invoke }) {
    if (!toolId || typeof invoke !== 'function') throw new Error('tool id and invoke function are required');
    if (isHardDeniedToolId(toolId)) throw new Error(`hard-denied tool is forbidden: ${toolId}`);
    const level = autonomyLevel || (readOnly ? 'observe' : 'reversible');
    if (!AUTONOMY_LEVELS.has(level)) throw new Error(`invalid autonomy level: ${level}`);
    this.tools.set(toolId, { id: toolId, title: title || toolId, description, readOnly, autonomyLevel: level, invoke });
    return this.listTools().find(tool => tool.id === toolId);
  }

  listTools() {
    return [...this.tools.values()].map(({ invoke, ...tool }) => clone(tool));
  }

  armSession(sessionId, durationMs = 15 * 60 * 1000) {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error('session not found');
    if (this.emergency.active) throw new Error('emergency stop is active');
    session.armedUntil = this.now() + Math.max(1, durationMs);
    session.updatedAt = iso(this.now());
    this._persist();
    return { sessionId, armedUntil: session.armedUntil };
  }

  revokeSession(sessionId) {
    const session = this.sessions.get(sessionId);
    if (!session) throw new Error('session not found');
    session.armedUntil = null;
    session.updatedAt = iso(this.now());
    this._persist();
    return clone(session);
  }

  isSessionArmed(sessionId) {
    const session = this.sessions.get(sessionId);
    return Boolean(!this.emergency.active && session && session.armedUntil && this.now() < session.armedUntil);
  }

  emergencyStop(reason = 'manual') {
    for (const session of this.sessions.values()) session.armedUntil = null;
    this.emergency = { active: true, reason, updatedAt: iso(this.now()) };
    this._persist();
    return this.emergencyStopState();
  }

  clearEmergencyStop() {
    this.emergency = { active: false, reason: null, updatedAt: iso(this.now()) };
    this._persist();
    return this.emergencyStopState();
  }

  emergencyStopState() {
    return clone(this.emergency);
  }

  getSessionPolicy(sessionId) {
    if (!this.sessions.has(sessionId)) throw new Error('session not found');
    return this._getPolicy('session', sessionId);
  }

  getAutonomyPolicy() {
    return this._getPolicy('global', 'personal');
  }

  updateAutonomyPolicy(patch = {}) {
    return this._updatePolicy('global', 'personal', patch);
  }

  updateSessionPolicy(sessionId, patch = {}) {
    if (!this.sessions.has(sessionId)) throw new Error('session not found');
    return this._updatePolicy('session', sessionId, patch);
  }

  getAutomationPolicy(automationId) {
    if (!this.automations.has(automationId)) throw new Error('automation not found');
    return this._getPolicy('automation', automationId);
  }

  updateAutomationPolicy(automationId, patch = {}) {
    if (!this.automations.has(automationId)) throw new Error('automation not found');
    return this._updatePolicy('automation', automationId, patch);
  }

  listPolicies() {
    return sortByUpdatedAtDesc([...this.policies.values()].map(clone));
  }

  upsertAttentionItem({
    id: attentionId = null,
    source = 'system',
    severity = 'low',
    status = undefined,
    title = '',
    summary = '',
    relatedSessionId = null,
    relatedTaskId = null,
    dedupeKey = null,
    snoozedUntil = undefined,
  } = {}) {
    const timestamp = iso(this.now());
    const existing = attentionId
      ? this.attention.get(attentionId)
      : [...this.attention.values()].find(item => dedupeKey && item.dedupeKey === dedupeKey);
    const next = existing ? { ...existing } : {
      id: attentionId || id('attention'),
      createdAt: timestamp,
    };

    next.source = String(source || next.source || 'system');
    next.severity = ATTENTION_SEVERITIES.has(String(severity)) ? String(severity) : (existing?.severity || 'low');
    next.title = limitText(title || next.title || next.source, 120);
    next.summary = limitText(summary || next.summary || '', 400);
    next.relatedSessionId = relatedSessionId !== undefined ? (relatedSessionId ? String(relatedSessionId) : null) : (next.relatedSessionId || null);
    next.relatedTaskId = relatedTaskId !== undefined ? (relatedTaskId ? String(relatedTaskId) : null) : (next.relatedTaskId || null);
    next.dedupeKey = dedupeKey !== undefined ? (dedupeKey ? String(dedupeKey) : null) : (next.dedupeKey || null);
    next.snoozedUntil = snoozedUntil !== undefined ? normalizeIsoOrNull(snoozedUntil) : normalizeIsoOrNull(next.snoozedUntil);

    let nextStatus = status !== undefined ? String(status) : (next.status || 'open');
    if (existing && ['resolved', 'dismissed'].includes(existing.status) && status === undefined && !next.snoozedUntil) nextStatus = 'open';
    if (next.snoozedUntil && parseTimestamp(next.snoozedUntil) > this.now()) nextStatus = 'snoozed';
    if (nextStatus === 'snoozed' && (!next.snoozedUntil || parseTimestamp(next.snoozedUntil) <= this.now())) nextStatus = 'open';
    if (!ATTENTION_STATUSES.has(nextStatus)) throw new Error(`invalid attention status: ${nextStatus}`);
    next.status = nextStatus;
    next.updatedAt = timestamp;
    this.attention.set(next.id, next);
    this._persist();
    return clone(next);
  }

  getAttentionItem(attentionId) {
    return clone(this.attention.get(attentionId) || null);
  }

  listAttentionItems(filters = {}) {
    const values = [...this.attention.values()]
      .filter((item) => {
        if (filters.status && item.status !== filters.status) return false;
        if (filters.relatedTaskId && item.relatedTaskId !== filters.relatedTaskId) return false;
        if (filters.relatedSessionId && item.relatedSessionId !== filters.relatedSessionId) return false;
        if (filters.source && item.source !== filters.source) return false;
        return true;
      })
      .map(clone);
    return sortByUpdatedAtDesc(values);
  }

  updateAttentionItem(attentionId, patch = {}) {
    const item = this.attention.get(attentionId);
    if (!item) throw new Error('attention item not found');
    const next = { ...item };
    if (patch.title !== undefined) next.title = limitText(String(patch.title || ''), 120);
    if (patch.summary !== undefined) next.summary = limitText(String(patch.summary || ''), 400);
    if (patch.relatedSessionId !== undefined) next.relatedSessionId = patch.relatedSessionId ? String(patch.relatedSessionId) : null;
    if (patch.relatedTaskId !== undefined) next.relatedTaskId = patch.relatedTaskId ? String(patch.relatedTaskId) : null;
    if (patch.dedupeKey !== undefined) next.dedupeKey = patch.dedupeKey ? String(patch.dedupeKey) : null;
    if (patch.snoozedUntil !== undefined) next.snoozedUntil = normalizeIsoOrNull(patch.snoozedUntil);

    let status = patch.status !== undefined ? String(patch.status) : next.status;
    if (patch.read === true) status = 'read';
    if (patch.resolved === true) status = 'resolved';
    if (patch.dismissed === true || patch.ignored === true) status = 'dismissed';
    if (next.snoozedUntil && parseTimestamp(next.snoozedUntil) > this.now()) status = 'snoozed';
    if (status === 'snoozed' && (!next.snoozedUntil || parseTimestamp(next.snoozedUntil) <= this.now())) status = 'open';
    if (!ATTENTION_STATUSES.has(status)) throw new Error(`invalid attention status: ${status}`);
    next.status = status;
    next.updatedAt = iso(this.now());
    this.attention.set(next.id, next);
    this._persist();
    return clone(next);
  }

  getLatestAttentionForTask(taskId) {
    return this.listAttentionItems({ relatedTaskId: taskId })[0] || null;
  }

  invokeTool(sessionId, toolId, args = {}, options = {}) {
    return this._invokeRegisteredToolInvocation({
      origin: 'session',
      sessionId,
      toolId,
      args,
      taskId: options.taskId || null,
      expiresAt: options.expiresAt || null,
      confirmed: options.confirmed === true,
      onUpdate: options.onUpdate,
    });
  }

  invokeAutomationTool(automationId, toolId, args = {}, options = {}) {
    return this._invokeRegisteredToolInvocation({
      origin: 'automation',
      automationId,
      toolId,
      args,
      taskId: options.taskId || null,
      expiresAt: options.expiresAt || null,
      confirmed: options.confirmed === true,
      onUpdate: options.onUpdate,
    });
  }

  invokeRegisteredTool(toolId, args = {}, actor = 'automation', options = {}) {
    if (typeof actor === 'string' && actor.startsWith('automation:')) {
      return this.invokeAutomationTool(actor.slice('automation:'.length), toolId, args, options);
    }
    if (typeof actor === 'string' && actor.startsWith('session:')) {
      return this.invokeTool(actor.slice('session:'.length), toolId, args, options);
    }
    if (this.sessions.has(actor)) return this.invokeTool(actor, toolId, args, options);
    if (this.automations.has(actor)) return this.invokeAutomationTool(actor, toolId, args, options);
    return this._invokeRegisteredToolInvocation({
      origin: 'direct',
      sessionId: null,
      automationId: null,
      toolId,
      args,
      taskId: options.taskId || null,
      expiresAt: options.expiresAt || null,
      confirmed: options.confirmed === true,
      onUpdate: options.onUpdate,
    });
  }

  listActionRuns() {
    return sortByUpdatedAtDesc([...this.actionRuns.values()].map(clone));
  }

  getActionRun(actionRunId) {
    return clone(this.actionRuns.get(actionRunId) || null);
  }

  updateActionRun(actionRunId, patch = {}) {
    const run = this.actionRuns.get(actionRunId);
    if (!run) throw new Error('action run not found');
    const state = patch.state !== undefined ? String(patch.state) : run.state;
    if (!ACTION_RUN_STATES.has(state)) throw new Error(`invalid action run state: ${state}`);
    if (patch.approval !== undefined && !ACTION_RUN_APPROVALS.has(String(patch.approval))) throw new Error(`invalid action approval: ${patch.approval}`);
    const next = { ...run };
    next.state = state;
    if (patch.approval !== undefined) next.approval = String(patch.approval);
    if (patch.taskId !== undefined) next.taskId = patch.taskId ? String(patch.taskId) : null;
    if (patch.resultRef !== undefined) next.resultRef = patch.resultRef == null ? null : limitText(String(patch.resultRef), 240);
    if (patch.error !== undefined) next.error = patch.error == null ? null : limitText(String(patch.error), 240);
    if (patch.startedAt !== undefined) next.startedAt = normalizeIsoOrNull(patch.startedAt);
    if (patch.endedAt !== undefined) next.endedAt = normalizeIsoOrNull(patch.endedAt);
    if (!next.startedAt && next.state === 'running') next.startedAt = iso(this.now());
    if (!next.endedAt && TERMINAL_ACTION_RUN_STATES.has(next.state)) next.endedAt = iso(this.now());
    next.updatedAt = iso(this.now());
    this.actionRuns.set(next.id, next);
    this._persist();
    return clone(next);
  }

  auditLog() {
    return clone(this.audit);
  }

  _attentionForTask(task) {
    if (!['needs_confirmation', 'succeeded', 'failed', 'cancelled'].includes(task.state)) return null;
    const severity = task.state === 'failed' ? 'high' : (task.state === 'needs_confirmation' || task.state === 'cancelled' ? 'medium' : 'low');
    const titlePrefix = task.state === 'failed' ? '任务失败' : (task.state === 'cancelled' ? '任务取消' : (task.state === 'needs_confirmation' ? '任务待确认' : '任务完成'));
    const summary = task.error || task.logs.at(-1)?.text || task.detail || '';
    return this.upsertAttentionItem({
      source: 'task',
      severity,
      title: `${titlePrefix}：${task.title}`,
      summary,
      relatedTaskId: task.id,
      dedupeKey: `task:${task.id}:${task.state}`,
    });
  }

  createTask({ id: taskId = id('task'), source = 'conversation', title = '未命名任务', detail = '', metadata = {} } = {}) {
    const timestamp = iso(this.now());
    const task = { id: taskId, source, title, detail, metadata, state: 'pending', progress: 0, logs: [], error: null, retryCount: 0, artifactRefs: [], createdAt: timestamp, updatedAt: timestamp };
    this.tasks.set(task.id, task);
    this._persist();
    return clone(task);
  }

  getTask(taskId) { return clone(this.tasks.get(taskId) || null); }

  listTasks() { return [...this.tasks.values()].map(clone).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)); }

  updateTask(taskId, patch = {}) {
    const task = this.tasks.get(taskId);
    if (!task) throw new Error('task not found');
    if (patch.state !== undefined && !TASK_STATES.has(patch.state)) throw new Error(`invalid task state: ${patch.state}`);
    if (patch.state !== undefined) task.state = patch.state;
    if (patch.progress !== undefined) task.progress = Math.max(0, Math.min(100, Number(patch.progress) || 0));
    for (const key of ['detail', 'error', 'metadata', 'artifactRefs']) if (patch[key] !== undefined) task[key] = clone(patch[key]);
    if (patch.log) task.logs.push({ id: id('log'), text: String(patch.log), createdAt: iso(this.now()) });
    if (patch.retry === true) { task.retryCount += 1; task.state = 'pending'; task.error = null; }
    task.updatedAt = iso(this.now());
    this._persist();
    this._attentionForTask(task);
    return clone(task);
  }

  createAutomation({ id: automationId = id('automation'), name = '未命名场景', enabled = true, trigger = { type: 'manual' }, conditions = [], actions = [], cooldownMs = 0 } = {}) {
    const automation = { id: automationId, name, enabled, trigger, conditions, actions, cooldownMs, lastRunAt: null, createdAt: iso(this.now()), updatedAt: iso(this.now()) };
    this.automations.set(automation.id, automation);
    this._persist();
    return clone(automation);
  }

  listAutomations() { return [...this.automations.values()].map(clone); }

  getAutomation(automationId) { return clone(this.automations.get(automationId) || null); }

  updateAutomation(automationId, patch = {}) {
    const automation = this.automations.get(automationId);
    if (!automation) throw new Error('automation not found');
    for (const key of ['name', 'enabled', 'trigger', 'conditions', 'actions', 'cooldownMs']) if (patch[key] !== undefined) automation[key] = clone(patch[key]);
    automation.updatedAt = iso(this.now());
    this._persist();
    return clone(automation);
  }

  _automationMatches(automation, deviceState = {}) {
    const trigger = automation.trigger || {};
    if (trigger.type === 'manual') return false;
    if (trigger.type === 'schedule') {
      const intervalMs = Math.max(1_000, Number(trigger.intervalMs || trigger.everyMs || 0));
      return intervalMs > 0 && (!automation.lastRunAt || this.now() - Date.parse(automation.lastRunAt) >= intervalMs);
    }
    if (trigger.type !== 'device') return false;
    if (!compare(deviceState[trigger.field], trigger.op || 'eq', trigger.value)) return false;
    return (automation.conditions || []).every(condition => compare(deviceState[condition.field], condition.op || 'eq', condition.value));
  }

  runAutomation(automationId, context = {}) {
    const automation = this.automations.get(automationId);
    if (!automation) throw new Error('automation not found');
    if (!automation.enabled) return null;
    const timestamp = this.now();
    if (automation.lastRunAt && timestamp - Date.parse(automation.lastRunAt) < Math.max(0, automation.cooldownMs || 0)) return null;
    const run = { id: id('run'), automationId, status: 'queued', context: clone(context), createdAt: iso(timestamp), completedAt: null, error: null };
    automation.lastRunAt = run.createdAt;
    this.runs.push(run);
    this._persist();
    return clone(run);
  }

  evaluateAutomations(deviceState = {}) {
    const runs = [];
    for (const automation of this.automations.values()) if (automation.enabled && this._automationMatches(automation, deviceState)) {
      const run = this.runAutomation(automation.id, { deviceState });
      if (run) runs.push(run);
    }
    return runs;
  }

  automationRuns() { return clone(this.runs); }

  completeAutomationRun(runId, status = 'succeeded', error = null) {
    const run = this.runs.find(item => item.id === runId);
    if (!run) throw new Error('automation run not found');
    run.status = status;
    run.error = error ? String(error) : null;
    run.completedAt = iso(this.now());
    this._persist();
    return clone(run);
  }
}

module.exports = { WorkspaceStore, createEventEnvelope, TASK_STATES };
