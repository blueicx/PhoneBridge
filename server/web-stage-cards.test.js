'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { selectStageCards } = require('./web-stage-cards');

test('stage cards prioritize explicit approvals and cap visible work at two cards', () => {
  const cards = selectStageCards({
    tasks: [
      { id: 'task-running', title: '巡检节点', state: 'running', progress: 42 },
      { id: 'task-approve', title: '整理文件', state: 'needs_confirmation', progress: 0 },
      { id: 'task-pending', title: '下一项', state: 'pending', progress: 0 },
    ],
    approvals: [
      { id: 'approval-1', taskId: 'task-approve', toolId: 'files.organize', state: 'needs_confirmation', args: { sensitive: 'must-not-escape' } },
    ],
    attention: [{ id: 'attention-1', title: '稍后提醒', status: 'open' }],
  });

  assert.equal(cards.length, 2);
  assert.equal(cards[0].kind, 'approval');
  assert.equal(cards[0].approvalId, 'approval-1');
  assert.equal(cards[1].taskId, 'task-running');
  assert.equal(cards[1].action, 'pause');
  assert.doesNotMatch(JSON.stringify(cards), /must-not-escape|sensitive/);
});

test('stage cards fall back to current task, attention, then recent result', () => {
  const cards = selectStageCards({
    tasks: [
      { id: 'done', title: '昨日日志', state: 'succeeded', updatedAt: 30 },
      { id: 'current', title: '当前任务', state: 'paused', progress: 20, updatedAt: 20 },
    ],
    attention: [{ id: 'attention', title: '需要查看', status: 'needs_confirmation', relatedTaskId: 'current' }],
    approvals: [],
  });
  assert.deepEqual(cards.map(card => card.kind), ['task', 'attention']);
  assert.equal(cards[0].action, 'continue');

  const resultOnly = selectStageCards({ tasks: [{ id: 'done', title: '最近结果', state: 'succeeded' }] });
  assert.equal(resultOnly[0].kind, 'result');
});

test('approved receipts are shown as execute actions without exposing arbitrary payload', () => {
  const cards = selectStageCards({
    approvals: [{ id: 'approval-2', taskId: 't1', toolId: 'device.status', state: 'approved', args: { token: 'hidden' } }],
    tasks: [{ id: 't1', title: '查看设备', state: 'needs_confirmation' }],
  });
  assert.equal(cards[0].action, 'invoke');
  assert.equal(cards[0].actionLabel, '继续执行');
  assert.doesNotMatch(JSON.stringify(cards), /hidden|token/);
});

test('browser card selector is self-contained and returns the same two-card projection', () => {
  const vm = require('node:vm');
  const { browserStageCardsScript } = require('./web-stage-cards');
  const context = { window: {} };
  vm.runInNewContext(browserStageCardsScript(), context);
  const cards = context.window.selectStageCards({ tasks: [{ id: 't1', title: '任务', state: 'running', progress: 10 }] });
  assert.equal(cards[0].id, 'task:t1');
  assert.equal(cards[0].action, 'pause');
});

test('active routine progress appears beside the task without exposing private reflection text', () => {
  const cards = selectStageCards({
    tasks: [{ id: 'task-1', title: '当前任务', state: 'running', progress: 40 }],
    activities: [{ routineId: 'focus-timer', title: '专注计时', status: 'active', elapsedSeconds: 754, reflection: 'must-not-escape' }],
  });
  assert.deepEqual(cards.map(card => card.kind), ['task', 'activity']);
  assert.equal(cards[1].routineId, 'focus-timer');
  assert.match(cards[1].detail, /12 分钟/);
  assert.doesNotMatch(JSON.stringify(cards), /must-not-escape|reflection/);
});
