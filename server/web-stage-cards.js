'use strict';

function selectStageCards(workspace = {}) {
  const activeStates = new Set(['needs_confirmation', 'running', 'paused', 'pending', 'queued']);
  const resultStates = new Set(['succeeded', 'failed', 'error', 'cancelled', 'archived']);
  const tasks = Array.isArray(workspace?.tasks) ? workspace.tasks : [];
  const attention = Array.isArray(workspace?.attention) ? workspace.attention : [];
  const approvals = Array.isArray(workspace?.approvals) ? workspace.approvals : [];
  const activities = Array.isArray(workspace?.activities) ? workspace.activities : [];
  const byTask = new Map(tasks.filter(task => task?.id).map(task => [String(task.id), task]));
  const cards = [];
  const add = card => {
    if (cards.length >= 2 || cards.some(item => item.id === card.id)) return;
    cards.push(card);
  };
  const time = value => Number(value) || Date.parse(value || '') || 0;
  const safeText = (value, limit, fallback = '') => String(value || fallback).trim().slice(0, limit);

  const pendingApproval = approvals
    .filter(item => item?.id && ['needs_confirmation', 'approved'].includes(String(item.state || '').toLowerCase()))
    .sort((a, b) => (String(b.state).toLowerCase() === 'needs_confirmation' ? 1 : 0) - (String(a.state).toLowerCase() === 'needs_confirmation' ? 1 : 0) || time(b.createdAt) - time(a.createdAt));
  for (const item of pendingApproval) {
    if (cards.length >= 1) break;
    const task = byTask.get(String(item.taskId || ''));
    const approved = String(item.state).toLowerCase() === 'approved';
    add({
      id: `approval:${String(item.id)}`,
      kind: 'approval',
      title: safeText(task?.title, 96, `需要确认 · ${item.toolId || '受限操作'}`),
      detail: approved ? '已确认，等待执行' : `待确认 · ${safeText(item.toolId, 96, '受限操作')}`,
      taskId: task?.id ? String(task.id) : '',
      approvalId: String(item.id),
      action: approved ? 'invoke' : 'approve',
      actionLabel: approved ? '继续执行' : '查看确认',
    });
  }

  const priority = { needs_confirmation: 5, running: 4, paused: 3, pending: 2, queued: 2 };
  const activeTask = tasks
    .filter(task => task?.id && activeStates.has(String(task.state || task.status || '').toLowerCase()))
    .sort((a, b) => (priority[String(b.state || b.status).toLowerCase()] || 0) - (priority[String(a.state || a.status).toLowerCase()] || 0) || time(b.updatedAt) - time(a.updatedAt))
    .find(task => !pendingApproval.some(item => String(item.taskId || '') === String(task.id)));
  if (activeTask && cards.length < 2) {
    const state = String(activeTask.state || activeTask.status || 'pending').toLowerCase();
    const stateLabel = ({ needs_confirmation: '待确认', running: '进行中', paused: '已暂停', pending: '待处理', queued: '排队中' })[state] || state;
    const action = ({ running: 'pause', paused: 'continue', pending: 'start', queued: 'start' })[state] || 'detail';
    const actionLabel = ({ pause: '暂停', continue: '继续', start: '开始' })[action] || '查看';
    add({
      id: `task:${String(activeTask.id)}`,
      kind: 'task',
      title: safeText(activeTask.title, 96, '当前任务'),
      detail: `${stateLabel} · ${Math.max(0, Math.min(100, Number(activeTask.progress) || 0))}%`,
      taskId: String(activeTask.id),
      approvalId: '',
      action,
      actionLabel,
    });
  }

  const activeActivity = activities
    .filter(item => item?.routineId && ['active', 'paused', 'pending'].includes(String(item.status || '').toLowerCase()))
    .sort((a, b) => time(b.updatedAt) - time(a.updatedAt))[0];
  if (activeActivity && cards.length < 2) {
    const status = String(activeActivity.status || '').toLowerCase();
    const statusLabel = ({ active: '进行中', paused: '已暂停', pending: '待同步' })[status];
    const minutes = Math.floor(Math.max(0, Number(activeActivity.elapsedSeconds) || 0) / 60);
    add({
      id: `activity:${String(activeActivity.routineId)}`,
      kind: 'activity',
      title: safeText(activeActivity.title, 96, '日常活动'),
      detail: `${statusLabel} · 已记录 ${minutes} 分钟`,
      taskId: '',
      routineId: String(activeActivity.routineId),
      approvalId: '',
      action: 'detail',
      actionLabel: '查看',
    });
  }

  const nextAttention = attention
    .filter(item => item?.id && ['open', 'pending', 'needs_confirmation'].includes(String(item.status || 'open').toLowerCase()))
    .sort((a, b) => time(b.updatedAt || b.createdAt) - time(a.updatedAt || a.createdAt))[0];
  if (nextAttention && cards.length < 2) {
    add({
      id: `attention:${String(nextAttention.id)}`,
      kind: 'attention',
      title: safeText(nextAttention.title, 96, '需要查看'),
      detail: safeText(nextAttention.summary, 140, '打开查看相关任务'),
      taskId: String(nextAttention.relatedTaskId || ''),
      approvalId: '',
      action: 'detail',
      actionLabel: '查看',
    });
  }

  if (cards.length < 2) {
    const latestResult = tasks
      .filter(task => task?.id && resultStates.has(String(task.state || task.status || '').toLowerCase()))
      .sort((a, b) => time(b.updatedAt) - time(a.updatedAt))[0];
    if (latestResult) add({
      id: `result:${String(latestResult.id)}`,
      kind: 'result',
      title: safeText(latestResult.title, 96, '最近结果'),
      detail: safeText(latestResult.result || latestResult.error || latestResult.detail, 140, String(latestResult.state || latestResult.status || '已完成')),
      taskId: String(latestResult.id),
      approvalId: '',
      action: 'detail',
      actionLabel: '查看',
    });
  }
  return cards;
}

function browserStageCardsScript() {
  return `window.selectStageCards=${selectStageCards.toString()};`;
}

module.exports = { selectStageCards, browserStageCardsScript };
