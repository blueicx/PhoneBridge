'use strict';

class StageChatController {
  constructor({ getSocket, createId = () => crypto.randomUUID(), onChange = () => {} } = {}) {
    this.getSocket = getSocket || (() => null);
    this.createId = createId;
    this.onChange = onChange;
    this.state = {
      messages: [], status: 'idle', activeRequestId: '', lastFailedText: '', failedMessages: [], pendingText: '',
      lastCompletedRequestId: '', streamedText: '', error: '', resume: null,
    };
    this.pendingUserMessageId = '';
    this.pendingFailureId = '';
    this.pendingHistoryRequestId = '';
    this.hydratedHistoryKeys = new Set();
  }

  publish(patch = {}) {
    Object.assign(this.state, patch);
    this.state.messages = this.state.messages.slice(-80);
    this.onChange({ ...this.state, messages: this.state.messages.map(item => ({ ...item })) });
  }

  send(text, appendUser = true, retryMessageId = '', retryOfRequestId = '') {
    const clean = String(text || '').trim().slice(0, 2000);
    if (!clean || this.state.activeRequestId) return false;
    const socket = this.getSocket();
    if (!socket || socket.readyState !== 1) {
      const messageId = retryMessageId || `offline:${String(this.createId())}`;
      const messages = appendUser
        ? [...this.state.messages, { id: messageId, role: 'user', text: clean, delivery: 'failed', createdAt: Date.now() }]
        : this.state.messages.map(item => item.id === retryMessageId ? { ...item, delivery: 'failed' } : item);
      const failedMessages = [...this.state.failedMessages.filter(item => item.id !== messageId), { id: messageId, text: clean, requestId: retryOfRequestId }];
      this.publish({ messages, failedMessages, lastFailedText: failedMessages.at(-1)?.text || '', status: 'offline', error: '节点离线，消息尚未发送。恢复连接后可重试。' });
      return false;
    }
    const requestId = `web_chat_${String(this.createId()).replace(/[^A-Za-z0-9_.:-]/g, '').slice(0, 72)}`;
    const messageId = retryMessageId || `${requestId}:user`;
    let messages = appendUser
      ? [...this.state.messages, { id: messageId, requestId, historyRequestId: requestId, role: 'user', text: clean, delivery: 'sending', createdAt: Date.now() }]
      : this.state.messages.map(item => item.id === retryMessageId ? {
          ...item, requestId: item.requestId || requestId,
          historyRequestId: retryOfRequestId || item.historyRequestId || item.requestId || requestId,
          delivery: 'sending',
        } : item);
    if (!appendUser && !messages.some(item => item.id === retryMessageId)) {
      messages = [...messages, { id: messageId, requestId, historyRequestId: retryOfRequestId || requestId, role: 'user', text: clean, delivery: 'sending', createdAt: Date.now() }];
    }
    try {
      const message = { type: 'chat', requestId, source: 'web-stage', text: clean, remember: true };
      if (retryOfRequestId) message.retryOfRequestId = retryOfRequestId;
      socket.send(JSON.stringify(message));
      const failedMessages = retryMessageId ? this.state.failedMessages.filter(item => item.id !== retryMessageId) : this.state.failedMessages;
      this.pendingUserMessageId = messageId;
      this.pendingFailureId = retryMessageId || messageId;
      this.pendingHistoryRequestId = retryOfRequestId || requestId;
      this.publish({ messages, failedMessages, lastFailedText: failedMessages.at(-1)?.text || '', status: 'generating', activeRequestId: requestId, pendingText: clean, error: '', streamedText: '' });
      return true;
    } catch (_) {
      const failedMessages = [...this.state.failedMessages.filter(item => item.id !== messageId), { id: messageId, text: clean, requestId: retryOfRequestId || requestId }];
      if (appendUser) messages = messages.map(item => item.id === messageId ? { ...item, delivery: 'failed' } : item);
      else messages = messages.map(item => item.id === messageId ? { ...item, delivery: 'failed' } : item);
      this.publish({ messages, failedMessages, status: 'offline', activeRequestId: '', pendingText: '', lastFailedText: failedMessages.at(-1)?.text || '', error: '消息未能发送；请检查节点连接后重试。' });
      return false;
    }
  }

  stop() {
    const requestId = this.state.activeRequestId;
    if (!requestId) return false;
    try {
      this.getSocket()?.send(JSON.stringify({ type: 'chat_cancel', requestId }));
      this.publish({ status: 'stopping' });
      return true;
    } catch (_) {
      this.connectionLost();
      return false;
    }
  }

  retry() {
    const failed = this.state.failedMessages.at(-1);
    if (!failed?.text) return false;
    return this.send(failed.text, false, failed.id, failed.historyRequestId || failed.requestId || '');
  }

  connectionLost() {
    if (!this.state.activeRequestId) return;
    this.markCurrentFailed('offline', '连接中断；本轮未收到完整回复，可重试。');
  }

  markCurrentFailed(status, error) {
    const text = this.state.pendingText;
    const id = this.pendingFailureId || this.pendingUserMessageId || `${this.state.activeRequestId}:user`;
    let failedMessages = this.state.failedMessages;
    let messages = this.state.messages;
    if (text) {
      failedMessages = [...failedMessages.filter(item => item.id !== id), { id, text, requestId: this.pendingHistoryRequestId || this.state.activeRequestId, historyRequestId: this.pendingHistoryRequestId || this.state.activeRequestId }];
      messages = messages.map(item => item.id === this.pendingUserMessageId ? { ...item, delivery: 'failed' } : item);
    }
    this.publish({ messages, failedMessages, status, activeRequestId: '', lastFailedText: failedMessages.at(-1)?.text || '', error, pendingText: '' });
  }

  handle(payload = {}) {
    const requestId = String(payload.requestId || payload.id || '');
    if (!requestId) return false;
    const active = requestId === this.state.activeRequestId;
    const completed = requestId === this.state.lastCompletedRequestId;
    if (!active && !completed) return false;

    if (payload.type === 'chat_delta') {
      if (!active) return false;
      const finalDelta = String(payload.text || '');
      const streamedText = payload.terminal ? (finalDelta || this.state.streamedText) : this.state.streamedText + finalDelta;
      const messages = this.state.messages.slice();
      const assistant = { id: `${requestId}:assistant`, requestId, historyRequestId: this.pendingHistoryRequestId || requestId, role: 'assistant', text: streamedText, streaming: !payload.terminal, createdAt: Date.now() };
      const assistantIndex = messages.findIndex(item => item.id === assistant.id);
      if (assistantIndex >= 0) messages[assistantIndex] = assistant;
      else {
        const userIndex = messages.findIndex(item => item.role === 'user' && (item.historyRequestId || item.requestId) === assistant.historyRequestId);
        messages.splice(userIndex >= 0 ? userIndex + 1 : messages.length, 0, assistant);
      }
      const completedMessages = payload.terminal
        ? messages.map(item => item.id === this.pendingUserMessageId ? { ...item, delivery: 'sent' } : item)
        : messages;
      this.publish(payload.terminal
        ? { messages: completedMessages, streamedText, status: 'complete', activeRequestId: '', pendingText: '', lastFailedText: this.state.failedMessages.at(-1)?.text || '', error: '', lastCompletedRequestId: requestId }
        : { messages, streamedText, status: 'generating' });
      if (payload.terminal) this.pendingFailureId = '';
      return true;
    }
    if (payload.type === 'chat') {
      const text = String(payload.text || '');
      if (!text) return false;
      const messages = this.state.messages.slice();
      const historyRequestId = this.pendingHistoryRequestId || requestId;
      const assistant = { id: `${requestId}:assistant`, requestId, historyRequestId, role: 'assistant', text, streaming: false, createdAt: Date.now() };
      const assistantIndex = messages.findIndex(item => item.id === assistant.id);
      if (assistantIndex >= 0) messages[assistantIndex] = assistant;
      else {
        const userIndex = messages.findIndex(item => item.role === 'user' && (item.historyRequestId || item.requestId) === historyRequestId);
        messages.splice(userIndex >= 0 ? userIndex + 1 : messages.length, 0, assistant);
      }
      if (!active) {
        this.publish({ messages });
        return true;
      }
      const completedMessages = messages.map(item => item.id === this.pendingUserMessageId ? { ...item, delivery: 'sent' } : item);
      this.publish({ messages: completedMessages, streamedText: text, status: 'complete', activeRequestId: '', pendingText: '', lastFailedText: this.state.failedMessages.at(-1)?.text || '', error: '', lastCompletedRequestId: requestId });
      this.pendingFailureId = '';
      return true;
    }
    if (payload.type === 'chat_error') {
      this.markCurrentFailed('error', String(payload.error || '回复未完成；可以重试。'));
      return true;
    }
    if (payload.type === 'chat_cancelled') {
      this.markCurrentFailed('stopped', '本轮已停止。');
      return true;
    }
    return false;
  }

  hydrate(history = [], resume = undefined) {
    const normalizedResume = resume && typeof resume === 'object'
      ? {
          lastUserMessage: String(resume.lastUserMessage || '').slice(0, 180),
          lastAssistantMessage: String(resume.lastAssistantMessage || '').slice(0, 240),
          updatedAt: resume.updatedAt || null,
        }
      : undefined;
    const resumeChanged = normalizedResume !== undefined && JSON.stringify(normalizedResume) !== JSON.stringify(this.state.resume);
    if (!Array.isArray(history) || history.length === 0) {
      if (resumeChanged) this.publish({ resume: normalizedResume });
      return;
    }
    const currentMessages = this.state.messages.slice();
    const orderedHistory = [];
    const consumedMessageIds = new Set();
    for (const [index, source] of history.slice(-80).entries()) {
      const role = source.role === 'assistant' ? 'assistant' : 'user';
      const text = String(source.text || '').slice(0, 4000);
      const requestId = String(source.requestId || '');
      const time = source.time || '';
      const historyKey = requestId ? `${requestId}\0${role}` : `legacy\0${role}\0${time}\0${text}`;
      if (!text) continue;
      const matchingIndex = requestId ? currentMessages.findIndex(item => (item.historyRequestId || item.requestId) === requestId && item.role === role) : -1;
      const hydrated = {
        id: requestId ? `history:${requestId}:${role}` : `history:${index}:${time}`,
        requestId, role, text, time, createdAt: Date.parse(time) || 0, streaming: false,
      };
      if (matchingIndex >= 0) {
        const current = currentMessages[matchingIndex];
        consumedMessageIds.add(current.id);
        orderedHistory.push({ ...current, ...hydrated, id: current.id });
      } else if (!this.hydratedHistoryKeys.has(historyKey)) {
        this.hydratedHistoryKeys.add(historyKey);
        orderedHistory.push(hydrated);
      }
    }
    const completedRequestIds = new Set(history
      .filter(item => item?.role === 'assistant' && item.requestId)
      .map(item => String(item.requestId)));
    const failedMessages = this.state.failedMessages.filter(item => !completedRequestIds.has(item.historyRequestId || item.requestId));
    const localOnly = currentMessages.filter(item => !consumedMessageIds.has(item.id));
    const messages = [...orderedHistory, ...localOnly];
    const changed = JSON.stringify(messages) !== JSON.stringify(this.state.messages) || failedMessages.length !== this.state.failedMessages.length;
    if (changed || resumeChanged) {
      this.publish({
        messages, failedMessages, lastFailedText: failedMessages.at(-1)?.text || '',
        ...(resumeChanged ? { resume: normalizedResume } : {}),
      });
    }
  }
}

function browserStageChatScript() {
  return `window.StageChatController=${StageChatController.toString()};`;
}

module.exports = { StageChatController, browserStageChatScript };
