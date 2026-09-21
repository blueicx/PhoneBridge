const crypto = require('node:crypto');

const SENSITIVE_KEY_PATTERN = /(token|secret|password|authorization|cookie|apikey|api_key)/i;
const SENSITIVE_VALUE_PATTERN = /(bearer\s+[a-z0-9._-]+|sk-[a-z0-9_-]+|AIzaSy[a-z0-9_-]+)/i;

function redactSecret(value) {
  if (value == null) return value;
  const str = String(value);
  if (!str) return str;
  if (str.length <= 8) return '[REDACTED]';
  if (str.startsWith('sk-')) {
    const tail = str.slice(-4);
    return `sk-***${tail}`;
  }
  return `${str.slice(0, 3)}***${str.slice(-4)}`;
}

function isRedactedPlaceholder(value) {
  if (!value) return false;
  const str = String(value).trim();
  return str === '[REDACTED]' || str.includes('***');
}

function sanitizeHeaders(headers = {}) {
  const result = {};
  for (const [k, v] of Object.entries(headers || {})) {
    if (SENSITIVE_KEY_PATTERN.test(k) || SENSITIVE_VALUE_PATTERN.test(String(v))) {
      result[k] = '[REDACTED]';
    } else {
      result[k] = v;
    }
  }
  return result;
}

function sanitizeProviderConfig(config = {}) {
  if (!config || typeof config !== 'object') return {};
  const sanitized = { ...config };
  if (sanitized.apiKey) sanitized.apiKey = redactSecret(sanitized.apiKey);
  if (sanitized.headers) sanitized.headers = sanitizeHeaders(sanitized.headers);
  return sanitized;
}

class LocalRuleFallbackAdapter {
  constructor() {
    this.id = 'local';
    this.name = '本地离线规则引擎';
    this.type = 'local';
  }

  async chat({ prompt = '', messages = [] } = {}) {
    const text = (prompt || messages.at(-1)?.text || '').trim();
    return {
      reply: this.generateReply(text),
      model: 'local-rules-v1'
    };
  }

  generateReply(text) {
    const lower = text.toLowerCase();
    if (/^(你好|hi|hello|在吗|早上好|晚上好)/.test(lower)) {
      return '你好！我是本地离线伴侣规则引擎。当前处于无外部网络离线模式，但我仍能协助你查看设备状态和任务。';
    }
    if (/电量|电池|battery/.test(lower)) {
      return '本地提示：请检查设备遥测卡片了解当前电池健康与电量。';
    }
    if (/任务|task/.test(lower)) {
      return '本地提示：当前任务中枢正常运行中，可直接在任务面板进行查看与管理。';
    }
    if (/温度|temp/.test(lower)) {
      return '本地提示：当前设备温度状态请参见感官与健康面板。';
    }
    return `[本地离线回退] 外部 AI 服务不可用或已降级。已安全接收你的指令：「${text.slice(0, 50)}」。`;
  }

  async probe() {
    return { ok: true, latencyMs: 1, model: 'local-rules-v1', status: 'healthy' };
  }

  capabilities() {
    return ['text', 'offline', 'cancel'];
  }
}

function requestSignal(timeoutMs) {
  return typeof AbortSignal?.timeout === 'function' ? AbortSignal.timeout(Math.max(1000, Number(timeoutMs) || 15000)) : undefined;
}

class OpenAiCompatibleAdapter {
  constructor({ fetchImpl = globalThis.fetch } = {}) {
    this.fetchImpl = fetchImpl;
    this.id = 'openai';
  }

  async chat({ prompt = '', messages = [], config = {}, timeoutMs = 15000 } = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) throw new Error('openai api key missing');
    const context = messages.length
      ? messages.map(item => ({ role: item.role || 'user', content: item.text || item.content || '' }))
      : [{ role: 'user', content: prompt }];
    const response = await this.fetchImpl(`${String(config.endpoint || '').replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${config.apiKey}`, ...(config.headers || {}) },
      body: JSON.stringify({ model: config.model, messages: context, stream: false }),
      signal: requestSignal(timeoutMs)
    });
    if (!response.ok) throw new Error(`openai provider HTTP ${response.status}`);
    const data = await response.json();
    const reply = data.choices?.[0]?.message?.content || data.choices?.[0]?.text || '';
    if (!String(reply).trim()) throw new Error('openai provider returned empty response');
    return { reply: String(reply), model: data.model || config.model };
  }

  async probe(config = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) return { latencyMs: 0, model: config.model, status: 'unconfigured' };
    const started = Date.now();
    const response = await this.fetchImpl(`${String(config.endpoint || '').replace(/\/$/, '')}/models`, {
      method: 'GET',
      headers: { authorization: `Bearer ${config.apiKey}`, ...(config.headers || {}) },
      signal: requestSignal(5000)
    });
    if (!response.ok) throw new Error(`openai provider HTTP ${response.status}`);
    return { latencyMs: Date.now() - started, model: config.model, status: 'healthy' };
  }
}

class GeminiCompatibleAdapter {
  constructor({ fetchImpl = globalThis.fetch } = {}) {
    this.fetchImpl = fetchImpl;
    this.id = 'gemini';
  }

  async chat({ prompt = '', config = {}, timeoutMs = 15000 } = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) throw new Error('gemini api key missing');
    const endpoint = `${String(config.endpoint || '').replace(/\/$/, '')}/models/${encodeURIComponent(config.model)}:generateContent?key=${encodeURIComponent(config.apiKey)}`;
    const response = await this.fetchImpl(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(config.headers || {}) },
      body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }] }),
      signal: requestSignal(timeoutMs)
    });
    if (!response.ok) throw new Error(`gemini provider HTTP ${response.status}`);
    const data = await response.json();
    const reply = data.candidates?.[0]?.content?.parts?.map(part => part.text || '').join('') || '';
    if (!String(reply).trim()) throw new Error('gemini provider returned empty response');
    return { reply: String(reply), model: config.model };
  }

  async probe(config = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) return { latencyMs: 0, model: config.model, status: 'unconfigured' };
    return { latencyMs: 0, model: config.model, status: 'configured' };
  }
}

class AiProviderManager {
  constructor({
    activeProviderId = 'codex',
    timeoutMs = 15000,
    configs = {},
    adapters = {},
    fetchImpl = globalThis.fetch,
    enableNetworkAdapters = true,
    now = () => Date.now(),
    persistence = null,
    maxOutputTokens = 1024,
    dailyOutputTokenBudget = 50000
  } = {}) {
    this.activeProviderId = activeProviderId;
    this.fallbackToLocal = true;
    this.timeoutMs = timeoutMs;
    this.localRulesEnabled = true;
    this.degradationCount = 0;
    this.lastLatencyMs = 0;
    this.lastProbedAt = null;
    this.now = now;
    this.fetchImpl = fetchImpl;
    this.persistence = persistence;
    this.maxOutputTokens = Math.max(64, Math.min(8192, Number(maxOutputTokens) || 1024));
    this.dailyOutputTokenBudget = Math.max(0, Math.min(1000000, Number(dailyOutputTokenBudget) || 0));
    this.dailyOutputTokens = 0;
    this.budgetDay = new Date(this.now()).toISOString().slice(0, 10);
    this.activeRequests = new Map();

    // Real raw configs
    this.configs = {
      codex: {
        id: 'codex',
        name: 'Codex 命令行桥接',
        type: 'codex',
        model: 'codex-chat',
        status: 'ready',
        capabilities: ['text', 'stream', 'tools', 'cancel']
      },
      openai: {
        id: 'openai',
        name: 'OpenAI-Compatible',
        type: 'openai',
        endpoint: 'https://api.openai.com/v1',
        model: 'gpt-4o-mini',
        apiKey: '',
        headers: {},
        status: 'configured',
        capabilities: ['text', 'stream', 'cancel']
      },
      gemini: {
        id: 'gemini',
        name: 'Gemini-Compatible',
        type: 'gemini',
        endpoint: 'https://generativelanguage.googleapis.com/v1beta',
        model: 'gemini-1.5-flash',
        apiKey: '',
        headers: {},
        status: 'configured',
        capabilities: ['text', 'vision', 'stream', 'cancel']
      },
      local: {
        id: 'local',
        name: '本地离线规则',
        type: 'local',
        model: 'local-rules-v1',
        status: 'ready',
        capabilities: ['text', 'offline', 'cancel']
      }
    };

    // Apply any initial configs
    for (const [id, cfg] of Object.entries(configs)) {
      if (this.configs[id]) {
        this.configs[id] = { ...this.configs[id], ...cfg };
      } else {
        this.configs[id] = { id, ...cfg };
      }
    }

    // Custom or mock adapters
    this.adapters = {
      local: new LocalRuleFallbackAdapter(),
      ...(enableNetworkAdapters ? {
        openai: new OpenAiCompatibleAdapter({ fetchImpl }),
        gemini: new GeminiCompatibleAdapter({ fetchImpl })
      } : {}),
      ...adapters
    };
    this._loadPersistedSettings();
  }

  _loadPersistedSettings() {
    if (!this.persistence) return;
    const saved = this.persistence.load('provider-settings', {});
    if (saved.activeProviderId && this.configs[saved.activeProviderId]) this.activeProviderId = saved.activeProviderId;
    if (saved.timeoutMs !== undefined) this.timeoutMs = Math.max(1000, Math.min(120000, Number(saved.timeoutMs) || 15000));
    if (saved.fallbackToLocal !== undefined) this.fallbackToLocal = Boolean(saved.fallbackToLocal);
    if (saved.localRulesEnabled !== undefined) this.localRulesEnabled = Boolean(saved.localRulesEnabled);
    if (saved.maxOutputTokens !== undefined) this.maxOutputTokens = Math.max(64, Math.min(8192, Number(saved.maxOutputTokens) || 1024));
    if (saved.dailyOutputTokenBudget !== undefined) this.dailyOutputTokenBudget = Math.max(0, Math.min(1000000, Number(saved.dailyOutputTokenBudget) || 0));
    for (const [id, incoming] of Object.entries(saved.providers || {})) {
      if (!this.configs[id]) continue;
      this.configs[id] = { ...this.configs[id], ...incoming };
    }
  }

  _persistSettings() {
    if (!this.persistence) return;
    const providers = {};
    for (const [id, config] of Object.entries(this.configs)) {
      const { apiKey, headers, ...safe } = config;
      providers[id] = { ...safe };
    }
    this.persistence.save('provider-settings', {
      activeProviderId: this.activeProviderId,
      fallbackToLocal: this.fallbackToLocal,
      timeoutMs: this.timeoutMs,
      localRulesEnabled: this.localRulesEnabled,
      maxOutputTokens: this.maxOutputTokens,
      dailyOutputTokenBudget: this.dailyOutputTokenBudget,
      providers,
    });
  }

  getProviders() {
    return Object.values(this.configs).map(c => {
      const sanitized = sanitizeProviderConfig(c);
      return {
        ...sanitized,
        capabilities: Array.isArray(c.capabilities) ? [...c.capabilities] : this.adapterCapabilities(c.id),
        isActive: sanitized.id === this.activeProviderId
      };
    });
  }

  getRawProviderConfig(id) {
    return this.configs[id] ? { ...this.configs[id] } : null;
  }

  getSettings() {
    return {
      activeProviderId: this.activeProviderId,
      fallbackToLocal: this.fallbackToLocal,
      timeoutMs: this.timeoutMs,
      localRulesEnabled: this.localRulesEnabled,
      maxOutputTokens: this.maxOutputTokens,
      dailyOutputTokenBudget: this.dailyOutputTokenBudget,
      dailyOutputTokens: this.dailyOutputTokens,
      budgetRemaining: this.budgetRemaining(),
      degradationCount: this.degradationCount,
      providers: this.getProviders()
    };
  }

  updateSettings(patch = {}) {
    if (patch.activeProviderId && this.configs[patch.activeProviderId]) {
      this.activeProviderId = String(patch.activeProviderId);
    }
    if (patch.timeoutMs !== undefined) {
      this.timeoutMs = Math.max(1000, Math.min(120000, Number(patch.timeoutMs) || 15000));
    }
    if (patch.fallbackToLocal !== undefined) {
      this.fallbackToLocal = Boolean(patch.fallbackToLocal);
    }
    if (patch.localRulesEnabled !== undefined) {
      this.localRulesEnabled = Boolean(patch.localRulesEnabled);
    }
    if (patch.maxOutputTokens !== undefined) {
      this.maxOutputTokens = Math.max(64, Math.min(8192, Number(patch.maxOutputTokens) || 1024));
    }
    if (patch.dailyOutputTokenBudget !== undefined) {
      this.dailyOutputTokenBudget = Math.max(0, Math.min(1000000, Number(patch.dailyOutputTokenBudget) || 0));
    }

    if (patch.providers && typeof patch.providers === 'object') {
      for (const [id, incoming] of Object.entries(patch.providers)) {
        if (!this.configs[id]) continue;
        const current = this.configs[id];
        const next = { ...current, ...incoming };

        // If incoming apiKey is a masked placeholder, preserve existing raw apiKey
        if (incoming.apiKey && isRedactedPlaceholder(incoming.apiKey)) {
          next.apiKey = current.apiKey;
        }
        this.configs[id] = next;
      }
    }

    this._persistSettings();
    return this.getSettings();
  }

  getDegradationCount() {
    return this.degradationCount;
  }

  adapterCapabilities(id) {
    const config = this.configs[id] || {};
    if (Array.isArray(config.capabilities)) return [...config.capabilities];
    const adapter = this.adapters[id];
    if (adapter && typeof adapter.capabilities === 'function') return adapter.capabilities();
    return ['text'];
  }

  budgetRemaining() {
    this.resetBudgetIfNeeded();
    return this.dailyOutputTokenBudget === 0
      ? null
      : Math.max(0, this.dailyOutputTokenBudget - this.dailyOutputTokens);
  }

  resetBudgetIfNeeded() {
    const day = new Date(this.now()).toISOString().slice(0, 10);
    if (day !== this.budgetDay) {
      this.budgetDay = day;
      this.dailyOutputTokens = 0;
    }
  }

  estimateTokens(text) {
    return Math.max(1, Math.ceil(String(text || '').length / 4));
  }

  consumeBudget(text) {
    this.resetBudgetIfNeeded();
    const amount = this.estimateTokens(text);
    if (this.dailyOutputTokenBudget > 0 && this.dailyOutputTokens + amount > this.dailyOutputTokenBudget) {
      throw new Error('AI daily output budget exhausted');
    }
    this.dailyOutputTokens += amount;
  }

  cancel(requestId) {
    const controller = this.activeRequests.get(String(requestId || ''));
    if (!controller) return false;
    controller.abort();
    return true;
  }

  async probeProvider(providerId) {
    const id = String(providerId || '');
    const config = this.configs[id];
    if (!config) {
      return { ok: false, providerId: id, error: `Provider ${id} not found` };
    }

    const start = this.now();
    try {
      if (this.adapters[id] && typeof this.adapters[id].probe === 'function') {
        const res = await this.adapters[id].probe(config);
        const latency = this.now() - start;
        return {
          ok: true,
          providerId: id,
          latencyMs: res.latencyMs || latency,
          model: res.model || config.model,
          status: 'healthy'
        };
      }

      if (id === 'local') {
        return { ok: true, providerId: id, latencyMs: 1, model: config.model, status: 'healthy' };
      }

      // Default mock/stub probe for registered network providers
      const latency = Math.max(5, this.now() - start);
      return {
        ok: true,
        providerId: id,
        latencyMs: latency,
        model: config.model,
        status: config.apiKey ? 'healthy' : 'unconfigured'
      };
    } catch (error) {
      return {
        ok: false,
        providerId: id,
        latencyMs: this.now() - start,
        error: error.message
      };
    }
  }

  async chat({ prompt = '', messages = [], memories = [], providerId = null, options = {}, requestId = null, signal = null } = {}) {
    const targetProviderId = providerId || this.activeProviderId;
    const start = this.now();
    const id = String(requestId || `ai_${crypto.randomUUID()}`);
    const controller = new AbortController();
    const onAbort = () => controller.abort();
    if (signal) {
      if (signal.aborted) throw new Error('AI request cancelled');
      signal.addEventListener('abort', onAbort, { once: true });
    }
    this.activeRequests.set(id, controller);
    const requestSignalValue = controller.signal;

    // 1. Try explicit target provider
    try {
      if (targetProviderId !== 'local') {
        try {
          const adapter = this.adapters[targetProviderId];
          if (adapter && typeof adapter.chat === 'function') {
            const res = await adapter.chat({
              prompt,
              messages,
              memories,
              options,
              timeoutMs: this.timeoutMs,
              config: this.configs[targetProviderId],
              signal: requestSignalValue
            });
            this.consumeBudget(res.reply || '');
            this.lastLatencyMs = this.now() - start;
            return {
              ok: true,
            id,
            providerId: targetProviderId,
            reply: res.reply || '',
            model: res.model || this.configs[targetProviderId]?.model,
            degraded: false
          };
        }
        throw new Error(`Provider ${targetProviderId} adapter unavailable`);
        } catch (error) {
          if (requestSignalValue.aborted) throw new Error('AI request cancelled');
        // Fallback rule:
        // "显式 provider 优先，失败只回退本地，备用联网 provider 不自动调用"
        if (!this.fallbackToLocal || !this.localRulesEnabled) throw error;
        this.degradationCount++;
        // Explicitly fallback ONLY to local
        const localAdapter = this.adapters.local || new LocalRuleFallbackAdapter();
        const fallbackRes = await localAdapter.chat({ prompt, messages, signal: requestSignalValue });
        this.consumeBudget(fallbackRes.reply || '');
        this.lastLatencyMs = this.now() - start;
        return {
          ok: true,
          id,
          providerId: targetProviderId,
          fallbackProvider: 'local',
          reply: fallbackRes.reply,
          model: fallbackRes.model,
          degraded: true,
          error: error.message
        };
        }
      }

      // Direct local call
      const localAdapter = this.adapters.local || new LocalRuleFallbackAdapter();
      const fallbackRes = await localAdapter.chat({ prompt, messages, signal: requestSignalValue });
      if (requestSignalValue.aborted) throw new Error('AI request cancelled');
      this.consumeBudget(fallbackRes.reply || '');
      this.lastLatencyMs = this.now() - start;
      return {
        ok: true,
        id,
        providerId: 'local',
        reply: fallbackRes.reply,
        model: fallbackRes.model,
        degraded: false
      };
    } finally {
      this.activeRequests.delete(id);
      if (signal) signal.removeEventListener('abort', onAbort);
    }
  }

  async *stream(request = {}) {
    const requestId = String(request.requestId || `ai_${crypto.randomUUID()}`);
    const targetProviderId = request.providerId || this.activeProviderId;
    const adapter = this.adapters[targetProviderId];
    if (adapter && typeof adapter.stream === 'function') {
      const controller = new AbortController();
      this.activeRequests.set(requestId, controller);
      try {
        for await (const delta of adapter.stream({ ...request, requestId, signal: controller.signal, config: this.configs[targetProviderId], timeoutMs: this.timeoutMs })) {
          if (controller.signal.aborted) throw new Error('AI request cancelled');
          yield String(delta);
        }
        return;
      } finally {
        this.activeRequests.delete(requestId);
      }
    }
    const result = await this.chat({ ...request, requestId });
    const text = String(result.reply || '');
    for (const chunk of text.match(/.{1,24}/gu) || []) yield chunk;
  }
}

module.exports = {
  AiProviderManager,
  LocalRuleFallbackAdapter,
  sanitizeProviderConfig,
  redactSecret,
  isRedactedPlaceholder
};
