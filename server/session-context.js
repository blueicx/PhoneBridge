'use strict';

function prepareConversation({ history = [], memories = [], maxMessages = 16, maxChars = 12000, remember = true } = {}) {
  const normalized = (Array.isArray(history) ? history : [])
    .filter(item => item && (item.role === 'user' || item.role === 'assistant' || item.role === 'system'))
    .map(item => ({ role: String(item.role), text: String(item.text || item.content || '').slice(0, 4000) }));
  const messageLimit = Math.max(1, Math.min(100, Number(maxMessages) || 16));
  const charLimit = Math.max(256, Math.min(50000, Number(maxChars) || 12000));
  const requested = normalized.slice(-messageLimit);
  const requestedChars = requested.reduce((sum, item) => sum + item.text.length, 0);
  const needsCompression = normalized.length > requested.length || requestedChars > charLimit;
  const mayRemember = remember === true;
  const summaryBudget = needsCompression && mayRemember
    ? Math.min(charLimit - 1, Math.max(64, Math.floor(charLimit * 0.35)))
    : 0;
  const recentBudget = Math.max(1, charLimit - summaryBudget);
  const recent = [];
  let recentChars = 0;
  for (const item of requested.slice().reverse()) {
    const remaining = recentBudget - recentChars;
    if (remaining <= 0) break;
    if (item.text.length <= remaining) {
      recent.unshift(item);
      recentChars += item.text.length;
      continue;
    }
    if (!recent.length) {
      recent.unshift({ ...item, text: item.text.slice(-remaining) });
      recentChars += remaining;
    }
    break;
  }

  const recentItems = new Set(recent);
  const omitted = normalized.filter(item => !recentItems.has(item));
  const summaryPrefix = '早期会话摘要：\n';
  const summary = summaryBudget && omitted.length
    ? omitted.map(item => `${item.role}: ${item.text.slice(0, 180)}`).join('\n').slice(0, Math.max(0, summaryBudget - summaryPrefix.length))
    : '';
  const memoryEntries = mayRemember
    ? (Array.isArray(memories) ? memories : []).map(item => {
      if (typeof item === 'string') return { text: item.trim(), source: 'explicit' };
      if (!item || typeof item !== 'object') return null;
      const text = String(item.text || '').trim();
      if (!text) return null;
      return {
        ...(item.id ? { id: String(item.id) } : {}),
        text,
        source: String(item.source || 'user'),
        ...(item.updatedAt ? { updatedAt: String(item.updatedAt) } : {}),
        ...(item.lastUsedAt ? { lastUsedAt: String(item.lastUsedAt) } : {}),
        ...(item.lastUsedReason ? { lastUsedReason: String(item.lastUsedReason) } : {})
      };
    }).filter(Boolean).slice(0, 20)
    : [];
  const memoryText = memoryEntries.map(item => item.text);
  const messages = [];
  if (summary) messages.push({ role: 'system', text: `${summaryPrefix}${summary}` });
  messages.push(...recent);
  const memoryUsage = memoryEntries
    .filter(item => item.id)
    .map(item => ({ id: item.id, source: item.source, reason: 'conversation_recall' }));
  return {
    messages,
    memories: memoryText,
    memoryEntries,
    memoryUsage,
    summary,
    compressedMessageCount: omitted.length,
    estimatedChars: messages.reduce((sum, item) => sum + item.text.length, 0) + memoryText.join('').length
  };
}

function summarizeConversationResume(history = []) {
  const normalized = (Array.isArray(history) ? history : [])
    .filter(item => item && (item.role === 'user' || item.role === 'assistant'))
    .map(item => ({ role: item.role, text: String(item.text || item.content || '').replace(/\s+/g, ' ').trim(), time: item.time || item.updatedAt || null }))
    .filter(item => item.text);
  const user = normalized.slice().reverse().find(item => item.role === 'user');
  const assistant = normalized.slice().reverse().find(item => item.role === 'assistant');
  if (!user && !assistant) return null;
  return {
    lastUserMessage: user?.text.slice(0, 180) || '',
    lastAssistantMessage: assistant?.text.slice(0, 240) || '',
    updatedAt: normalized.at(-1)?.time || null
  };
}

module.exports = { prepareConversation, summarizeConversationResume };
