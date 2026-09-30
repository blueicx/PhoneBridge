'use strict';

const crypto = require('node:crypto');

const MAX_CONTEXT_LENGTH = 1000;
const MAX_PROVIDER_OUTPUT_LENGTH = 8000;
const MAX_STEPS = 8;
const MAX_DRAFT_AUDITS = 100;
const GOAL_FIELDS = new Set(['title', 'description']);

class GoalBoardError extends Error {
  constructor(message, { statusCode = 400, code = 'invalid_goal' } = {}) {
    super(message);
    this.name = 'GoalBoardError';
    this.statusCode = statusCode;
    this.code = code;
  }
}

function requireObject(value, message) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new GoalBoardError(message);
  return value;
}

function requireAllowedFields(value, allowed, label) {
  for (const key of Object.keys(value)) if (!allowed.has(key)) throw new GoalBoardError(`unsupported ${label} field: ${key}`);
}

function normalizeSteps(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > MAX_STEPS) throw new GoalBoardError(`goal steps must contain 1-${MAX_STEPS} items`);
  return value.map(step => {
    requireObject(step, 'goal step must be an object');
    requireAllowedFields(step, new Set(['title', 'description']), 'step');
    const title = String(step.title || '').trim();
    const description = String(step.description || '').trim();
    if (!title || [...title].length > 120) throw new GoalBoardError('goal step title must be 1-120 characters');
    if ([...description].length > 500) throw new GoalBoardError('goal step description is too long');
    return { title, description };
  });
}

function parseDraftSteps(reply) {
  let source = String(reply || '').trim();
  if (source.length > MAX_PROVIDER_OUTPUT_LENGTH) throw new Error('provider output is too long');
  const fenced = source.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  if (fenced) source = fenced[1];
  const value = JSON.parse(source);
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => key !== 'steps')) {
    throw new Error('provider output schema is invalid');
  }
  return normalizeSteps(value.steps);
}

function createLocalSteps(goal) {
  const goalTitle = [...String(goal.title || '').trim()].slice(0, 72).join('') || '这个目标';
  return [
    { title: `拆解：${goalTitle}`, description: '把目标缩小为一个可在短时间内完成的具体动作。' },
    { title: '执行并观察结果', description: '完成第一步后，记录可观察的结果与实际阻碍。' },
    { title: '回顾并确定下一步', description: '保留有效做法，再选择一项合适的后续行动。' },
  ];
}

class GoalBoardService {
  constructor({ workspaceStore, providerManager = null, now = () => Date.now(), isTaskActive = () => false, onTasksDeleted = () => {}, recordProviderAudit = () => {} } = {}) {
    if (!workspaceStore) throw new TypeError('workspaceStore is required');
    this.workspaceStore = workspaceStore;
    this.providerManager = providerManager;
    this.now = now;
    this.isTaskActive = isTaskActive;
    this.onTasksDeleted = onTasksDeleted;
    this.recordProviderAudit = recordProviderAudit;
    this.draftAudits = [];
  }

  createGoal(input = {}) {
    requireObject(input, 'goal body must be an object');
    requireAllowedFields(input, GOAL_FIELDS, 'goal');
    const title = String(input.title || '').trim();
    const description = String(input.description || '').trim();
    if (!title || [...title].length > 120) throw new GoalBoardError('goal title must be 1-120 characters');
    if ([...description].length > 2000) throw new GoalBoardError('goal description is too long');
    return this.workspaceStore.createGoal({ title, description });
  }

  getGoal(goalId) { return this.workspaceStore.getGoal(goalId); }

  listGoals() { return this.workspaceStore.listGoals(); }

  updateGoal(goalId, patch = {}) {
    requireObject(patch, 'goal patch must be an object');
    requireAllowedFields(patch, GOAL_FIELDS, 'goal');
    if (!Object.keys(patch).length) throw new GoalBoardError('goal patch is empty');
    if (patch.title !== undefined && (!String(patch.title || '').trim() || [...String(patch.title || '').trim()].length > 120)) {
      throw new GoalBoardError('goal title must be 1-120 characters');
    }
    if (patch.description !== undefined && [...String(patch.description || '').trim()].length > 2000) {
      throw new GoalBoardError('goal description is too long');
    }
    return this.workspaceStore.updateGoal(goalId, patch);
  }

  acceptDraft(goalId, payload = {}) {
    requireObject(payload, 'goal acceptance body must be an object');
    requireAllowedFields(payload, new Set(['eventId', 'steps']), 'goal acceptance');
    const steps = normalizeSteps(payload.steps);
    return this.workspaceStore.acceptGoalSteps(goalId, { eventId: payload.eventId, steps });
  }

  _goalTaskIds(goal) {
    return new Set(this.workspaceStore.listGoalTaskIds(goal?.id));
  }

  _ensureGoalTasksIdle(goal) {
    for (const taskId of this._goalTaskIds(goal)) {
      const task = this.workspaceStore.getTask(taskId);
      if (task?.state === 'running' || this.isTaskActive(taskId)) {
        throw new GoalBoardError('finish or cancel active goal tasks before deleting the goal', {
          statusCode: 409,
          code: 'goal_task_active',
        });
      }
    }
  }

  assertGoalIdle(goalId) {
    const goal = this.workspaceStore.getGoal(goalId);
    if (goal) this._ensureGoalTasksIdle(goal);
    return goal;
  }

  validateClear() {
    for (const goal of this.workspaceStore.listGoals()) this._ensureGoalTasksIdle(goal);
  }

  deleteGoal(goalId, { beforeDelete = () => {} } = {}) {
    const goal = this.workspaceStore.getGoal(goalId);
    if (!goal) return { deleted: false, goalId: String(goalId || ''), taskIds: [] };
    this._ensureGoalTasksIdle(goal);
    beforeDelete();
    const result = this.workspaceStore.deleteGoal(goal.id);
    if (result.deleted) this.onTasksDeleted(result.taskIds);
    return result;
  }

  clearGoals() {
    const goals = this.workspaceStore.listGoals();
    this.validateClear();
    const result = this.workspaceStore.clearGoals();
    this.onTasksDeleted(result.taskIds);
    return result;
  }

  countGoals() { return this.workspaceStore.countGoals(); }

  exportGoals() { return this.workspaceStore.exportGoals(); }

  listDraftAudits() { return this.draftAudits.map(item => ({ ...item })); }

  _recordDraftAudit(entry) {
    const safe = {
      providerId: String(entry.providerId || 'local').slice(0, 64),
      elapsedMs: Math.max(0, Math.min(120_000, Math.round(Number(entry.elapsedMs) || 0))),
      fallbackReason: entry.fallbackReason || null,
      resultStatus: entry.resultStatus === 'failed' ? 'failed' : 'ready',
    };
    this.draftAudits.push(safe);
    if (this.draftAudits.length > MAX_DRAFT_AUDITS) this.draftAudits.splice(0, this.draftAudits.length - MAX_DRAFT_AUDITS);
    try { this.recordProviderAudit({ ...safe }); } catch (_) {}
    return safe;
  }

  async draftGoal(goalId, input = {}) {
    requireObject(input, 'goal draft body must be an object');
    requireAllowedFields(input, new Set(['context', 'signal']), 'goal draft');
    const goal = this.workspaceStore.getGoal(goalId);
    if (!goal) throw new GoalBoardError('goal not found', { statusCode: 404, code: 'goal_not_found' });
    const context = String(input.context || '').trim();
    if ([...context].length > MAX_CONTEXT_LENGTH) throw new GoalBoardError(`goal draft context must be at most ${MAX_CONTEXT_LENGTH} characters`);

    const manager = this.providerManager;
    const providerId = String(manager?.activeProviderId || manager?.getSettings?.().activeProviderId || 'local').slice(0, 64);
    const startedAt = Number(this.now()) || Date.now();
    let steps = null;
    let fallbackReason = null;
    let fallbackProvider = null;
    const signal = input.signal;

    if (!manager || providerId === 'local') {
      steps = createLocalSteps(goal);
    } else {
      const prompt = [
        '根据用户目标生成可执行的阶段建议。只返回 JSON 对象，格式为 {"steps":[{"title":"...","description":"..."}]}。',
        `steps 数量为 1-${MAX_STEPS}；标题不超过 120 字，说明不超过 500 字；不得提出自动工具调用。`,
        `目标：${goal.title}`,
        `目标说明：${goal.description}`,
        `用户补充：${context}`,
      ].join('\n');
      try {
        const result = await manager.chat({
          prompt,
          providerId,
          requestId: `goal-draft-${crypto.randomUUID()}`,
          signal: signal || null,
          options: { maxOutputTokens: 900, task: 'goal_draft' },
        });
        if (result.degraded || result.fallbackProvider) {
          fallbackReason = 'provider_unavailable';
          fallbackProvider = 'local';
          steps = createLocalSteps(goal);
        } else {
          try { steps = parseDraftSteps(result.reply); }
          catch (_) {
            fallbackReason = 'provider_output_invalid';
            fallbackProvider = 'local';
            steps = createLocalSteps(goal);
          }
        }
      } catch (error) {
        if (signal?.aborted || /cancel/i.test(String(error.message || ''))) {
          this._recordDraftAudit({ providerId, elapsedMs: this.now() - startedAt, fallbackReason: 'request_cancelled', resultStatus: 'failed' });
          throw new GoalBoardError('goal draft was cancelled', { statusCode: 409, code: 'goal_draft_cancelled' });
        }
        fallbackReason = 'provider_unavailable';
        fallbackProvider = 'local';
        steps = createLocalSteps(goal);
      }
    }

    const providerAudit = this._recordDraftAudit({
      providerId,
      elapsedMs: this.now() - startedAt,
      fallbackReason,
      resultStatus: 'ready',
    });
    return {
      goalId: goal.id,
      steps: normalizeSteps(steps),
      providerId,
      fallbackProvider,
      providerAudit,
    };
  }
}

module.exports = { GoalBoardService, GoalBoardError, normalizeSteps, parseDraftSteps, createLocalSteps };
