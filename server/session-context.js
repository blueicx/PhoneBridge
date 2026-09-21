'use strict';

function prepareConversation({ history = [], memories = [], maxMessages = 16, maxChars = 12000 } = {}) {
  const normalized = (Array.isArray(history) ? history : [])
    .filter(item => item && (item.role === 'user' || item.role === 'assistant' || item.role === 'system'))
    .map(item => ({ role: String(item.role), text: String(item.text || item.content || '').slice(0, 4000) }));
  const recent = normalized.slice(-Math.max(1, Number(maxMessages) || 16));
  let total = recent.reduce((sum, item) => sum + item.text.length, 0);
  let summary = '';
  if (normalized.length > recent.length || total > maxChars) {
    const omitted = normalized.slice(0, Math.max(0, normalized.length - recent.length));
    summary = omitted.map(item => `${item.role}: ${item.text.slice(0, 180)}`).join('\n').slice(0, 3000);
  }
  const memoryText = (Array.isArray(memories) ? memories : []).map(item => String(item || '').trim()).filter(Boolean).slice(0, 20);
  const messages = [];
  if (summary) messages.push({ role: 'system', text: `早期会话摘要：\n${summary}` });
  messages.push(...recent);
  return { messages, memories: memoryText, summary, estimatedChars: total + summary.length + memoryText.join('').length };
}

module.exports = { prepareConversation };
